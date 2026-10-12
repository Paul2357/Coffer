package com.gecopilot.client.trade;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class OfferJournalTest {
    @Test public void placedOnceThenCancelKeepsFilledQty() {
        OfferJournal j = new OfferJournal();
        OfferEvent placed = j.onState(0, "BUYING", 4151, 10, 0, 100, 1_000);
        assertEquals("placed", placed.kind);
        assertTrue(placed.buy);
        assertEquals(10, placed.qty);
        assertNull(j.onState(0, "BUYING", 4151, 10, 4, 100, 2_000)); // same offer, partial
        OfferEvent cancel = j.onState(0, "CANCELLED_BUY", 4151, 10, 3, 100, 3_000);
        assertNotNull(cancel);
        assertEquals("cancelled", cancel.kind);
        assertEquals(3, cancel.filled);
        assertNull(j.onState(0, "CANCELLED_BUY", 4151, 10, 3, 100, 3_000)); // replay
        assertNull(j.onState(0, "EMPTY", 0, 0, 0, 0, 4_000));
    }

    @Test public void completedSellAndLoginReplayDoesNotDouble() {
        Map<Integer, String> placed = new HashMap<>();
        Map<Integer, String> terminal = new HashMap<>();
        OfferJournal first = new OfferJournal();
        first.rebind(placed, placed::putAll, terminal, m -> { terminal.clear(); terminal.putAll(m); });
        assertNotNull(first.onState(1, "SELLING", 4151, 8, 0, 130, 1_000));
        OfferEvent done = first.onState(1, "SOLD", 4151, 8, 8, 130, 5_000);
        assertEquals("completed", done.kind);
        assertFalse(done.buy);
        assertEquals(8, done.filled);

        OfferJournal next = new OfferJournal();
        next.rebind(new HashMap<>(placed), m -> {}, new HashMap<>(terminal), m -> {});
        assertNull(next.onState(1, "SOLD", 4151, 8, 8, 130, 9_000));
    }

    @Test public void newOfferAfterCancelIsPlacedAgain() {
        OfferJournal j = new OfferJournal();
        j.onState(2, "BUYING", 5, 10, 0, 50, 1);
        j.onState(2, "CANCELLED_BUY", 5, 10, 0, 50, 2);
        OfferEvent again = j.onState(2, "BUYING", 5, 10, 0, 40, 3);
        assertNotNull(again);
        assertEquals("placed", again.kind);
        assertEquals(40, again.price);
    }
}
