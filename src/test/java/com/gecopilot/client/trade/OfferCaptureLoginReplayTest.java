package com.gecopilot.client.trade;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** RuneLite replays EMPTY for every slot at login, then the real state of an uncollected offer. */
public class OfferCaptureLoginReplayTest {

    @Test public void loginEmptyThenSoldDoesNotRecordTwiceSameSession() {
        OfferCapture c = new OfferCapture();
        c.onOffer(2, "SELLING", 1517, 6800, 0, 0, 1_000);
        CompletedFill first = c.onOffer(2, "SOLD", 1517, 6800, 6800, 68_000, 2_000);
        assertNotNull(first);
        // Log out without collecting, log back in: EMPTY placeholder, then the same SOLD state.
        assertNull(c.onOffer(2, "EMPTY", 0, 0, 0, 0, 3_000));
        assertNull(c.onOffer(2, "SOLD", 1517, 6800, 6800, 68_000, 3_100));
    }

    @Test public void loginEmptyThenSoldDoesNotRecordTwiceAcrossRestart() {
        Map<Integer, String> sigs = new HashMap<>(), epochs = new HashMap<>();
        OfferCapture c1 = new OfferCapture(new HashMap<>(), m -> { sigs.clear(); sigs.putAll(m); });
        c1.rebindEpochs(new HashMap<>(), m -> { epochs.clear(); epochs.putAll(m); });
        c1.onOffer(0, "SELLING", 890, 11000, 0, 0, 1_000);
        CompletedFill first = c1.onOffer(0, "SOLD", 890, 11000, 11000, 297_000, 2_000);
        assertNotNull(first);

        // Client restarted; persisted state restored; login replays EMPTY then SOLD.
        OfferCapture c2 = new OfferCapture(sigs, m -> {});
        c2.rebindEpochs(epochs, m -> {});
        assertNull(c2.onOffer(0, "EMPTY", 0, 0, 0, 0, 50_000));
        assertNull(c2.onOffer(0, "SOLD", 890, 11000, 11000, 297_000, 50_100));
    }

    @Test public void collectedThenGenuineRepeatStillRecordsWithNewKey() {
        OfferCapture c = new OfferCapture();
        c.onOffer(1, "BUYING", 444, 100, 0, 0, 1_000);
        CompletedFill a = c.onOffer(1, "BOUGHT", 444, 100, 100, 15_100, 2_000);
        c.onOffer(1, "EMPTY", 0, 0, 0, 0, 3_000);                 // collected
        c.onOffer(1, "BUYING", 444, 100, 0, 0, 4_000);            // identical new offer
        c.onOffer(1, "BUYING", 444, 100, 40, 6_040, 4_500);       // quantity tick keeps the epoch
        CompletedFill b = c.onOffer(1, "BOUGHT", 444, 100, 100, 15_100, 5_000);
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a.fillKey, b.fillKey);
        assertEquals("1:4000:444:B:100:100:15100", b.fillKey);
    }

    @Test public void replayedFillKeepsTheSameKey() {
        OfferCapture c1 = new OfferCapture();
        c1.onOffer(3, "SELLING", 1917, 954, 0, 0, 7_000);
        CompletedFill a = c1.onOffer(3, "SOLD", 1917, 954, 954, 72_504, 8_000);
        assertEquals("3:7000:1917:S:954:954:72504", a.fillKey);
    }

    @Test public void offerNeverSeenLiveGetsEpochZero() {
        OfferCapture c = new OfferCapture();
        CompletedFill f = c.onOffer(5, "BOUGHT", 1, 10, 10, 1000, 9_000);
        assertEquals("5:0:1:B:10:10:1000", f.fillKey);
    }
}
