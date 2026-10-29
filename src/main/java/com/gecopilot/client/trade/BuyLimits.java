package com.gecopilot.client.trade;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * GE buy-limit windows this client has seen, per item. The GE limit is a 4h window that opens on
 * the first buy of an item and resets 4h later, whatever was bought in between. The server only
 * counts the trailing 4h of recorded trades (no window start), so the reset time comes from here.
 *
 * Fed from GE offer events (client thread); read by the overlay and panel. Persisted per RS account.
 */
public class BuyLimits {
    public static final long WINDOW_MS = 4L * 60 * 60 * 1000;
    /** A window that starts this far in the future came from a wrong clock (or a bad save): treat it as over. */
    static final long FUTURE_SKEW_MS = 60_000L;

    /** One item's open window. Public fields so it round-trips through Gson. */
    public static final class Window {
        public long startMs;
        public int bought;

        public Window() {}

        public Window(long startMs, int bought) {
            this.startMs = startMs;
            this.bought = bought;
        }
    }

    /** What the player can still buy. {@code limit} 0 = unknown; {@code resetAtMs} 0 = no open window. */
    public static final class Status {
        public final int limit;
        public final int used;
        public final long resetAtMs;

        Status(int limit, int used, long resetAtMs) {
            this.limit = limit;
            this.used = used;
            this.resetAtMs = resetAtMs;
        }

        public boolean known() { return limit > 0; }

        /** Units left this window; -1 when the limit is unknown. */
        public int remaining() { return known() ? Math.max(0, limit - used) : -1; }

        public boolean reached() { return known() && used >= limit; }
    }

    private final Map<Integer, Window> windows = new ConcurrentHashMap<>();
    private volatile Consumer<Map<Integer, Window>> saver = m -> {};

    /**
     * Swap in an RS account's saved windows. {@code keepCurrent} (first load) keeps windows seen
     * before the account was known when the saved state has none for that item.
     */
    public synchronized void rebind(Map<Integer, Window> saved, Consumer<Map<Integer, Window>> saver, boolean keepCurrent) {
        Map<Integer, Window> current = keepCurrent ? new HashMap<>(windows) : new HashMap<>();
        windows.clear();
        if (saved != null) {
            for (Map.Entry<Integer, Window> e : saved.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) windows.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<Integer, Window> e : current.entrySet()) windows.putIfAbsent(e.getKey(), e.getValue());
        this.saver = saver != null ? saver : m -> {};
    }

    /** {@code qty} units of {@code itemId} bought at {@code nowMs}. Opens a new window if none is running. */
    public synchronized void record(int itemId, int qty, long nowMs) {
        if (itemId <= 0 || qty <= 0) return;
        Window w = windows.get(itemId);
        if (w == null || expired(w, nowMs)) windows.put(itemId, new Window(nowMs, qty));
        else w.bought += qty;
        save(nowMs);
    }

    /** Write the open windows through the saver (record() does this on every buy). */
    public synchronized void save(long nowMs) {
        Map<Integer, Window> out = new HashMap<>();
        for (Map.Entry<Integer, Window> e : windows.entrySet()) {
            Window w = e.getValue();
            if (!expired(w, nowMs)) out.put(e.getKey(), new Window(w.startMs, w.bought));
        }
        windows.keySet().retainAll(out.keySet()); // expired windows carry nothing; keep the saved map small
        saver.accept(out);
    }

    /**
     * @param limit      the item's GE limit; 0 or less when unknown
     * @param serverUsed what the server counts against the limit (trailing 4h of trades + filled open
     *                   buys); 0 when it sent nothing for this item
     */
    public Status status(int itemId, int limit, int serverUsed, long nowMs) {
        return status(windows.get(itemId), limit, serverUsed, nowMs);
    }

    static Status status(Window w, int limit, int serverUsed, long nowMs) {
        int lim = Math.max(0, limit);
        int server = Math.max(0, serverUsed);
        if (w == null) return new Status(lim, server, 0); // never seen buying it here: server's count, no reset time
        // The window this client saw has reset. The server's trailing 4h still holds those buys for a
        // while after the GE has forgotten them, so it would under-report what is left.
        if (expired(w, nowMs)) return new Status(lim, 0, 0);
        return new Status(lim, Math.max(w.bought, server), w.startMs + WINDOW_MS);
    }

    /** Past its 4h, or starting more than {@link #FUTURE_SKEW_MS} after now (clock skew / bad save). */
    static boolean expired(Window w, long nowMs) {
        return nowMs >= w.startMs + WINDOW_MS || w.startMs > nowMs + FUTURE_SKEW_MS;
    }

    /**
     * Units bought between two GE events on one slot. {@code before} is what the client tracked for
     * the slot before this event (null if nothing). A terminal replay of an offer this client never
     * tracked has no known buy time, so it counts nothing; the server's count still covers it.
     */
    public static int boughtDelta(String state, int itemId, int sold, OpenOfferDto before) {
        boolean live = "BUYING".equals(state);
        if (!live && !"BOUGHT".equals(state) && !"CANCELLED_BUY".equals(state)) return 0;
        if (before == null || before.itemId != itemId || !before.buy) return live ? Math.max(0, sold) : 0;
        return Math.max(0, sold - before.sold);
    }
}
