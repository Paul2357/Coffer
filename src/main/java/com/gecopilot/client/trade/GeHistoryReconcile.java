package com.gecopilot.client.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure reconcile step for GE History import: given the trades read from the in-game History and the
 * trades already recorded on the server, return the ones Coffer MISSED (to import as source=history).
 *
 * Count-based dedup by signature (item:side:qty:unitPrice): if the History shows N of a signature and
 * the server already has M, import N-M. This handles repeated identical flips and never re-imports
 * something already recorded. Errs toward under-import (a partial-fill vs aggregate-row mismatch skips
 * rather than double-counts), per the design.
 */
public final class GeHistoryReconcile {
    private GeHistoryReconcile() {}

    /** A raw recorded trade from GET /api/trades/mine (server's source of truth). */
    public static final class Recorded {
        public int itemId; public boolean buy; public int qty; public int price;
    }

    private static String sig(int itemId, boolean buy, int qty, int price) {
        return itemId + ":" + (buy ? "B" : "S") + ":" + qty + ":" + price;
    }

    /** History rows that are not already covered by recorded trades, preserving History order. */
    public static List<GeHistoryRow> missing(List<GeHistoryRow> history, List<Recorded> recorded) {
        Map<String, Integer> remaining = new HashMap<>();
        if (recorded != null)
            for (Recorded r : recorded) remaining.merge(sig(r.itemId, r.buy, r.qty, r.price), 1, Integer::sum);

        List<GeHistoryRow> out = new ArrayList<>();
        if (history == null) return out;
        for (GeHistoryRow h : history) {
            String s = sig(h.itemId, h.buy, h.qty, h.unitPrice);
            Integer have = remaining.get(s);
            if (have != null && have > 0) remaining.put(s, have - 1); // already recorded — skip one
            else out.add(h);                                          // missed — import it
        }
        return out;
    }
}
