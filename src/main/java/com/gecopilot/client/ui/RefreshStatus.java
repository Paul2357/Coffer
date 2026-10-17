package com.gecopilot.client.ui;

/**
 * The quiet "Updating… / Updated just now / Last updated Nm ago" cue in the panel header. Pure, so
 * the wording is testable without Swing.
 */
public final class RefreshStatus {
    public enum State { IDLE, UPDATING, OK, FAILED }

    /** Widest text the label can show; every state must fit on the header row. */
    static final String WIDEST = "Last updated 59m ago";

    private RefreshStatus() {}

    /**
     * @param lastOkMs wall-clock time of the last refresh that landed, or 0 if none has this session
     */
    public static String text(State state, long lastOkMs, long nowMs) {
        switch (state) {
            case UPDATING: return "Updating\u2026";
            case OK: return "Updated just now";
            case FAILED: return lastOkMs > 0 ? "Last updated " + ago(nowMs - lastOkMs) + " ago" : "Not updated yet";
            default: return " "; // keeps the row's height before the first refresh
        }
    }

    /** Whole minutes (at least 1), then hours, then days. */
    static String ago(long ms) {
        long min = Math.max(1, ms / 60_000L);
        if (min < 60) return min + "m";
        long h = min / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }
}
