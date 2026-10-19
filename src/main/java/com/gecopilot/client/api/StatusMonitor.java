package com.gecopilot.client.api;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Service-status banner state and the /api/status check cadence. Pure (no Swing, no I/O) so the
 * rules are testable.
 *
 * <p>Banner: an explicit message from the server wins. Without one, the server being unreachable
 * (timeout, connection error, 5xx) or reporting {@code db:false} shows {@link #DEFAULT_OUTAGE} as
 * an outage. Otherwise there is no banner.
 *
 * <p>Cadence: every {@link #HEALTHY_INTERVAL_MS} while healthy. While degraded (unreachable,
 * {@code db:false} or an explicit outage) the wait grows along {@link #OUTAGE_STEPS_MS} and resets
 * on recovery. A hidden panel never checks more often than {@link PollPolicy#HIDDEN_INTERVAL_MS},
 * the same rule the main poll follows. Like {@link QuoteBackoff}, one check is in flight at a time
 * and {@link #attempt} always records the outcome, so nothing thrown can wedge it.
 */
public final class StatusMonitor {
    public static final long HEALTHY_INTERVAL_MS = 5 * 60_000L;
    /** Waits after the 1st, 2nd, 3rd, 4th and later consecutive degraded checks: 1, 2, 5, 10, 15 min. */
    static final long[] OUTAGE_STEPS_MS = {60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L};
    public static final String DEFAULT_OUTAGE = "Coffer is down for maintenance";
    static final int MAX_TEXT = 280;

    public enum Level { INFO, WARNING, OUTAGE }

    /** What the banner shows. Equal banners never re-render or re-notify. */
    public static final class Banner {
        public final Level level;
        public final String text;
        public final String link;      // https or null
        public final String since;     // ISO-8601 instant or null
        public final boolean auto;     // derived from reachability, not a server message

        Banner(Level level, String text, String link, String since, boolean auto) {
            this.level = level;
            this.text = text;
            this.link = link;
            this.since = since;
            this.auto = auto;
        }

        /** Chat-notice dedupe key: one notice per distinct (level, message) per session. */
        String key() { return level + "|" + text; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Banner)) return false;
            Banner b = (Banner) o;
            return level == b.level && text.equals(b.text) && Objects.equals(link, b.link)
                && Objects.equals(since, b.since) && auto == b.auto;
        }
        @Override public int hashCode() { return Objects.hash(level, text, link, since, auto); }
        @Override public String toString() { return key(); }
    }

    /** Result of one check. */
    public static final class Update {
        public final Banner banner;       // null: no banner
        public final boolean changed;     // banner differs from the last one shown
        public final String chatNotice;   // non-null once per distinct banner per session
        public final boolean recovered;   // was degraded, now healthy

        Update(Banner banner, boolean changed, String chatNotice, boolean recovered) {
            this.banner = banner;
            this.changed = changed;
            this.chatNotice = chatNotice;
            this.recovered = recovered;
        }
    }

    private final PollPolicy policy;
    private final LongSupplier clockMs;
    private boolean checked = false;
    private long lastCheckMs = 0;
    private int degradedStreak = 0;
    /** Unreachable or an announced outage: the regular poll pauses. db:false alone does not pause it. */
    private boolean pauseRegular = false;
    private boolean inFlight = false;
    private Banner banner = null;
    private final Set<String> notified = new HashSet<>();

    /** @param policy supplies panel visibility; {@code clockMs} is monotonic milliseconds */
    public StatusMonitor(PollPolicy policy, LongSupplier clockMs) {
        this.policy = policy;
        this.clockMs = clockMs;
    }

    /** Whether a check is due now. */
    public synchronized boolean due() {
        if (inFlight) return false;
        if (!checked) return true;
        return clockMs.getAsLong() - lastCheckMs >= intervalMs() - PollPolicy.SLACK_MS;
    }

    /** Wait before the next check, given the current state and panel visibility. */
    synchronized long intervalMs() {
        long wait = degradedStreak > 0 ? stepMs(degradedStreak) : HEALTHY_INTERVAL_MS;
        return policy.visible() ? wait : Math.max(wait, PollPolicy.HIDDEN_INTERVAL_MS);
    }

    /** Claim the next check if it is due; the caller must then run {@link #attempt}. */
    public synchronized boolean tryStart() {
        if (!due()) return false;
        inFlight = true;
        return true;
    }

    /** Unreachable, db:false or an explicit outage on the last check; status checks back off. */
    public synchronized boolean degraded() { return degradedStreak > 0; }

    /**
     * The regular poll pauses (and probes once per backed-off check) only when the server is
     * unreachable or announced an outage. With db:false alone the server answers, and the plugin's
     * own regular syncs are what reconnect and clear the flag, so they keep running.
     */
    public synchronized boolean pausesRegularPoll() { return pauseRegular; }

    public synchronized Banner banner() { return banner; }

    /**
     * Run one check that {@link #tryStart} allowed. {@code fetch} returns the status, or null when the
     * server could not be reached; a throw counts as unreachable. The outcome is recorded in a finally.
     */
    public Update attempt(Supplier<ServiceStatusDto> fetch) {
        boolean recorded = false;
        try {
            ServiceStatusDto s;
            try { s = fetch.get(); } catch (RuntimeException e) { s = null; }
            Update u = record(s);
            recorded = true;
            return u;
        } finally {
            if (!recorded) record(null); // an Error from fetch still clears the in-flight mark
        }
    }

    synchronized Update record(ServiceStatusDto s) {
        inFlight = false;
        checked = true;
        lastCheckMs = clockMs.getAsLong();
        boolean wasDegraded = degradedStreak > 0;
        Banner next = bannerFor(s);
        boolean degradedNow = s == null || Boolean.FALSE.equals(s.db) || (next != null && !next.auto && next.level == Level.OUTAGE);
        degradedStreak = degradedNow ? degradedStreak + 1 : 0;
        pauseRegular = s == null || (next != null && !next.auto && next.level == Level.OUTAGE);
        boolean changed = !Objects.equals(next, banner);
        banner = next;
        String notice = next != null && notified.add(next.key()) ? next.text : null;
        return new Update(next, changed, notice, wasDegraded && !degradedNow);
    }

    /**
     * One timer tick. Healthy: the regular poll tick runs, then the status check if it is due.
     * Degraded: the regular poll pauses; when the backed-off check comes due, one sync runs first as
     * the probe (its database use is what tells the server whether the database is back), then the
     * check. So an outage costs one sync and one status request per step, not one per tick.
     *
     * @param regularTick the main poll's tick (it applies its own visible/hidden cadence)
     * @param probeSync   one sync now
     * @param fetch       GET /api/status; null when unreachable
     * @param apply       handles the check's result
     */
    public void tick(Runnable regularTick, Runnable probeSync, Supplier<ServiceStatusDto> fetch,
                     java.util.function.Consumer<Update> apply) {
        boolean paused = pausesRegularPoll();
        if (!paused) regularTick.run();
        if (!tryStart()) return;
        Update u = attempt(() -> {
            if (paused) probeSync.run();
            return fetch.get();
        });
        apply.accept(u);
    }

    /**
     * Fill in {@code db} from the plugin's own calls when the server didn't say (a server older than
     * /api/status, or one that has not used its database since boot): if the last authed call failed
     * with a 5xx or a network error, count the database as down.
     */
    public static ServiceStatusDto withOwnCalls(ServiceStatusDto s, boolean ownCallsFailing) {
        if (s != null && s.db == null && ownCallsFailing) s.db = Boolean.FALSE;
        return s;
    }

    static long stepMs(int streak) {
        int i = Math.min(Math.max(streak, 1), OUTAGE_STEPS_MS.length) - 1;
        return OUTAGE_STEPS_MS[i];
    }

    /** The banner for one check result; {@code s == null} means unreachable. */
    static Banner bannerFor(ServiceStatusDto s) {
        Level level = s == null ? null : level(s.level);
        if (level != null) {
            String text = clean(s.message);
            if (text == null && level == Level.OUTAGE) text = DEFAULT_OUTAGE;
            if (text != null) return new Banner(level, text, httpsOrNull(s.link), s.since, false);
        }
        if (s == null || Boolean.FALSE.equals(s.db)) return new Banner(Level.OUTAGE, DEFAULT_OUTAGE, null, null, true);
        return null;
    }

    static Level level(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "info": return Level.INFO;
            case "warning": return Level.WARNING;
            case "outage": return Level.OUTAGE;
            default: return null;
        }
    }

    /** Plain text only: control characters become spaces, runs collapse, capped like the server. */
    static String clean(String raw) {
        if (raw == null) return null;
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length() && b.length() < MAX_TEXT; i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) { space = b.length() > 0; continue; }
            if (isInvisibleFormat(c)) continue;
            if (space) { b.append(' '); space = false; }
            b.append(c);
        }
        String t = b.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /** Bidi overrides/isolates and zero-width characters, which can disguise text (as on the server). */
    static boolean isInvisibleFormat(char c) {
        return (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E)
            || (c >= 0x2066 && c <= 0x2069) || c == 0xFEFF || c == 0x2028 || c == 0x2029;
    }

    /** The server already drops non-https links; check again rather than trust the response. */
    static String httpsOrNull(String raw) {
        if (raw == null) return null;
        try {
            URI u = new URI(raw.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null) return null;
            return u.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
