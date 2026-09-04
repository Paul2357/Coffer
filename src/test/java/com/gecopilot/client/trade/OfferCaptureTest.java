package com.gecopilot.client.trade;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OfferCaptureTest {
    @Test public void emitsFillOnTerminalBuy() {
        OfferCapture c = new OfferCapture();
        assertNull(c.onOffer(0, "BUYING", 1, 10, 0, 0, 1000));
        CompletedFill f = c.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 2000);
        assertNotNull(f);
        assertTrue(f.buy);
        assertEquals(10, f.qty);
        assertEquals(1000, f.price); // 10000 spent / 10
    }

    @Test public void repeatedIdenticalFlipEmitsTwice() {
        OfferCapture c = new OfferCapture();
        c.onOffer(0, "BUYING", 1, 10, 0, 0, 1000);
        assertNotNull(c.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 2000));
        c.onOffer(0, "EMPTY", 0, 0, 0, 0, 2500);
        c.onOffer(0, "BUYING", 1, 10, 0, 0, 3000);
        assertNotNull(c.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 4000)); // identical, must emit again
    }

    @Test public void loginReplayDoesNotDoubleEmit() {
        OfferCapture c = new OfferCapture();
        c.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 1000); // first time
        assertNull(c.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 1000)); // replay of same uncollected offer
    }

    @Test public void persistedSigSurvivesRestartSoReplayDoesNotDoubleCount() {
        java.util.Map<Integer, String> store = new java.util.HashMap<>();
        OfferCapture c1 = new OfferCapture(new java.util.HashMap<>(), m -> { store.clear(); store.putAll(m); });
        c1.onOffer(0, "BUYING", 1, 10, 0, 0, 1000);
        assertNotNull(c1.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 2000)); // recorded; sig persisted
        assertTrue(store.containsKey(0));

        // Client restarts (new instance), offer still uncollected -> RS re-fires BOUGHT.
        OfferCapture c2 = new OfferCapture(store, m -> {});
        assertNull(c2.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 5000)); // must NOT double-count
    }

    @Test public void newFlipAfterRestartClearsPersistedSig() {
        java.util.Map<Integer, String> store = new java.util.HashMap<>();
        OfferCapture c1 = new OfferCapture(new java.util.HashMap<>(), m -> { store.clear(); store.putAll(m); });
        c1.onOffer(0, "BUYING", 1, 10, 0, 0, 1000);
        c1.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 2000);
        // Restart, then a genuinely new identical flip in the same slot must still record.
        OfferCapture c2 = new OfferCapture(store, m -> { store.clear(); store.putAll(m); });
        c2.onOffer(0, "BUYING", 1, 10, 0, 0, 3000);   // live phase clears persisted sig
        assertNotNull(c2.onOffer(0, "BOUGHT", 1, 10, 10, 10000, 4000));
    }
}
