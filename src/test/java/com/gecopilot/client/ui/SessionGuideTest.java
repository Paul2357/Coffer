package com.gecopilot.client.ui;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SessionGuideTest {
    @Test public void stagesFollowGeThenOfferThenTrade() {
        assertEquals(SessionGuide.Stage.OPEN_GE, SessionGuide.stage(false, false, false));
        assertEquals(SessionGuide.Stage.PLACE_OFFER, SessionGuide.stage(true, false, false));
        // Offer already captured, even if this client has not reported offersKnown.
        assertEquals(SessionGuide.Stage.WAIT_FILL, SessionGuide.stage(false, true, false));
        assertEquals(SessionGuide.Stage.DONE, SessionGuide.stage(false, false, true));
        assertEquals(SessionGuide.Stage.DONE, SessionGuide.stage(true, true, true));
    }

    @Test public void advanceDoesNotRegress() {
        assertEquals(SessionGuide.Stage.PLACE_OFFER,
            SessionGuide.advance(SessionGuide.Stage.PLACE_OFFER, SessionGuide.Stage.OPEN_GE));
        assertEquals(SessionGuide.Stage.DONE,
            SessionGuide.advance(SessionGuide.Stage.DONE, SessionGuide.Stage.OPEN_GE));
        assertEquals(SessionGuide.Stage.WAIT_FILL,
            SessionGuide.advance(null, SessionGuide.Stage.WAIT_FILL));
    }

    @Test public void bannerNamesTheNextAction() {
        assertEquals("Next: open the Grand Exchange.", SessionGuide.banner(SessionGuide.Stage.OPEN_GE, null, 0));
        assertEquals("Next: place or view an offer in the GE.",
            SessionGuide.banner(SessionGuide.Stage.PLACE_OFFER, "", 0));
        assertEquals("Next: in the GE, buy Whip @ " + String.format(Locale.US, "%,d", 1200) + ".",
            SessionGuide.banner(SessionGuide.Stage.PLACE_OFFER, "Whip", 1200));
        assertEquals("Offer synced. Sell it when it fills — see Positions.",
            SessionGuide.banner(SessionGuide.Stage.WAIT_FILL, "Whip", 1200));
        assertNull(SessionGuide.banner(SessionGuide.Stage.DONE, "Whip", 1200));
    }

    @Test public void toastOnlyOnAdvance() {
        assertNull(SessionGuide.transitionToast(null, SessionGuide.Stage.OPEN_GE));
        assertNull(SessionGuide.transitionToast(SessionGuide.Stage.OPEN_GE, SessionGuide.Stage.OPEN_GE));
        assertEquals("GE is open. Place or view an offer.",
            SessionGuide.transitionToast(SessionGuide.Stage.OPEN_GE, SessionGuide.Stage.PLACE_OFFER));
        assertEquals("Offer synced. Check Positions.",
            SessionGuide.transitionToast(SessionGuide.Stage.PLACE_OFFER, SessionGuide.Stage.WAIT_FILL));
        assertEquals("First trade recorded.",
            SessionGuide.transitionToast(SessionGuide.Stage.WAIT_FILL, SessionGuide.Stage.DONE));
        assertNull(SessionGuide.transitionToast(SessionGuide.Stage.DONE, SessionGuide.Stage.OPEN_GE));
    }

    @Test public void emptyStatesNameTheStage() {
        assertEquals("No offers yet. Open the Grand Exchange.",
            SessionGuide.emptyPositions(SessionGuide.Stage.OPEN_GE));
        assertEquals("No offer yet. Buy the top Finder pick in the GE.",
            SessionGuide.emptyPositions(SessionGuide.Stage.PLACE_OFFER));
        assertEquals("No open offers.", SessionGuide.emptyPositions(SessionGuide.Stage.DONE));
        assertEquals("No flips right now.", SessionGuide.emptyFlips(null));
    }
}
