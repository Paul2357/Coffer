package com.gecopilot.client.trade;

import org.junit.Test;
import static org.junit.Assert.*;

public class GeHistoryReaderTest {
    @Test public void firstNumberParsesTotalFromPriceText() {
        assertEquals(594663, GeHistoryReader.firstNumber("<col=ffb83f>594,663 coins</col><br>= 198,221 each"));
        assertEquals(-1, GeHistoryReader.firstNumber("no digits here"));
    }

    @Test public void grossTotalPrefersPreTaxForSells() {
        // Buy: no tax breakdown -> plain total is gross.
        assertEquals(594663, GeHistoryReader.grossTotal("<col=ffb83f>594,663 coins</col><br>= 198,221 each"));
        // Sell: net shown first, gross in parens -> use gross (matches Coffer's recorded price).
        assertEquals(729632, GeHistoryReader.grossTotal(
            "<col=ffb83f>715,040 coins</col><br><col=9f9f9f>(729,632 - 14,592)</col><br>= 89,380 each"));
    }
}
