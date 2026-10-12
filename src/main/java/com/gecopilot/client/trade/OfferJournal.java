package com.gecopilot.client.trade;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Turns GE offer states into placed / cancelled / completed events, once each.
 * Signatures are persisted (via the savers) so a login replay does not log the offer again.
 */
public final class OfferJournal {
    private final Map<Integer, String> placed = new ConcurrentHashMap<>();
    private final Map<Integer, String> terminal = new ConcurrentHashMap<>();
    private Consumer<Map<Integer, String>> onPlaced = m -> {};
    private Consumer<Map<Integer, String>> onTerminal = m -> {};

    public void rebind(Map<Integer, String> placed0, Consumer<Map<Integer, String>> placedSaver,
                       Map<Integer, String> terminal0, Consumer<Map<Integer, String>> terminalSaver) {
        placed.clear();
        if (placed0 != null) placed.putAll(placed0);
        terminal.clear();
        if (terminal0 != null) terminal.putAll(terminal0);
        this.onPlaced = placedSaver != null ? placedSaver : m -> {};
        this.onTerminal = terminalSaver != null ? terminalSaver : m -> {};
    }

    public OfferEvent onState(int slot, String state, int itemId, int total, int sold, int price, long ts) {
        if (state == null) return null;
        if (state.equals("BUYING") || state.equals("SELLING")) {
            if (terminal.remove(slot) != null) onTerminal.accept(new HashMap<>(terminal));
            boolean buy = state.equals("BUYING");
            String sig = itemId + ":" + (buy ? "B" : "S") + ":" + price + ":" + total;
            if (sig.equals(placed.get(slot))) return null; // quantity tick or login replay
            placed.put(slot, sig);
            onPlaced.accept(new HashMap<>(placed));
            return new OfferEvent("placed", itemId, buy, price, total, sold, slot, ts);
        }
        if (state.equals("EMPTY")) return null;
        boolean cancel = state.startsWith("CANCELLED");
        boolean done = state.equals("BOUGHT") || state.equals("SOLD");
        if (!cancel && !done) return null;
        if (done && sold <= 0) return null;
        boolean buy = state.startsWith("BUY") || state.equals("BOUGHT") || state.equals("CANCELLED_BUY");
        String kind = cancel ? "cancelled" : "completed";
        String sig = kind + ":" + itemId + ":" + (buy ? "B" : "S") + ":" + price + ":" + total + ":" + sold;
        if (sig.equals(terminal.get(slot))) return null;
        terminal.put(slot, sig);
        onTerminal.accept(new HashMap<>(terminal));
        if (placed.remove(slot) != null) onPlaced.accept(new HashMap<>(placed));
        return new OfferEvent(kind, itemId, buy, price, total, sold, slot, ts);
    }
}
