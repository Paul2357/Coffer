package com.gecopilot.client.ui;

import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

import static org.junit.Assert.*;

public class GeOfferOverlayVarpTest {
    @Test public void overlayReadsTheOfferPriceVarpNotThePvpTutorialVarbit() {
        assertEquals(5753, GeOfferOverlay.VARP_GE_NEWOFFER_PRICE);
        assertEquals(VarPlayerID.GE_NEWOFFER_PRICE, GeOfferOverlay.VARP_GE_NEWOFFER_PRICE);
        assertEquals(26_900, GeOfferOverlay.offerPrice(26_900L));
        assertEquals(0, GeOfferOverlay.offerPrice(0L));
        assertEquals(0, GeOfferOverlay.offerPrice(-5L));
        assertEquals("max-cash long clamps instead of wrapping negative", Integer.MAX_VALUE, GeOfferOverlay.offerPrice(5_000_000_000L));
        // The warning math then sees the real typed price: a 2.8m buy over a 2.4m high warns.
        assertNotNull(OfferPriceWarning.evaluate(true, GeOfferOverlay.offerPrice(2_800_000L), 2_000_000, 2_400_000));
    }
}
