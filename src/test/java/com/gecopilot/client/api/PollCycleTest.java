package com.gecopilot.client.api;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Poll cadence and refresh depth by panel visibility. */
public class PollCycleTest {
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicBoolean minimised = new AtomicBoolean(false);
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<Dashboard> rendered = new ArrayList<>();
    private boolean ready = true;
    private boolean serverUp = true;
    private boolean throwOnSync = false;
    private PollPolicy policy;
    private PollCycle cycle;

    private final class Recorder implements PollCycle.Steps {
        @Override public boolean ready() { return ready; }
        @Override public void beforeSync() { calls.add("before"); }
        @Override public void flushQueues() { calls.add("flush"); }
        @Override public SyncResult sync() {
            calls.add("flips");
            if (throwOnSync) throw new IllegalStateException("boom");
            SyncResult r = new SyncResult();
            if (serverUp) r.tier = "premium";
            return r;
        }
        @Override public Dashboard dashboard() { calls.add("dashboard"); return serverUp ? new Dashboard() : null; }
        @Override public void render(SyncResult s, Dashboard d) { calls.add("render"); rendered.add(d); }
        @Override public void logPlan(SyncResult s) { calls.add("planShown"); }
        @Override public void nudge(SyncResult s) { calls.add("nudge"); }
        @Override public void done(boolean ok) { calls.add(ok ? "ok" : "failed"); }
    }

    @Before public void up() {
        policy = new PollPolicy(clock::get, minimised::get);
        cycle = new PollCycle(policy, new Recorder());
    }

    /** Simulate the fixed-delay timer for {@code minutes}, one tick a minute; returns /api/flips posts. */
    private int runTicks(int minutes) {
        int before = count("flips");
        for (int i = 0; i < minutes; i++) {
            clock.addAndGet(PollPolicy.TICK_MS);
            cycle.onTick();
        }
        return count("flips") - before;
    }

    private int count(String what) {
        synchronized (calls) { return (int) calls.stream().filter(what::equals).count(); }
    }

    @Test public void visiblePanelPollsEveryTick() {
        policy.setPanelVisible(true);
        assertEquals(30, runTicks(30));
        assertEquals(30, count("dashboard"));
        assertEquals(30, count("planShown"));
    }

    @Test public void hiddenPanelPollsEveryTenMinutes() {
        cycle.onTick();               // startup tick: first sync of the session always goes out
        assertEquals(1, count("flips"));
        assertEquals(0, runTicks(9));  // 1..9 min since the last sync
        assertEquals(1, runTicks(1));  // 10 min
        assertEquals(3, runTicks(30));
    }

    @Test public void hiddenCadenceToleratesTimerDrift() {
        cycle.onTick();
        // Fixed-delay ticks land slightly under a minute apart relative to the stamped sync.
        for (int i = 0; i < 10; i++) { clock.addAndGet(PollPolicy.TICK_MS - 150); cycle.onTick(); }
        assertEquals(2, count("flips"));
    }

    @Test public void hiddenPollIsLight() {
        cycle.onTick();
        assertEquals(List.of("before", "flush", "flips", "render", "nudge", "ok"), calls);
        assertNull("no dashboard while hidden; panel keeps its last one", rendered.get(0));
        assertEquals(0, count("dashboard"));
        assertEquals(0, count("planShown"));
    }

    @Test public void geEventRefreshFollowsVisibility() {
        cycle.refresh();
        assertEquals(1, count("flips"));
        assertEquals(1, count("flush"));
        assertEquals(1, count("nudge"));
        assertEquals(0, count("dashboard"));
        assertEquals(0, count("planShown"));
        policy.setPanelVisible(true);
        cycle.refresh();
        assertEquals(1, count("dashboard"));
        assertEquals(1, count("planShown"));
    }

    @Test public void geEventSyncResetsTheHiddenClock() {
        cycle.onTick();
        clock.addAndGet(8 * PollPolicy.TICK_MS);
        cycle.refresh();              // an offer changed 8 min in
        assertEquals(2, count("flips"));
        assertEquals(0, runTicks(9)); // next timer poll is 10 min after that sync, not after the first
        assertEquals(1, runTicks(1));
    }

    @Test public void openingThePanelRefreshesFullyAtOnce() {
        cycle.onTick();
        clock.addAndGet(3 * PollPolicy.TICK_MS);
        policy.setPanelVisible(true);
        cycle.refreshFull();          // what onActivate schedules
        assertEquals(2, count("flips"));
        assertEquals(1, count("dashboard"));
        assertEquals(1, count("planShown"));
        assertNotNull(rendered.get(1));
        assertEquals("visible again: back to every tick", 1, runTicks(1));
    }

    @Test public void closingThePanelReturnsToHiddenCadence() {
        policy.setPanelVisible(true);
        runTicks(3);
        policy.setPanelVisible(false);
        assertEquals(0, runTicks(9));
        assertEquals(1, runTicks(1));
    }

    @Test public void minimisedClientCountsAsHidden() {
        policy.setPanelVisible(true);
        minimised.set(true);
        assertFalse(policy.visible());
        cycle.onTick();
        assertEquals(0, count("dashboard"));
        assertEquals(0, runTicks(9));
        minimised.set(false);
        assertEquals(1, runTicks(1));
        assertEquals(1, count("dashboard"));
    }

    @Test public void throwingMinimisedProbeFallsBackToPanelState() {
        PollPolicy p = new PollPolicy(clock::get, () -> { throw new IllegalStateException("no frame"); });
        p.setPanelVisible(true);
        assertTrue(p.visible());
    }

    @Test public void signedOutDoesNothingAndKeepsTheFirstSyncDue() {
        ready = false;
        runTicks(5);
        assertEquals(0, count("flips"));
        ready = true;
        assertEquals("first signed-in tick syncs right away", 1, runTicks(1));
    }

    @Test public void hiddenIntervalStaysInsideServerWindows() {
        // Over Neon's 5-min autosuspend + 30s pool idle timeout, under the 15-min crowd-count window
        // even with a full tick of lag.
        assertTrue(PollPolicy.HIDDEN_INTERVAL_MS > 5 * 60_000L + 30_000L);
        assertTrue(PollPolicy.HIDDEN_INTERVAL_MS + PollPolicy.TICK_MS < 15 * 60_000L);
    }

    @Test public void reportsSuccessWhenTheServerAnswers() {
        policy.setPanelVisible(true);
        cycle.refreshFull();
        assertEquals(1, count("ok"));
        assertEquals(0, count("failed"));
    }

    @Test public void reportsFailureWhenSyncOrDashboardFails() {
        serverUp = false;
        cycle.refreshFull();
        assertEquals(1, count("failed"));
        cycle.refresh();              // light refresh, sync empty
        assertEquals(2, count("failed"));
    }

    @Test public void reportsFailureEvenWhenAStepThrows() {
        throwOnSync = true;
        try { cycle.refreshFull(); fail("expected the exception to propagate"); }
        catch (IllegalStateException expected) { }
        assertEquals("header must not stick on Updating", 1, count("failed"));
    }

    @Test public void signedOutReportsFailureSoTheCueCannotStick() {
        ready = false;
        cycle.refreshFull();
        assertEquals(1, count("failed"));
        assertEquals(0, count("flips"));
    }

    @Test public void restoringAMinimisedClientWithThePanelOpenRefreshesAtOnce() {
        policy.setPanelVisible(true);
        assertFalse("minimising needs no refresh", policy.onWindowState(true));
        assertTrue("restored with the panel on screen", policy.onWindowState(false));
        assertFalse("a second restore event is not a new restore", policy.onWindowState(false));
    }

    @Test public void restoringWithThePanelClosedWaitsForTheHiddenCadence() {
        policy.onWindowState(true);
        assertFalse(policy.onWindowState(false));
    }

    @Test public void maximiseOrOtherStateChangesAreNotRestores() {
        policy.setPanelVisible(true);
        assertFalse(policy.onWindowState(false));
        assertFalse(policy.onWindowState(false));
    }
}
