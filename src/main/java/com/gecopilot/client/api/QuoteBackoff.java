package com.gecopilot.client.api;

/**
 * Gate for the GE overlay's /api/quote fetch. The plugin checks it every game tick (0.6s); without
 * it a failing quote (server asleep, 204, timeout) was re-requested every tick, and ticks kept
 * queueing requests while one was still in flight. Now one request per item is in flight at a
 * time, and failures wait {@link #BASE_MS} doubling up to {@link #CAP_MS} before the next try.
 * State is per item: viewing a different item starts fresh.
 */
public final class QuoteBackoff {
    static final long BASE_MS = 1_000L;
    static final long CAP_MS = 30_000L;

    private int itemId = -1;
    private int failures = 0;
    private long nextAtMs = 0;
    private boolean inFlight = false;

    /** True if a quote request for {@code id} may go out now; the caller must then report the result. */
    public synchronized boolean tryStart(int id, long nowMs) {
        if (id != itemId) {
            itemId = id;
            failures = 0;
            nextAtMs = 0;
            inFlight = false;
        }
        if (inFlight || nowMs < nextAtMs) return false;
        inFlight = true;
        return true;
    }

    /** A quote for {@code id} arrived. Returns false if the player has moved on to another item. */
    public synchronized boolean succeeded(int id) {
        if (id != itemId) return false;
        inFlight = false;
        failures = 0;
        nextAtMs = 0;
        return true;
    }

    /** The quote for {@code id} failed; wait before the next try. */
    public synchronized void failed(int id, long nowMs) {
        if (id != itemId) return;
        inFlight = false;
        failures++;
        nextAtMs = nowMs + delayMs(failures);
    }

    /**
     * Run one fetch for {@code id} that {@link #tryStart} allowed. Returns the result, or null if it
     * failed or the player has moved on. The in-flight mark is cleared in a finally, so nothing the
     * fetch throws (an Error included) can leave the item stuck with no further retries.
     */
    public <T> T attempt(int id, java.util.function.Supplier<T> fetch, java.util.function.LongSupplier clockMs) {
        boolean ok = false;
        try {
            T r = fetch.get();
            ok = r != null;
            return ok && succeeded(id) ? r : null;
        } finally {
            if (!ok) failed(id, clockMs.getAsLong());
        }
    }

    /** Left the GE offer screen. */
    public synchronized void reset() {
        itemId = -1;
        failures = 0;
        nextAtMs = 0;
        inFlight = false;
    }

    static long delayMs(int failures) {
        if (failures <= 0) return 0;
        int shift = Math.min(failures - 1, 20);
        return Math.min(CAP_MS, BASE_MS << shift);
    }
}
