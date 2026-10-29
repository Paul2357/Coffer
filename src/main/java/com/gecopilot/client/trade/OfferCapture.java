package com.gecopilot.client.trade;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects completed GE fills to POST to the server. Same de-dup as the fixed local tracker:
 * the per-slot signature resets on a new live offer (so a repeated identical flip records again)
 * and blocks a re-emit of the same terminal offer (login replay).
 *
 * The signature is NOT reset on EMPTY. RuneLite replays EMPTY for every slot at login before the
 * real state, so clearing on EMPTY let an uncollected SOLD/BOUGHT record a second time on the next
 * login (seen in prod as exact duplicate sells with no matching offer event). Same rule as
 * {@link OfferJournal#onState}: only a new live offer (BUYING/SELLING) starts a new signature.
 *
 * Each fill also carries a {@code fillKey} (slot, the time this client first saw the offer live,
 * and the signature) so the server can treat a re-post of the same fill as a no-op.
 *
 * The signature deliberately excludes {@code spent}. A completed offer is often observed twice:
 * once with {@code spent == offerPrice * qty} (the price the player typed) and again with the
 * coins that actually moved, or a login replay of either. Those are the same fill. Putting
 * {@code spent} in the key recorded the offer price and then a second row at the real price.
 *
 * Thread-safety: GE events mutate {@link #open}/{@link #slotSig} on the client thread while the
 * poll thread reads via {@link #snapshot()} — both maps are concurrent so reads never throw CME.
 */
public class OfferCapture {
    private final Map<Integer, String> slotSig = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Map<Integer, String>> onSigChange;
    /** Per slot: when this client first saw the current offer live (ms, as a string for the shared
     *  persistence helpers). Kept across EMPTY and restarts so a replayed fill keeps its key. */
    private final Map<Integer, String> slotEpoch = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Map<Integer, String>> onEpochChange = m -> {};
    /** Slots whose current live offer already has an epoch this session (quantity ticks keep it). */
    private final java.util.Set<Integer> epochLive = ConcurrentHashMap.newKeySet();
    private final Map<Integer, OpenOfferDto> open = new ConcurrentHashMap<>();
    /** Slots whose live state we have actually seen this JVM session. A restored offer is not in
     *  here until a real GE event arrives, so the login EMPTY replay cannot resolve it early. */
    private final java.util.Set<Integer> liveThisSession = ConcurrentHashMap.newKeySet();
    private volatile java.util.function.Consumer<Map<Integer, OpenOfferDto>> onOpenChange = m -> {};

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

    /** Swap the persisted per-slot offer epochs (fill-key component) + their saver, e.g. on RS account load. */
    public void rebindEpochs(Map<Integer, String> initial, java.util.function.Consumer<Map<Integer, String>> saver) {
        slotEpoch.clear();
        epochLive.clear();
        if (initial != null) slotEpoch.putAll(initial);
        this.onEpochChange = saver != null ? saver : m -> {};
    }

    /** Called with a copy of the open-offer map whenever it changes, so a later session can resolve it. */
    public void setOpenSaver(java.util.function.Consumer<Map<Integer, OpenOfferDto>> saver) {
        this.onOpenChange = saver != null ? saver : m -> {};
    }

    /** Fill slots we are not already tracking. Does not mark them live this session. */
    public void restoreOpen(Map<Integer, OpenOfferDto> saved) {
        if (saved == null || saved.isEmpty()) return;
        for (Map.Entry<Integer, OpenOfferDto> e : saved.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            open.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /** Persist whatever is currently tracked. Call after the saver is attached. */
    public void flushOpen() { notifyOpen(); }

    private void notifyOpen() { onOpenChange.accept(new HashMap<>(open)); }

    private void removeSig(int slot) {
        if (slotSig.remove(slot) != null) onSigChange.accept(slotSig);
    }
    private void putSig(int slot, String sig) {
        slotSig.put(slot, sig);
        onSigChange.accept(slotSig);
    }

    /**
     * Track live offers per slot. openedAt stays put across quantity ticks and a client restart
     * (see {@link #restoreOpen}), and resets when the offer itself changes.
     * @return the new offer when this call opened one; null for a quantity tick or a non-open state
     */
    public OpenOfferDto noteOpen(int slot, String state, int itemId, int sold, int total, long price, long nowMs) {
        if (state.equals("BUYING") || state.equals("SELLING")) {
            liveThisSession.add(slot);
            boolean buying = state.equals("BUYING");
            OpenOfferDto prev = open.get(slot);
            boolean same = prev != null && prev.itemId == itemId && prev.buy == buying
                && prev.price == price && prev.total == total;
            long openedAt = same ? prev.openedAt : nowMs;
            OpenOfferDto dto = new OpenOfferDto(itemId, buying, sold, total, price, openedAt);
            // A fill moves the offer; a replay of the same quantity (login, restore) does not.
            if (same) dto.movedAt = sold != prev.sold ? nowMs : prev.lastMovedAt();
            if (same && prev.features != null) {
                dto.features = prev.features;
                dto.fv = prev.fv;
            }
            open.put(slot, dto);
            if (!same || sold != prev.sold) notifyOpen();
            return same ? null : dto;
        }
        // Login replays EMPTY for every slot before the real state. A restored offer has not been
        // seen live this session, so that placeholder must not drop it.
        if (state.equals("EMPTY") && !liveThisSession.contains(slot)) return null;
        liveThisSession.remove(slot);
        if (open.remove(slot) != null) notifyOpen();
        return null;
    }

    /**
     * Attach the placement-time feature vector to the offer that was opened at {@code openedAt}.
     * A quantity tick or a replaced offer (different openedAt) does not take the vector.
     */
    public void attachFeatures(int slot, long openedAt, double[] features, int fv) {
        if (features == null || features.length == 0) return;
        OpenOfferDto o = open.get(slot);
        if (o == null || o.openedAt != openedAt) return;
        o.features = java.util.Arrays.copyOf(features, features.length);
        o.fv = fv;
        notifyOpen();
    }

    /** The live offer tracked for a GE slot (0-based), or null. */
    public OpenOfferDto openAt(int slot) {
        return open.get(slot);
    }

    public java.util.List<OpenOfferDto> snapshot() {
        return new java.util.ArrayList<>(open.values());
    }

    /** Drop all tracked live offers — used on a real RS account switch (the new character's login
     *  events repopulate). NOT called on first load, which keeps this session's already-noted offers. */
    public void clearOpen() {
        liveThisSession.clear();
        if (open.isEmpty()) return;
        open.clear();
        notifyOpen();
    }

    /** When a tracked live offer resolves, emit its outcome (fill or not) and clear the slot.
     *  {@code fromEmpty} is the login/collect EMPTY state: it only resolves an offer this session
     *  has already seen live, so a restored offer waits for the real BOUGHT/CANCELLED replay. */
    public Outcome resolve(int slot, int sold, int total, long nowMs) {
        return resolve(slot, sold, total, nowMs, false);
    }

    public Outcome resolve(int slot, int sold, int total, long nowMs, boolean fromEmpty) {
        if (fromEmpty && !liveThisSession.contains(slot)) return null;
        liveThisSession.remove(slot);
        OpenOfferDto o = open.remove(slot);
        if (o == null) return null;
        notifyOpen();
        double[] fx = o.features == null ? null : java.util.Arrays.copyOf(o.features, o.features.length);
        return new Outcome(o.itemId, o.buy, o.price, sold, total, Math.max(0, nowMs - o.openedAt), fx, o.fv);
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
            if (epochLive.add(slot)) { // first live event for this offer: stamp a new epoch
                slotEpoch.put(slot, Long.toString(ts));
                onEpochChange.accept(new HashMap<>(slotEpoch));
            }
            return null;
        }
        // EMPTY is a placeholder (login replay, or the slot was collected). It must not reset the
        // signature: the login replay is EMPTY then the real SOLD/BOUGHT of an uncollected offer.
        if (state.equals("EMPTY")) { epochLive.remove(slot); return null; }
        if (!terminal(state) || qtySold <= 0) return null;
        epochLive.remove(slot);

        // No spent: offer-price and actual-coins observations of one terminal offer must collapse.
        String sig = itemId + ":" + (isBuy(state) ? "B" : "S") + ":" + totalQty + ":" + qtySold;
        if (sameTerminal(slotSig.get(slot), sig)) return null;
        putSig(slot, sig);

        long avg = Math.round((double) spent / qtySold);
        CompletedFill f = new CompletedFill(itemId, isBuy(state), qtySold, avg, ts);
        f.fillKey = fillKey(slot, sig);
        return f;
    }

    /**
     * A stored signature is this offer when it equals {@code sig}, or when it is the pre-fix form
     * that appended {@code :spent}. The prefix check keeps an upgraded client from re-recording an
     * offer whose old key is still on disk.
     */
    static boolean sameTerminal(String stored, String sig) {
        if (stored == null || sig == null) return false;
        return stored.equals(sig) || stored.startsWith(sig + ":");
    }

    /** slot:epoch:signature. Epoch 0 when the offer was never seen live by this client. */
    String fillKey(int slot, String sig) {
        String e = slotEpoch.get(slot);
        return slot + ":" + (e == null ? "0" : e) + ":" + sig;
    }
}
