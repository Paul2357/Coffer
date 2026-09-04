package com.gecopilot.client.trade;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfferCaptureOutcomeTest {
    @Test public void resolveEmitsOutcomeInclNonFill() {
        OfferCapture c = new OfferCapture();
        c.noteOpen(0, "BUYING", 4151, 0, 10, 100, 1000);
        Outcome o = c.resolve(0, 0, 10, 1000 + 90_000);
        assertNotNull(o);
        assertEquals(4151, o.itemId);
        assertTrue(o.buy);
        assertEquals(100, o.price);
        assertEquals(0, o.sold);
        assertEquals(10, o.total);
        assertEquals(90_000, o.waitedMs);
        assertNull(c.resolve(0, 0, 10, 2000)); // slot cleared
    }

    @Test public void resolveNullWhenNoTrackedOffer() {
        OfferCapture c = new OfferCapture();
        assertNull(c.resolve(3, 5, 5, 5000));
    }
}
