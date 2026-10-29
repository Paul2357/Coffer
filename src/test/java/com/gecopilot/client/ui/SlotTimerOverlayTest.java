package com.gecopilot.client.ui;

import com.gecopilot.client.trade.OpenOfferDto;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class SlotTimerOverlayTest {
    private static final long M = 60_000L;
    private static final long H = 60 * M;

    @Test public void formatsShortLabels() {
        assertEquals("<1m", ShortDuration.format(0));
        assertEquals("<1m", ShortDuration.format(59_000));
        assertEquals("<1m", ShortDuration.format(59_999));
        assertEquals("1m", ShortDuration.format(60_000));
        assertEquals("3m", ShortDuration.format(3 * M + 20_000));
        assertEquals("59m", ShortDuration.format(59 * M));
        assertEquals("59m", ShortDuration.format(60 * M - 1));
        assertEquals("1h 00m", ShortDuration.format(60 * M));
        assertEquals("1h 05m", ShortDuration.format(65 * M));
        assertEquals("2h 10m", ShortDuration.format(130 * M));
        assertEquals("9h 59m", ShortDuration.format(9 * H + 59 * M));
        assertEquals("9h 59m", ShortDuration.format(10 * H - 1));
        assertEquals("10h", ShortDuration.format(10 * H));
        assertEquals("14h", ShortDuration.format(14 * H + 30 * M));
        assertEquals("23h", ShortDuration.format(23 * H));
        assertEquals("23h", ShortDuration.format(24 * H - 1));
        assertEquals("1d 0h", ShortDuration.format(24 * H));
        assertEquals("1d 3h", ShortDuration.format(27 * H));
        assertEquals("2d 3h", ShortDuration.format(51 * H + 59 * M));
        assertEquals("9d 23h", ShortDuration.format(240 * H - 1));
        assertEquals("10d", ShortDuration.format(240 * H));
        assertEquals("12d", ShortDuration.format(288 * H));
        assertEquals("12d", ShortDuration.format(299 * H));
        assertEquals("99d", ShortDuration.format(100 * 24 * H - 1));
        assertEquals("99d+", ShortDuration.format(100 * 24 * H));
        assertEquals("99d+", ShortDuration.format(400 * 24 * H));
    }

    @Test public void chipStates() {
        assertSame(SlotTimerOverlay.FRESH_CHIP, SlotTimerOverlay.chip(14 * M + 59_999));
        assertSame(SlotTimerOverlay.STALE_CHIP, SlotTimerOverlay.chip(15 * M));
        assertSame(SlotTimerOverlay.STALE_CHIP, SlotTimerOverlay.chip(60 * M - 1));
        assertSame(SlotTimerOverlay.OLD_CHIP, SlotTimerOverlay.chip(60 * M));
        assertSame(SlotTimerOverlay.OLD_CHIP, SlotTimerOverlay.chip(51 * H));
        // Juno's spec: ink #0D0F14 @ 230 with #A8A8A8 text; solid #FFB020 / #E5484D with ink text.
        assertEquals(new Color(0x0d, 0x0f, 0x14, 230), SlotTimerOverlay.FRESH_CHIP.fill);
        assertEquals(230, SlotTimerOverlay.FRESH_CHIP.fill.getAlpha());
        assertEquals(new Color(0xa8, 0xa8, 0xa8), SlotTimerOverlay.FRESH_CHIP.text);
        assertEquals(new Color(0xff, 0xb0, 0x20), SlotTimerOverlay.STALE_CHIP.fill);
        assertEquals(new Color(0xe5, 0x48, 0x4d), SlotTimerOverlay.OLD_CHIP.fill);
        assertEquals(new Color(0x0d, 0x0f, 0x14), SlotTimerOverlay.STALE_CHIP.text);
        assertEquals(new Color(0x0d, 0x0f, 0x14), SlotTimerOverlay.OLD_CHIP.text);
        assertEquals(255, SlotTimerOverlay.STALE_CHIP.fill.getAlpha());
    }

    @Test public void hiddenOnSetUpOfferAndOfferStatus() {
        assertTrue("offers overview", SlotTimerOverlay.visible(true, "Grand Exchange\nSelect an offer slot to set up or view an offer.\n"));
        assertTrue("frame text unknown", SlotTimerOverlay.visible(true, null));
        assertFalse(SlotTimerOverlay.visible(true, "Grand Exchange: Set up offer\n"));
        assertFalse(SlotTimerOverlay.visible(true, "Grand Exchange: Offer status\n"));
        assertFalse("toggle off", SlotTimerOverlay.visible(false, "Grand Exchange\n"));
    }

    @Test public void onlyLiveTrackedOffersGetALabel() {
        OpenOfferDto buy = new OpenOfferDto(4151, true, 2, 10, 1_500_000, 1_000);
        buy.movedAt = 7_000;
        assertEquals(Long.valueOf(7_000), SlotTimerOverlay.movedAt(offer(4151, GrandExchangeOfferState.BUYING), buy));
        assertNull("finished", SlotTimerOverlay.movedAt(offer(4151, GrandExchangeOfferState.BOUGHT), buy));
        assertNull("empty", SlotTimerOverlay.movedAt(offer(0, GrandExchangeOfferState.EMPTY), buy));
        assertNull("untracked", SlotTimerOverlay.movedAt(offer(4151, GrandExchangeOfferState.BUYING), null));
        assertNull("tracked a different item", SlotTimerOverlay.movedAt(offer(561, GrandExchangeOfferState.BUYING), buy));
        assertNull("tracked a buy, slot is selling", SlotTimerOverlay.movedAt(offer(4151, GrandExchangeOfferState.SELLING), buy));
        OpenOfferDto sell = new OpenOfferDto(561, false, 0, 100, 200, 3_000);
        assertEquals(Long.valueOf(3_000), SlotTimerOverlay.movedAt(offer(561, GrandExchangeOfferState.SELLING), sell));
    }

    @Test public void eightSlotWidgetsInOrder() {
        assertEquals(8, SlotTimerOverlay.SLOTS.length);
        for (int i = 0; i < 8; i++) assertEquals(InterfaceID.GeOffers.INDEX_0 + i, SlotTimerOverlay.SLOTS[i]);
    }

    static GrandExchangeOffer offer(int itemId, GrandExchangeOfferState state) {
        return (GrandExchangeOffer) Proxy.newProxyInstance(GrandExchangeOffer.class.getClassLoader(),
            new Class<?>[]{GrandExchangeOffer.class}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "getItemId": return itemId;
                    case "getState": return state;
                    default: return m.getReturnType() == int.class ? 0 : m.getReturnType() == long.class ? 0L : null;
                }
            });
    }
}
