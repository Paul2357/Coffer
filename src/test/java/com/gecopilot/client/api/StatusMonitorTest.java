package com.gecopilot.client.api;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class StatusMonitorTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final PollPolicy policy = new PollPolicy(now::get, () -> false);
    private final StatusMonitor m = new StatusMonitor(policy, now::get);

    { policy.setPanelVisible(true); }

    private static ServiceStatusDto status(String level, String message, Boolean db) {
        ServiceStatusDto s = new ServiceStatusDto();
        s.level = level;
        s.message = message;
        s.db = db;
        return s;
    }

    private StatusMonitor.Update check(ServiceStatusDto s) {
        assertTrue("check should be due", m.tryStart());
        return m.attempt(() -> s);
    }

    private static final long MIN = 60_000L;

    @Test public void healthyMeansNoBannerAndFiveMinuteChecks() {
        StatusMonitor.Update u = check(status(null, null, true));
        assertNull(u.banner);
        assertFalse(m.degraded());
        assertNull(u.chatNotice);
        now.addAndGet(4 * MIN);
        assertFalse(m.due());
        now.addAndGet(MIN);
        assertTrue(m.due());
    }

    @Test public void explicitMessageWinsOverAutoDetect() {
        ServiceStatusDto s = status("warning", "Fills may lag while we upgrade the database.", false);
        s.link = "https://meetcoffer.com/status";
        s.since = "2026-10-19T14:00:00Z";
        StatusMonitor.Banner b = check(s).banner;
        assertEquals(StatusMonitor.Level.WARNING, b.level);
        assertEquals("Fills may lag while we upgrade the database.", b.text);
        assertEquals("https://meetcoffer.com/status", b.link);
        assertEquals("2026-10-19T14:00:00Z", b.since);
        assertFalse(b.auto);
        assertTrue("db:false still backs off", m.degraded());
    }

    @Test public void autoDetectOutageWhenUnreachableOrDbDown() {
        StatusMonitor.Banner down = check(status(null, null, false)).banner;
        assertEquals(StatusMonitor.Level.OUTAGE, down.level);
        assertEquals(StatusMonitor.DEFAULT_OUTAGE, down.text);
        assertTrue(down.auto);

        StatusMonitor fresh = new StatusMonitor(policy, now::get);
        assertTrue(fresh.tryStart());
        StatusMonitor.Banner unreachable = fresh.attempt(() -> null).banner;
        assertEquals(down, unreachable);
        assertTrue(fresh.degraded());

        StatusMonitor thrown = new StatusMonitor(policy, now::get);
        assertTrue(thrown.tryStart());
        assertEquals(down, thrown.attempt(() -> { throw new IllegalStateException("timeout"); }).banner);
    }

    @Test public void unknownDbAndOldServerShowNothing() {
        assertNull(check(status(null, null, null)).banner);   // nothing has tried the DB yet
        now.addAndGet(5 * MIN);
        assertNull(check(new ServiceStatusDto()).banner);     // 404 from a server without /api/status
        assertFalse(m.degraded());
    }

    @Test public void levelWithoutMessage() {
        assertEquals(StatusMonitor.DEFAULT_OUTAGE, check(status("outage", "  ", true)).banner.text);
        assertTrue("an announced outage backs off too", m.degraded());
        StatusMonitor other = new StatusMonitor(policy, now::get);
        assertTrue(other.tryStart());
        assertNull("info with nothing to say shows nothing", other.attempt(() -> status("info", null, true)).banner);
    }

    @Test public void textIsPlainAndLinksAreHttpsOnly() {
        ServiceStatusDto s = status("info", "a\nb\tc\u0007d", true);
        s.link = "http://meetcoffer.com";
        StatusMonitor.Banner b = check(s).banner;
        assertEquals("a b c d", b.text);
        assertNull(b.link);
        assertNull(StatusMonitor.httpsOrNull("javascript:alert(1)"));
        assertNull(StatusMonitor.httpsOrNull("https://u:p@evil.example"));
        assertEquals("https://meetcoffer.com/x", StatusMonitor.httpsOrNull("https://meetcoffer.com/x"));
        assertEquals(StatusMonitor.MAX_TEXT, StatusMonitor.clean("y".repeat(900)).length());
    }

    @Test public void outageBacksOffOneTwoFiveTenFifteenThenResets() {
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            check(null);
            long waited = 0;
            while (!m.due()) { now.addAndGet(1_000L); waited += 1_000L; }
            waits.add(Math.round(waited / (double) MIN));
        }
        assertEquals(List.of(1L, 2L, 5L, 10L, 15L, 15L, 15L), waits);

        StatusMonitor.Update back = check(status(null, null, true));
        assertTrue(back.recovered);
        assertNull("banner clears on recovery", back.banner);
        assertTrue(back.changed);
        assertFalse(m.degraded());
        assertEquals(StatusMonitor.HEALTHY_INTERVAL_MS, m.intervalMs());

        now.addAndGet(StatusMonitor.HEALTHY_INTERVAL_MS);
        check(null);
        assertEquals("a new outage starts the schedule again", MIN, m.intervalMs());
    }

    @Test public void hiddenPanelNeverChecksFasterThanTheHiddenPoll() {
        check(null);
        policy.setPanelVisible(false);
        assertEquals(PollPolicy.HIDDEN_INTERVAL_MS, m.intervalMs());
        policy.setPanelVisible(true);
        assertEquals(MIN, m.intervalMs());
        StatusMonitor minimised = new StatusMonitor(new PollPolicy(now::get, () -> true), now::get);
        assertEquals(PollPolicy.HIDDEN_INTERVAL_MS, minimised.intervalMs());
    }

    @Test public void chatNoticeOncePerDistinctBannerPerSession() {
        List<String> notices = new ArrayList<>();
        ServiceStatusDto a = status("warning", "Maintenance at 18:00 UTC", true);
        ServiceStatusDto b = status("outage", "Database upgrade in progress", true);
        ServiceStatusDto[] seq = {a, a, null, a, b, b, status(null, null, true), b, a};
        for (ServiceStatusDto s : seq) {
            StatusMonitor.Update u = check(s);
            if (u.chatNotice != null) notices.add(u.chatNotice);
            now.addAndGet(20 * MIN);
        }
        assertEquals(List.of("Maintenance at 18:00 UTC", StatusMonitor.DEFAULT_OUTAGE, "Database upgrade in progress"), notices);
    }

    @Test public void sameBannerIsNotReRendered() {
        assertTrue(check(status("info", "Hello", true)).changed);
        now.addAndGet(5 * MIN);
        assertFalse(check(status("info", "Hello", true)).changed);
        now.addAndGet(5 * MIN);
        assertTrue(check(status("info", "Hello again", true)).changed);
    }

    @Test public void oneCheckInFlightAndAnErrorCannotWedgeIt() {
        assertTrue(m.tryStart());
        assertFalse("second check while one is in flight", m.tryStart());
        try {
            m.attempt(() -> { throw new AssertionError("boom"); });
            fail();
        } catch (AssertionError expected) { }
        assertTrue("recorded as unreachable", m.degraded());
        now.addAndGet(MIN);
        assertTrue("next check is allowed after the backoff", m.tryStart());
    }

    /** Records which requests a run of timer ticks makes. */
    private List<String> runTicks(int ticks, java.util.function.Supplier<ServiceStatusDto> server) {
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            m.tick(() -> calls.add("tick"), () -> calls.add("probe-sync"),
                () -> { calls.add("status"); return server.get(); }, u -> { });
            now.addAndGet(PollPolicy.TICK_MS);
        }
        return calls;
    }

    @Test public void healthyTicksPollEveryMinuteAndCheckStatusEveryFive() {
        List<String> calls = runTicks(10, () -> status(null, null, true));
        assertEquals(10, calls.stream().filter("tick"::equals).count());
        assertEquals(2, calls.stream().filter("status"::equals).count()); // t=0 and t=5min
        assertEquals(0, calls.stream().filter("probe-sync"::equals).count());
        assertEquals("regular poll goes first, so its sync refreshes the server's db flag", "tick", calls.get(0));
    }

    @Test public void outagePausesTheRegularPollAndProbesOncePerStep() {
        // 0: healthy tick, check -> down. Then checks due after 1, 2, 5, 10, 15 min of waiting.
        List<String> calls = runTicks(60, () -> null);
        assertEquals("only the first tick before the outage was known", 1, calls.stream().filter("tick"::equals).count());
        long checks = calls.stream().filter("status"::equals).count();
        assertEquals("checks at minutes 0, 1, 3, 8, 18, 33, 48", 7, checks);
        assertEquals(checks - 1, calls.stream().filter("probe-sync"::equals).count());
        int i = calls.indexOf("probe-sync");
        assertEquals("probe sync runs right before the status check", "status", calls.get(i + 1));
    }

    @Test public void recoveryResumesTheRegularPoll() {
        boolean[] up = {false};
        List<String> calls = runTicks(3, () -> up[0] ? status(null, null, true) : null);
        up[0] = true;
        calls.addAll(runTicks(4, () -> status(null, null, true)));
        // t0 tick + check (down); t1 probe + check (down); t2 waiting; t3 probe + check (up); t4.. regular
        assertEquals(List.of("tick", "status", "probe-sync", "status", "probe-sync", "status", "tick", "tick", "tick"), calls);
    }

    @Test public void ownFailingCallsFillInAnUnknownDb() {
        assertEquals(Boolean.FALSE, StatusMonitor.withOwnCalls(status(null, null, null), true).db);
        assertNull(StatusMonitor.withOwnCalls(status(null, null, null), false).db);
        assertEquals("the server's answer wins", Boolean.TRUE, StatusMonitor.withOwnCalls(status(null, null, true), true).db);
        assertNull("unreachable stays unreachable", StatusMonitor.withOwnCalls(null, true));
        StatusMonitor.Banner b = check(StatusMonitor.withOwnCalls(new ServiceStatusDto(), true)).banner;
        assertEquals(StatusMonitor.DEFAULT_OUTAGE, b.text);
        assertTrue(b.auto);
    }

    @Test public void dbFalseAloneKeepsTheRegularPollRunning() {
        // The server answers; only its passive db flag is false. The plugin's own syncs are what
        // reconnect and clear it, so they must keep running.
        List<String> calls = runTicks(10, () -> status(null, null, false));
        assertEquals(10, calls.stream().filter("tick"::equals).count());
        assertEquals(0, calls.stream().filter("probe-sync"::equals).count());
        assertEquals("status checks still back off: minutes 0, 1, 3, 8", 4, calls.stream().filter("status"::equals).count());
        assertTrue(m.degraded());
        assertFalse(m.pausesRegularPoll());
        assertEquals(StatusMonitor.DEFAULT_OUTAGE, m.banner().text);
    }

    @Test public void dbFlagClearingClearsTheBanner() {
        runTicks(2, () -> status(null, null, false));
        assertNotNull(m.banner());
        List<String> calls = runTicks(2, () -> status(null, null, true));
        assertTrue(calls.contains("status"));
        assertNull(m.banner());
        assertFalse(m.degraded());
    }

    @Test public void unreachableOrAnnouncedOutagePausesTheRegularPoll() {
        check(null);
        assertTrue(m.pausesRegularPoll());
        StatusMonitor other = new StatusMonitor(policy, now::get);
        assertTrue(other.tryStart());
        other.attempt(() -> status("outage", "Maintenance", true));
        assertTrue(other.pausesRegularPoll());
        StatusMonitor warn = new StatusMonitor(policy, now::get);
        assertTrue(warn.tryStart());
        warn.attempt(() -> status("warning", "Slow", true));
        assertFalse(warn.pausesRegularPoll());
    }

    @Test public void cleanDropsBidiAndZeroWidthCharacters() {
        assertEquals("Hello world", StatusMonitor.clean("Hel\u200Blo \u202Eworld\u202C\uFEFF"));
        assertEquals("ab", StatusMonitor.clean("a\u2066\u200Db"));
    }
}
