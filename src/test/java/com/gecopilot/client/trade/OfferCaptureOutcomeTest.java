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

    @Test public void buyPlacedLastSessionResolvesWithOriginalWait() {
        java.util.concurrent.atomic.AtomicReference<java.util.Map<Integer, OpenOfferDto>> saved =
            new java.util.concurrent.atomic.AtomicReference<>();
        OfferCapture first = new OfferCapture();
        first.setOpenSaver(saved::set);
        first.noteOpen(0, "BUYING", 4151, 0, 10, 100, 1_000);
        assertNotNull(saved.get());

        OfferCapture next = new OfferCapture();
        next.restoreOpen(saved.get());
        // Login replays EMPTY for every slot before the real state. That must not drop the buy.
        assertNull(next.resolve(0, 0, 0, 50_000, true));
        next.noteOpen(0, "EMPTY", 0, 0, 0, 0, 50_000);
        assertEquals(1, next.snapshot().size());

        Outcome o = next.resolve(0, 10, 10, 1_000 + 90_000, false);
        assertNotNull(o);
        assertTrue(o.buy);
        assertEquals(10, o.sold);
        assertEquals(90_000, o.waitedMs);
        assertNull(next.resolve(0, 10, 10, 200_000)); // already consumed
    }

    @Test public void replacedOfferResetsOpenedAt() {
        OfferCapture c = new OfferCapture();
        c.noteOpen(0, "BUYING", 4151, 0, 10, 100, 1_000);
        c.noteOpen(0, "BUYING", 4151, 0, 10, 250, 5_000); // same slot, new price
        Outcome o = c.resolve(0, 10, 10, 8_000);
        assertEquals(3_000, o.waitedMs);
    }

    @Test public void placementFeaturesSurviveTicksResolveAndRestart() {
        OfferCapture c = new OfferCapture();
        OpenOfferDto placed = c.noteOpen(0, "BUYING", 4151, 0, 10, 100, 1_000);
        assertNotNull(placed);
        assertNull(c.noteOpen(0, "BUYING", 4151, 2, 10, 100, 2_000)); // quantity tick
        c.attachFeatures(0, 1_000, new double[]{1.5, 1, 0.2, 0.3, 1.1, 0.4}, 2);
        c.attachFeatures(0, 9_999, new double[]{9}, 2); // different offer, ignored
        Outcome o = c.resolve(0, 10, 10, 1_000 + 90_000);
        assertNotNull(o);
        assertEquals(2, o.featureVersion);
        assertEquals(6, o.features.length);
        assertEquals(1.5, o.features[0], 0);
        assertEquals(90_000, o.waitedMs);
    }

    @Test public void openOffersRoundTripKeepsPlacementFeatures() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.util.Map<Integer, OpenOfferDto> open = new java.util.HashMap<>();
        OpenOfferDto dto = new OpenOfferDto(4151, true, 0, 10, 100, 1234L);
        dto.features = new double[]{1.5, 1, 0.2, 0.3, 1.1, 0.4};
        dto.fv = 2;
        open.put(2, dto);
        OpenOfferDto back = OpenOfferPersistence.fromJson(gson, OpenOfferPersistence.toJson(gson, open)).get(2);
        assertEquals(2, back.fv);
        assertEquals(1.5, back.features[0], 0);
        assertEquals(0.4, back.features[5], 0);
    }

    @Test public void openOffersRoundTripThroughJson() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.util.Map<Integer, OpenOfferDto> open = new java.util.HashMap<>();
        open.put(2, new OpenOfferDto(4151, true, 1, 10, 100, 1234L));
        String json = OpenOfferPersistence.toJson(gson, open);
        java.util.Map<Integer, OpenOfferDto> back = OpenOfferPersistence.fromJson(gson, json);
        assertEquals(1, back.size());
        OpenOfferDto o = back.get(2);
        assertEquals(4151, o.itemId);
        assertTrue(o.buy);
        assertEquals(100, o.price);
        assertEquals(1234L, o.openedAt);
        assertTrue(OpenOfferPersistence.fromJson(gson, "").isEmpty());
        assertTrue(OpenOfferPersistence.fromJson(gson, "not-json").isEmpty());
    }
}
