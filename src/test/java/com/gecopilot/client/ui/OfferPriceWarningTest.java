package com.gecopilot.client.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfferPriceWarningTest {
    @Test public void sellBelowWhatBuyersPayWarnsWithGap() {
        OfferPriceWarning.Warning w = OfferPriceWarning.evaluate(false, 900, 1000, 1200);
        assertNotNull(w);
        assertFalse(w.buy);
        assertEquals(900, w.offerPrice);
        assertEquals(1000, w.marketPrice);
        assertEquals(100, w.gapGp);
        assertEquals(10.0, w.gapPct, 0.001);
        assertEquals("Sell under buyers", w.headline());
        assertTrue(w.detail().contains("100"));
        assertTrue(w.detail().contains("10.0%"));
        assertTrue(w.detail().contains("under"));
    }

    @Test public void sellAtOrAboveTheLowIsFine() {
        assertNull(OfferPriceWarning.evaluate(false, 1000, 1000, 1200));
        assertNull(OfferPriceWarning.evaluate(false, 1100, 1000, 1200)); // under the high, still above buyers
    }

    @Test public void buyAboveMarketHighWarnsWithGap() {
        OfferPriceWarning.Warning w = OfferPriceWarning.evaluate(true, 1500, 1000, 1200);
        assertNotNull(w);
        assertTrue(w.buy);
        assertEquals(1200, w.marketPrice);
        assertEquals(300, w.gapGp);
        assertEquals(25.0, w.gapPct, 0.001);
        assertEquals("Buy over market", w.headline());
        assertTrue(w.detail().contains("25.0%"));
        assertTrue(w.detail().contains("over"));
    }

    @Test public void buyAtOrBelowTheHighIsFine() {
        assertNull(OfferPriceWarning.evaluate(true, 1200, 1000, 1200));
        assertNull(OfferPriceWarning.evaluate(true, 1100, 1000, 1200));
    }

    @Test public void missingOrZeroPricesDoNotWarn() {
        assertNull(OfferPriceWarning.evaluate(false, 0, 1000, 1200));
        assertNull(OfferPriceWarning.evaluate(false, 500, 0, 1200));
        assertNull(OfferPriceWarning.evaluate(true, 5000, 1000, 0));
        assertNull(OfferPriceWarning.evaluate(true, 0, 1000, 1200));
    }

    @Test public void adamantArrowsMarginIsTooThinOnABuy() {
        // High 28, low 26. Tax is 0 under 50 gp, so after-tax margin is 2 gp (~7.7% of 26).
        // That clears the 0.3% ROI default and fails the 10 gp margin default.
        OfferPriceWarning.ThinMargin w = OfferPriceWarning.evaluateMargin(true, 26, 28, 10, 0.3);
        assertNotNull(w);
        assertEquals(2L, w.marginGp);
        assertEquals(2.0 * 100.0 / 26.0, w.marginPct, 0.001);
        assertEquals("Margin too thin to flip", w.headline());
        assertTrue(w.detail().contains("2 gp"));
        assertTrue(w.detail().contains("7.7%"));
        assertTrue(w.detail().contains("after tax"));
    }

    @Test public void thinMarginDoesNotWarnOnASellSetup() {
        assertNull(OfferPriceWarning.evaluateMargin(false, 26, 28, 10, 0.3));
    }

    @Test public void marginAtTheUsersThresholdsIsFine() {
        // 197 gp on a 10_000 buy is 1.97%, above both defaults.
        assertNull(OfferPriceWarning.evaluateMargin(true, 10_000, 10_405, 10, 0.3));
        // sell 10_214 taxes 204, so margin is exactly 10 gp and exactly 0.1%. FlipEngine keeps `==`.
        assertNull(OfferPriceWarning.evaluateMargin(true, 10_000, 10_214, 10, 0.1));
        OfferPriceWarning.ThinMargin underRoi = OfferPriceWarning.evaluateMargin(true, 10_000, 10_214, 10, 0.11);
        assertNotNull(underRoi);
        assertEquals(10L, underRoi.marginGp);
    }

    @Test public void unprofitableSpreadWarns() {
        // 100/100 after 2% tax is -2 gp.
        OfferPriceWarning.ThinMargin w = OfferPriceWarning.evaluateMargin(true, 100, 100, 10, 0.3);
        assertNotNull(w);
        assertEquals(-2L, w.marginGp);
        assertTrue(w.detail().contains("-2"));
    }

    @Test public void roiBelowTheUsersMinimumWarnsEvenWhenGpClears() {
        // ~201 gp on a 100_000 buy is 0.20%, under a 0.3% floor, over a 10 gp floor.
        OfferPriceWarning.ThinMargin w = OfferPriceWarning.evaluateMargin(true, 100_000, 102_245, 10, 0.3);
        assertNotNull(w);
        assertTrue(w.marginGp >= 10);
        assertTrue(w.marginPct < 0.3);
    }

    @Test public void missingMarketDoesNotWarnOnMargin() {
        assertNull(OfferPriceWarning.evaluateMargin(true, 0, 28, 10, 0.3));
        assertNull(OfferPriceWarning.evaluateMargin(true, 26, 0, 10, 0.3));
    }

    @Test public void buyOverHighAndThinMarginAreIndependent() {
        // Bid 30 is over the high of 28, and the 2 gp spread is also too thin.
        assertNotNull(OfferPriceWarning.evaluate(true, 30, 26, 28));
        assertNotNull(OfferPriceWarning.evaluateMargin(true, 26, 28, 10, 0.3));
        // Bid 27 sits between 26 and 28, so only the thin-margin line fires.
        assertNull(OfferPriceWarning.evaluate(true, 27, 26, 28));
        assertNotNull(OfferPriceWarning.evaluateMargin(true, 26, 28, 10, 0.3));
    }
}
