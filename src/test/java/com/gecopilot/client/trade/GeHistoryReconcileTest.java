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
}
