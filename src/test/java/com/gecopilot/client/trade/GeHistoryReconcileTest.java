package com.gecopilot.client.trade;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class GeHistoryReconcileTest {
    private GeHistoryReconcile.Recorded rec(int id, boolean buy, int qty, int price) {
        GeHistoryReconcile.Recorded r = new GeHistoryReconcile.Recorded();
        r.itemId = id; r.buy = buy; r.qty = qty; r.price = price; return r;
    }

    @Test public void importsOnlyWhatIsMissing() {
        List<GeHistoryRow> history = List.of(
            new GeHistoryRow(1, true, 10, 100),   // already recorded
            new GeHistoryRow(1, false, 10, 130),  // MISSED
            new GeHistoryRow(2, true, 5, 200));    // MISSED
        List<GeHistoryReconcile.Recorded> recorded = List.of(rec(1, true, 10, 100));
        List<GeHistoryRow> out = GeHistoryReconcile.missing(history, recorded);
        assertEquals(2, out.size());
        assertEquals(1, out.get(0).itemId); assertFalse(out.get(0).buy);
        assertEquals(2, out.get(1).itemId);
    }

    @Test public void countBasedDedupOfRepeatedFlips() {
        // History has the same flip twice; server recorded it once -> import one.
        List<GeHistoryRow> history = List.of(
            new GeHistoryRow(1, true, 10, 100),
            new GeHistoryRow(1, true, 10, 100));
        List<GeHistoryReconcile.Recorded> recorded = List.of(rec(1, true, 10, 100));
        assertEquals(1, GeHistoryReconcile.missing(history, recorded).size());
    }

    @Test public void nothingMissingWhenAllRecorded() {
        List<GeHistoryRow> history = List.of(new GeHistoryRow(1, true, 10, 100));
        assertTrue(GeHistoryReconcile.missing(history, List.of(rec(1, true, 10, 100))).isEmpty());
    }

    @Test public void allMissingWhenNoneRecorded() {
        List<GeHistoryRow> history = List.of(new GeHistoryRow(1, true, 10, 100), new GeHistoryRow(2, false, 3, 50));
        assertEquals(2, GeHistoryReconcile.missing(history, List.of()).size());
    }

    /**
     * Paul, Dragon dagger(p++), 2026-10-15. Live capture stored the offer (26 @ 16,490, sell 70 @
     * 17,880). GE History has the coins that moved (26 @ 16,440, sell gross 17,532). The 44-lot
     * filled at the offer so it already matches. Reconcile must correct the two prices and import
     * nothing — importing the sell is what painted the phantom 140 "unknown cost" row.
     */
    @Test public void offerPriceIsCorrectedFromHistoryNotImportedAgain() {
        int dagger = 5698;
        List<GeHistoryReconcile.Recorded> recorded = List.of(
            rec(dagger, true, 44, 16448, 1),
            rec(dagger, true, 26, 16490, 2),
            rec(dagger, false, 70, 17880, 3));
        List<GeHistoryRow> history = List.of(
            new GeHistoryRow(dagger, false, 70, 17532),
            new GeHistoryRow(dagger, true, 26, 16440),
            new GeHistoryRow(dagger, true, 44, 16448));

        GeHistoryReconcile.Plan plan = GeHistoryReconcile.plan(history, recorded);
        assertTrue(plan.imports.isEmpty());
        assertEquals(2, plan.corrections.size());
        assertEquals(16490, correction(plan, true, 26).fromPrice);
        assertEquals(16440, correction(plan, true, 26).toPrice);
        assertEquals(17880, correction(plan, false, 70).fromPrice);
        assertEquals(17532, correction(plan, false, 70).toPrice);

        // Second open, after the prices were written: exact match, nothing left to do.
        List<GeHistoryReconcile.Recorded> fixed = List.of(
            rec(dagger, true, 44, 16448, 1),
            rec(dagger, true, 26, 16440, 2),
            rec(dagger, false, 70, 17532, 3));
        GeHistoryReconcile.Plan again = GeHistoryReconcile.plan(history, fixed);
        assertTrue(again.imports.isEmpty());
        assertTrue(again.corrections.isEmpty());
    }

    @Test public void twoDiscountedFillsCorrectIndependently() {
        List<GeHistoryReconcile.Recorded> recorded = List.of(
            rec(7, true, 10, 1000, 1),
            rec(7, true, 10, 900, 2));
        List<GeHistoryRow> history = List.of(
            new GeHistoryRow(7, true, 10, 800),
            new GeHistoryRow(7, true, 10, 880));
        GeHistoryReconcile.Plan plan = GeHistoryReconcile.plan(history, recorded);
        assertTrue(plan.imports.isEmpty());
        assertEquals(2, plan.corrections.size());
        // Globally closest first: 880→900 (diff 20), then 800→1000 (diff 200).
        assertEquals(900, correction(plan, true, 10, 880).fromPrice);
        assertEquals(1000, correction(plan, true, 10, 800).fromPrice);
    }

    @Test public void priceFarFromTheOnlyCandidateIsADifferentTrade() {
        // 3× apart is not an under-offer fill. Import it; leave the recorded row alone.
        List<GeHistoryReconcile.Recorded> recorded = List.of(rec(7, true, 10, 1000, 5));
        GeHistoryReconcile.Plan plan = GeHistoryReconcile.plan(
            List.of(new GeHistoryRow(7, true, 10, 3000)), recorded);
        assertEquals(1, plan.imports.size());
        assertEquals(3000, plan.imports.get(0).unitPrice);
        assertTrue(plan.corrections.isEmpty());
    }

    @Test public void olderSameQtyOutsideHistoryWindowIsNotRewritten() {
        List<GeHistoryReconcile.Recorded> recorded = new java.util.ArrayList<>();
        recorded.add(rec(5698, true, 26, 15000, 1)); // older than the last 40
        for (int i = 0; i < 40; i++) recorded.add(rec(1, true, 1, 100, 100L + i));
        GeHistoryReconcile.Plan plan = GeHistoryReconcile.plan(
            List.of(new GeHistoryRow(5698, true, 26, 16440)), recorded);
        assertEquals(1, plan.imports.size());
        assertEquals(16440, plan.imports.get(0).unitPrice);
        assertTrue(plan.corrections.isEmpty());
    }

    private GeHistoryReconcile.Recorded rec(int id, boolean buy, int qty, int price, long ts) {
        GeHistoryReconcile.Recorded r = rec(id, buy, qty, price);
        r.ts = ts;
        return r;
    }

    private GeHistoryReconcile.Correction correction(GeHistoryReconcile.Plan plan, boolean buy, int qty) {
        for (GeHistoryReconcile.Correction c : plan.corrections) if (c.buy == buy && c.qty == qty) return c;
        fail("no correction for " + (buy ? "buy " : "sell ") + qty);
        return null;
    }

    private GeHistoryReconcile.Correction correction(GeHistoryReconcile.Plan plan, boolean buy, int qty, int toPrice) {
        for (GeHistoryReconcile.Correction c : plan.corrections)
            if (c.buy == buy && c.qty == qty && c.toPrice == toPrice) return c;
        fail("no correction to " + toPrice);
        return null;
    }
}
