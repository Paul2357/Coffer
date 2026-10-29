package com.gecopilot.client.trade;

import com.google.gson.Gson;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** The slot timer's "last moved" time rides on the offers OfferCapture already tracks. */
public class OfferCaptureMovedTest {
    /** The instance RuneLite injects as Gson (RuneLiteModule binds RuneLiteAPI.GSON). */
    private static final Gson GSON = RuneLiteAPI.GSON;

    @Test public void fillMovesTheOfferReplayDoesNot() {
        OfferCapture c = new OfferCapture();
        c.noteOpen(2, "BUYING", 4151, 0, 10, 1_500_000, 1_000);
        assertEquals(1_000, c.openAt(2).lastMovedAt());
        c.noteOpen(2, "BUYING", 4151, 0, 10, 1_500_000, 5_000);
        assertEquals("same quantity: not a move", 1_000, c.openAt(2).lastMovedAt());
        c.noteOpen(2, "BUYING", 4151, 4, 10, 1_500_000, 9_000);
        assertEquals("a fill moves it", 9_000, c.openAt(2).lastMovedAt());
        assertEquals("openedAt (Positions openMin) is unchanged", 1_000, c.openAt(2).openedAt);
        c.noteOpen(2, "BUYING", 4151, 4, 10, 1_500_000, 12_000);
        assertEquals(9_000, c.openAt(2).lastMovedAt());
        c.noteOpen(2, "BUYING", 4151, 0, 10, 1_400_000, 15_000);
        assertEquals("a new offer starts fresh", 15_000, c.openAt(2).lastMovedAt());
        assertNull(c.openAt(3));
    }

    @Test public void movedAtSurvivesARestart() {
        Map<Integer, OpenOfferDto> saved = new HashMap<>();
        OfferCapture c = new OfferCapture();
        c.setOpenSaver(m -> { saved.clear(); saved.putAll(m); });
        c.noteOpen(0, "SELLING", 561, 0, 1000, 210, 1_000);
        c.noteOpen(0, "SELLING", 561, 300, 1000, 210, 60_000);
        String json = OpenOfferPersistence.toJson(GSON, saved);
        Map<Integer, OpenOfferDto> back = OpenOfferPersistence.fromJson(GSON, json);
        assertEquals(60_000, back.get(0).lastMovedAt());
        assertEquals("the persisted sold follows fills too", 300, back.get(0).sold);

        OfferCapture restarted = new OfferCapture();
        restarted.restoreOpen(back);
        restarted.noteOpen(0, "SELLING", 561, 300, 1000, 210, 99_000); // login replay
        assertEquals(60_000, restarted.openAt(0).lastMovedAt());
    }

    @Test public void rowsFromOlderBuildsFallBackToOpenedAt() {
        Map<Integer, OpenOfferDto> back = OpenOfferPersistence.fromJson(GSON,
            "[{\"slot\":1,\"itemId\":2,\"buy\":true,\"sold\":0,\"total\":5,\"price\":9,\"openedAt\":4242}]");
        assertEquals(4242, back.get(1).lastMovedAt());
    }
}
