package com.gecopilot.client.ui;

import com.gecopilot.client.trade.BuyLimits;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import java.awt.Color;
import java.time.ZoneId;

import static org.junit.Assert.*;

public class BuyLimitDisplayTest {
    private static final long H = 60 * 60 * 1000L;
    private static final long M = 60 * 1000L;
    private static final long T0 = 1_792_500_000_000L; // 2026-10-20 12:40 UTC
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test public void overlayLimitRow() {
        BuyLimits b = new BuyLimits();
        b.record(4151, 3_800, T0);
        assertArrayEquals(new String[]{"Limit left", "1,200 of 5,000 \u00b7 2h 13m"},
            GeOfferOverlay.limitRow(b.status(4151, 5_000, 0, T0 + 107 * M), T0 + 107 * M));
        b.record(4151, 1_200, T0 + H);
        assertArrayEquals(new String[]{"Limit left", "Reached \u00b7 1h 02m"},
            GeOfferOverlay.limitRow(b.status(4151, 5_000, 0, T0 + 178 * M), T0 + 178 * M));

        assertArrayEquals("limit known, nothing bought: no countdown", new String[]{"Limit left", "25,000 of 25,000"},
            GeOfferOverlay.limitRow(b.status(561, 25_000, 0, T0), T0));
        assertArrayEquals("server-only count: no reset part", new String[]{"Limit left", "4,000 of 5,000"},
            GeOfferOverlay.limitRow(b.status(561, 5_000, 1_000, T0), T0));

        b.record(9999, 40, T0);
        assertArrayEquals(new String[]{"Bought", "40 (limit unknown) \u00b7 3h 00m"},
            GeOfferOverlay.limitRow(b.status(9999, 0, 0, T0 + H), T0 + H));
        assertNull(GeOfferOverlay.limitRow(b.status(1, 0, 0, T0), T0));
        assertNull(GeOfferOverlay.limitRow(null, T0));
        assertArrayEquals("expired: limit back", new String[]{"Limit left", "5,000 of 5,000"},
            GeOfferOverlay.limitRow(b.status(4151, 5_000, 0, T0 + 4 * H), T0 + 4 * H));
    }

    @Test public void lowLimitThresholdIsAQuarterInclusive() {
        BuyLimits b = new BuyLimits();
        b.record(1, 3_750, T0);  // 1,250 of 5,000 left: exactly a quarter
        b.record(2, 3_749, T0);  // 1,251 left
        b.record(3, 5_000, T0);  // reached
        assertTrue(GeOfferOverlay.lowLimit(b.status(1, 5_000, 0, T0)));
        assertFalse(GeOfferOverlay.lowLimit(b.status(2, 5_000, 0, T0)));
        assertFalse("reached is its own state", GeOfferOverlay.lowLimit(b.status(3, 5_000, 0, T0)));
        assertFalse("unknown limit", GeOfferOverlay.lowLimit(b.status(1, 0, 0, T0)));
        b.record(4, 3, T0);      // 1 of 4 left: 1*4 <= 4
        assertTrue(GeOfferOverlay.lowLimit(b.status(4, 4, 0, T0)));
    }

    @Test public void limitColours() {
        BuyLimits b = new BuyLimits();
        b.record(1, 3_750, T0);
        b.record(2, 100, T0);
        b.record(3, 5_000, T0);
        b.record(9, 40, T0);
        Color amber = new Color(0xff, 0xb0, 0x20), reached = new Color(0xff, 0x7b, 0x72);
        // GE overlay
        assertEquals(amber, GeOfferOverlay.limitColor(b.status(1, 5_000, 0, T0)));
        assertEquals(new Color(0x3f, 0xb9, 0x50), GeOfferOverlay.limitColor(b.status(2, 5_000, 0, T0)));
        assertEquals(reached, GeOfferOverlay.limitColor(b.status(3, 5_000, 0, T0)));
        assertEquals(Color.WHITE, GeOfferOverlay.limitColor(b.status(9, 0, 0, T0)));
        // Panel: #F2EFE6 normal, #FFB020 at a quarter or less, #FF7B72 reached
        assertEquals(new Color(0xf2, 0xef, 0xe6), CloudPanel.limitColor(b.status(2, 5_000, 0, T0)));
        assertEquals(amber, CloudPanel.limitColor(b.status(1, 5_000, 0, T0)));
        assertEquals(reached, CloudPanel.limitColor(b.status(3, 5_000, 0, T0)));
        assertEquals(ColorScheme.LIGHT_GRAY_COLOR, CloudPanel.limitColor(b.status(9, 0, 0, T0)));
    }

    @Test public void panelLimitText() {
        BuyLimits b = new BuyLimits();
        b.record(4151, 3_800, T0);
        BuyLimits.Status part = b.status(4151, 5_000, 0, T0 + 107 * M);
        assertEquals("1,200 of 5,000 left", CloudPanel.limitText(part));
        assertEquals("resets 16:40 (in 2h 13m)", CloudPanel.resetText(part, T0 + 107 * M, UTC));
        assertEquals("local clock", "resets 12:40 (in 2h 13m)",
            CloudPanel.resetText(part, T0 + 107 * M, ZoneId.of("America/New_York")));
        b.record(4151, 1_200, T0 + H);
        BuyLimits.Status full = b.status(4151, 5_000, 0, T0 + 178 * M);
        assertEquals("Limit reached", CloudPanel.limitText(full));
        assertEquals("resets 16:40 (in 1h 02m)", CloudPanel.resetText(full, T0 + 178 * M, UTC));

        BuyLimits.Status serverOnly = b.status(561, 5_000, 1_000, T0);
        assertEquals("4,000 of 5,000 left", CloudPanel.limitText(serverOnly));
        assertEquals("reset time unknown", CloudPanel.resetText(serverOnly, T0, UTC));

        b.record(9999, 40, T0);
        BuyLimits.Status unknown = b.status(9999, 0, 0, T0 + H);
        assertEquals("Bought 40 (limit unknown)", CloudPanel.limitText(unknown));
        assertEquals("resets 16:40 (in 3h 00m)", CloudPanel.resetText(unknown, T0 + H, UTC));

        assertNull("nothing used", CloudPanel.limitText(b.status(561, 5_000, 0, T0)));
        assertNull(CloudPanel.resetText(b.status(561, 5_000, 0, T0), T0, UTC));
        assertNull("unknown limit, nothing bought", CloudPanel.limitText(b.status(1, 0, 0, T0)));
        assertNull("window expired", CloudPanel.limitText(b.status(4151, 5_000, 0, T0 + 4 * H)));
    }

    @Test public void panelLineIsTwoLinesWithADimReset() {
        System.setProperty("java.awt.headless", "true");
        BuyLimits b = new BuyLimits();
        b.record(4151, 3_000, T0);
        javax.swing.JLabel two = CloudPanel.limitLine(b.status(4151, 5_000, 0, T0 + 107 * M), T0 + 107 * M, UTC);
        assertTrue(two.getText(), two.getText().contains("<font color='#f2efe6'>2,000 of 5,000 left</font><br>"));
        assertTrue(two.getText().contains("resets 16:40 (in 2h 13m)"));
        javax.swing.JLabel unknownReset = CloudPanel.limitLine(b.status(561, 5_000, 1_000, T0), T0, UTC);
        assertTrue("#8A8A8A", unknownReset.getText().contains("#8a8a8a'>reset time unknown"));
        assertEquals("starts at the item name's x", CloudPanel.TITLE_X, two.getInsets().left);
        assertEquals(javax.swing.SwingConstants.LEADING, two.getHorizontalAlignment());
        assertFalse(two.getText().contains("<center>"));
        assertNull(CloudPanel.limitLine(b.status(561, 5_000, 0, T0), T0, UTC));
    }
}
