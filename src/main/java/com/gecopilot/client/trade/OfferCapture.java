package com.gecopilot.client.trade;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects completed GE fills to POST to the server. Same de-dup as the fixed local tracker:
 * the per-slot signature resets on a new live offer (so a repeated identical flip records again)
 * and blocks a re-emit of the same terminal offer (login replay).
 *
 * Thread-safety: GE events mutate {@link #open}/{@link #slotSig} on the client thread while the
 * poll thread reads via {@link #snapshot()} — both maps are concurrent so reads never throw CME.
 */
public class OfferCapture {
    private final Map<Integer, String> slotSig = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Map<Integer, String>> onSigChange;
    private final Map<Integer, OpenOfferDto> open = new ConcurrentHashMap<>();

    /** In-memory only (tests / no persistence). */
    public OfferCapture() { this(new HashMap<>(), null); }

    /**
     * @param initialSig  last-recorded per-slot signatures restored from storage, so a
     *                    completed-but-uncollected offer that re-fires on login records once.
     * @param onSigChange called with the full sig map whenever it changes, to persist it.
     */
    public OfferCapture(Map<Integer, String> initialSig,
                        java.util.function.Consumer<Map<Integer, String>> onSigChange) {
        if (initialSig != null) slotSig.putAll(initialSig);
        this.onSigChange = onSigChange != null ? onSigChange : m -> {};
    }

    /**
     * Swap the persisted signatures + save callback (e.g. on RS account load) WITHOUT touching the
     * live {@link #open} offers map — those are transient game state repopulated only by GE events,
     * which fire once per login, so replacing the whole capture would lose them.
     */
    public void rebind(Map<Integer, String> initialSig,
                       java.util.function.Consumer<Map<Integer, String>> onSigChange) {
        slotSig.clear();
        if (initialSig != null) slotSig.putAll(initialSig);
        this.onSigChange = onSigChange != null ? onSigChange : m -> {};
    }

    private void removeSig(int slot) {
        if (slotSig.remove(slot) != null) onSigChange.accept(slotSig);
    }
    private void putSig(int slot, String sig) {
        slotSig.put(slot, sig);
        onSigChange.accept(slotSig);
    }

    /** Track live offers per slot for the positions view. openedAt is stable until the slot empties. */
    public void noteOpen(int slot, String state, int itemId, int sold, int total, int price, long nowMs) {
        if (state.equals("BUYING") || state.equals("SELLING")) {
            OpenOfferDto prev = open.get(slot);
            long openedAt = prev != null ? prev.openedAt : nowMs;
            open.put(slot, new OpenOfferDto(itemId, state.equals("BUYING"), sold, total, price, openedAt));
        } else {
            open.remove(slot); // EMPTY / terminal — no longer a live offer
        }
    }

    public java.util.List<OpenOfferDto> snapshot() {
        return new java.util.ArrayList<>(open.values());
    }

    /** Drop all tracked live offers — used on a real RS account switch (the new character's login
     *  events repopulate). NOT called on first load, which keeps this session's already-noted offers. */
    public void clearOpen() { open.clear(); }

    /** When a tracked live offer resolves, emit its outcome (fill or not) and clear the slot. */
    public Outcome resolve(int slot, int sold, int total, long nowMs) {
        OpenOfferDto o = open.remove(slot);
        if (o == null) return null;
        return new Outcome(o.itemId, o.buy, o.price, sold, total, nowMs - o.openedAt);
    }

    private static boolean terminal(String state) {
        return state.equals("BOUGHT") || state.equals("SOLD") || state.startsWith("CANCELLED");
    }

    private static boolean isBuy(String state) {
        return state.startsWith("BUY") || state.equals("BOUGHT") || state.equals("CANCELLED_BUY");
    }

    public CompletedFill onOffer(int slot, String state, int itemId, int totalQty, int qtySold, long spent, long ts) {
        if (state.equals("BUYING") || state.equals("SELLING")) {
            removeSig(slot);           // new live offer — reset so a repeat identical flip records
            return null;
        }
        if (state.equals("EMPTY")) { removeSig(slot); return null; }
        if (!terminal(state) || qtySold <= 0) return null;

        String sig = itemId + ":" + (isBuy(state) ? "B" : "S") + ":" + totalQty + ":" + qtySold + ":" + spent;
        if (sig.equals(slotSig.get(slot))) return null; // login replay of the same offer
        putSig(slot, sig);

        int avg = (int) Math.round((double) spent / qtySold);
        return new CompletedFill(itemId, isBuy(state), qtySold, avg, ts);
    }
}
