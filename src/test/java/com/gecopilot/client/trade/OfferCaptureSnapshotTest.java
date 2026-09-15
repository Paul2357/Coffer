package com.gecopilot.client.trade;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class OfferCaptureSnapshotTest {
    @Test public void tracksOpenAndClearsOnEmpty() {
        OfferCapture c = new OfferCapture();
        c.noteOpen(0, "BUYING", 5, 2, 10, 90, 1000);
        c.noteOpen(1, "SELLING", 6, 0, 3, 500, 1000);
        List<OpenOfferDto> snap = c.snapshot();
        assertEquals(2, snap.size());

        c.noteOpen(0, "EMPTY", 0, 0, 0, 0, 2000);
        assertEquals(1, c.snapshot().size());
    }

    @Test public void openedAtStableAcrossUpdates() {
        OfferCapture c = new OfferCapture();
        c.noteOpen(0, "BUYING", 5, 0, 10, 90, 1000);
        c.noteOpen(0, "BUYING", 5, 4, 10, 90, 5000);
        OpenOfferDto o = c.snapshot().get(0);
        assertEquals(1000, o.openedAt);
        assertEquals(4, o.sold);
    }

    @Test public void rebindKeepsOpenButClearOpenDropsIt() {
        OfferCapture c = new OfferCapture();
        c.noteOpen(0, "BUYING", 5, 2, 10, 90, 1000);
        c.rebind(new java.util.HashMap<>(), m -> {}); // account load must NOT lose live offers
        assertEquals(1, c.snapshot().size());
        c.clearOpen(); // real account switch drops them
        assertTrue(c.snapshot().isEmpty());
    }
}
