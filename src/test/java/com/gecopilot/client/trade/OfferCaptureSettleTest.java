package com.gecopilot.client.trade;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Last open offer cleared across a restart: login EMPTY must not drop it until the replay settles. */
public class OfferCaptureSettleTest {

    private static final int SAND = 1783;

    @Test public void lastOpenOfferClearedAfterLoginEmptySettles() {
        AtomicReference<java.util.Map<Integer, OpenOfferDto>> saved = new AtomicReference<>();
        OfferCapture first = new OfferCapture();
        first.setOpenSaver(saved::set);
        first.noteOpen(3, "BUYING", SAND, 26000, 27180, 8, 1_000);
        assertEquals(1, first.snapshot().size());

        OfferCapture next = new OfferCapture();
        next.restoreOpen(saved.get());
        // Login replays EMPTY before the real state. The offer stays until settle.
        next.noteOpen(3, "EMPTY", 0, 0, 0, 0, 50_000);
        assertEquals(1, next.snapshot().size());
        assertTrue(next.hasUnconfirmedEmpties());

        // No BUYING/CANCELLED followed: the slot is actually empty.
        assertTrue(next.dropUnconfirmedEmpties());
        assertTrue("last open offer cleared => no open offer shown", next.snapshot().isEmpty());
        assertFalse(next.hasUnconfirmedEmpties());
    }

    @Test public void stillOpenRestoredOfferSurvivesEmptyThenBuyingSettle() {
        AtomicReference<java.util.Map<Integer, OpenOfferDto>> saved = new AtomicReference<>();
        OfferCapture first = new OfferCapture();
        first.setOpenSaver(saved::set);
        first.noteOpen(0, "BUYING", SAND, 0, 100, 8, 1_000);

        OfferCapture next = new OfferCapture();
        next.restoreOpen(saved.get());
        next.noteOpen(0, "EMPTY", 0, 0, 0, 0, 50_000);
        next.noteOpen(0, "BUYING", SAND, 0, 100, 8, 50_100);
        assertFalse(next.hasUnconfirmedEmpties());
        assertFalse(next.dropUnconfirmedEmpties());
        assertEquals(1, next.snapshot().size());
        assertEquals(1_000, next.snapshot().get(0).openedAt);
    }

    @Test public void dropUnseenClearsRestoredEmptySlotButKeepsLiveOnes() {
        OfferCapture c = new OfferCapture();
        c.restoreOpen(java.util.Map.of(
            0, new OpenOfferDto(30810, false, 0, 286, 104000, 1_000),
            3, new OpenOfferDto(SAND, true, 26000, 27180, 8, 1_000)));
        c.noteOpen(0, "SELLING", 30810, 0, 286, 104000, 50_000); // still open
        c.noteOpen(3, "EMPTY", 0, 0, 0, 0, 50_000);

        assertFalse(c.dropUnseen(0));
        assertTrue(c.dropUnseen(3));
        assertEquals(1, c.snapshot().size());
        assertEquals(30810, c.snapshot().get(0).itemId);
        assertFalse(c.hasUnconfirmedEmpties());
    }
}
