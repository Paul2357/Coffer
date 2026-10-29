package com.gecopilot.client.ui;

/**
 * Compact durations shared by the slot chips, the GE overlay and the panel:
 * "<1m", "12m", "1h 05m" (under 10h), "14h" (under a day), "2d 3h" (under 10 days), "12d", capped at
 * "99d+". Long ages drop the smaller unit so a chip never grows wide enough to reach the slot's title.
 */
public final class ShortDuration {
    private ShortDuration() {}

    public static String format(long ms) {
        if (ms < 60_000L) return "<1m";
        long min = ms / 60_000L;
        if (min < 60) return min + "m";
        long h = min / 60;
        if (h < 10) {
            long m = min % 60;
            return h + "h " + (m < 10 ? "0" : "") + m + "m";
        }
        if (h < 24) return h + "h";
        long d = h / 24;
        if (d < 10) return d + "d " + (h % 24) + "h";
        return d > 99 ? "99d+" : d + "d";
    }
}
