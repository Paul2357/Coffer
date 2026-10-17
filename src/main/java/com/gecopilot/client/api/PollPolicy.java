package com.gecopilot.client.api;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Background poll cadence. The plugin's timer ticks every {@link #TICK_MS}. While the Coffer panel
 * is on screen every tick polls, as before. While it is hidden (sidebar collapsed, another plugin's
 * panel selected, or the client minimised) a tick only polls once {@link #HIDDEN_INTERVAL_MS} has
 * passed since the last sync, and that poll is a light one (see {@link PollCycle}).
 *
 * <p>The hidden interval stays under the server's 15-minute crowd-count window, so a hidden client
 * still counts as live, and over Neon's 5-minute autosuspend plus the pool's idle timeout, so a
 * hidden client alone no longer keeps the database awake.
 */
public final class PollPolicy {
    public static final long TICK_MS = 60_000L;
    public static final long HIDDEN_INTERVAL_MS = 10 * 60_000L;
    /** The timer is fixed-delay, so ticks drift by the poll's own duration. Absorb that. */
    static final long SLACK_MS = 2_000L;

    private final LongSupplier clockMs;
    private final BooleanSupplier minimised;
    private volatile boolean panelVisible = false;
    private volatile boolean synced = false;
    private volatile long lastSyncMs = 0;
    private volatile boolean windowMinimised = false;

    /**
     * @param clockMs   monotonic milliseconds (System.nanoTime based in the plugin)
     * @param minimised true while the client window is minimised; a throwing or null supplier counts as not
     */
    public PollPolicy(LongSupplier clockMs, BooleanSupplier minimised) {
        this.clockMs = clockMs;
        this.minimised = minimised != null ? minimised : () -> false;
    }

    public void setPanelVisible(boolean v) { panelVisible = v; }
    public boolean panelVisible() { return panelVisible; }

    /** The panel is selected and expanded, and the client is not minimised. */
    public boolean visible() {
        if (!panelVisible) return false;
        try { return !minimised.getAsBoolean(); } catch (RuntimeException e) { return true; }
    }

    /**
     * The client window's minimised state changed. Returns true when it was just restored with the
     * panel on screen; the caller then refreshes in full at once, the same as opening the panel.
     */
    public boolean onWindowState(boolean minimisedNow) {
        boolean restored = windowMinimised && !minimisedNow;
        windowMinimised = minimisedNow;
        return restored && panelVisible;
    }

    /** Whether this timer tick should poll. */
    public boolean dueOnTick() {
        if (visible() || !synced) return true;
        return clockMs.getAsLong() - lastSyncMs >= HIDDEN_INTERVAL_MS - SLACK_MS;
    }

    /** A sync to /api/flips is going out now (from any path: timer, GE event, panel open, user action). */
    public void noteSync() {
        lastSyncMs = clockMs.getAsLong();
        synced = true;
    }
}
