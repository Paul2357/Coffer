package com.gecopilot.client.trade;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class BuyLimitsTest {
    private static final long H = 60 * 60 * 1000L;
    private static final long M = 60 * 1000L;
    private static final long T0 = 1_792_500_000_000L;

    @Test public void firstBuyOpensTheWindowAndLaterBuysDoNotMoveIt() {
        BuyLimits b = new BuyLimits();
        b.record(4151, 3, T0);
        b.record(4151, 2, T0 + 90 * M);
        BuyLimits.Status s = b.status(4151, 70, 0, T0 + 2 * H);
        assertEquals(5, s.used);
        assertEquals(65, s.remaining());
        assertEquals("4h from the FIRST buy, not the latest", T0 + 4 * H, s.resetAtMs);
        assertFalse(s.reached());
    }

    @Test public void windowExpiresFourHoursAfterTheFirstBuy() {
        BuyLimits b = new BuyLimits();
        b.record(4151, 70, T0);
        assertTrue(b.status(4151, 70, 0, T0 + 4 * H - 1).reached());
        BuyLimits.Status after = b.status(4151, 70, 0, T0 + 4 * H);
        assertEquals("full limit back at the reset", 70, after.remaining());
        assertEquals("no countdown once reset", 0, after.resetAtMs);
        // The server's trailing 4h still holds the old buys; it must not hold the limit down after the reset.
        assertEquals(70, b.status(4151, 70, 60, T0 + 4 * H + 30 * M).remaining());
    }

    @Test public void buyAfterExpiryOpensAFreshWindow() {
        BuyLimits b = new BuyLimits();
        b.record(4151, 70, T0);
        b.record(4151, 10, T0 + 5 * H);
        BuyLimits.Status s = b.status(4151, 70, 0, T0 + 5 * H + M);
        assertEquals(10, s.used);
        assertEquals(T0 + 9 * H, s.resetAtMs);
    }

    @Test public void limitAlreadyReached() {
        BuyLimits b = new BuyLimits();
        b.record(2, 10_000, T0);
        b.record(2, 5_000, T0 + M); // over-count (e.g. a mobile buy) never goes negative
        BuyLimits.Status s = b.status(2, 11_000, 0, T0 + H);
        assertTrue(s.reached());
        assertEquals(0, s.remaining());
        assertEquals(T0 + 4 * H, s.resetAtMs);
    }

    @Test public void unknownLimitStillCountsAndTimes() {
        BuyLimits b = new BuyLimits();
        b.record(9999, 40, T0);
        BuyLimits.Status s = b.status(9999, 0, 0, T0 + H);
        assertFalse(s.known());
        assertFalse(s.reached());
        assertEquals(-1, s.remaining());
        assertEquals(40, s.used);
        assertEquals(T0 + 4 * H, s.resetAtMs);
        BuyLimits.Status none = b.status(1234, 0, 0, T0);
        assertFalse(none.known());
        assertEquals(0, none.used);
        assertEquals(0, none.resetAtMs);
    }

    @Test public void serverCountIsAFloorButGivesNoResetTime() {
        BuyLimits b = new BuyLimits();
        BuyLimits.Status serverOnly = b.status(4151, 70, 25, T0);
        assertEquals(45, serverOnly.remaining());
        assertEquals("server has no window start", 0, serverOnly.resetAtMs);
        b.record(4151, 10, T0);
        assertEquals("server saw more (another device)", 45, b.status(4151, 70, 25, T0 + M).remaining());
        assertEquals("this client saw more", 60, b.status(4151, 70, 4, T0 + M).remaining());
    }

    @Test public void boughtDeltaCountsOnlyNewBuyFills() {
        OpenOfferDto tracked = new OpenOfferDto(4151, true, 3, 10, 1_500_000, T0);
        assertEquals(4, BuyLimits.boughtDelta("BUYING", 4151, 7, tracked));
        assertEquals(7, BuyLimits.boughtDelta("BOUGHT", 4151, 10, tracked));
        assertEquals(2, BuyLimits.boughtDelta("CANCELLED_BUY", 4151, 5, tracked));
        assertEquals("replay of the same quantity", 0, BuyLimits.boughtDelta("BUYING", 4151, 3, tracked));
        assertEquals("sells never count", 0, BuyLimits.boughtDelta("SELLING", 4151, 9, tracked));
        assertEquals(0, BuyLimits.boughtDelta("SOLD", 4151, 10, tracked));
        assertEquals(0, BuyLimits.boughtDelta("EMPTY", 0, 0, tracked));
        assertEquals("first sight of a live buy counts what it already bought", 6, BuyLimits.boughtDelta("BUYING", 4151, 6, null));
        assertEquals("untracked terminal replay: unknown buy time", 0, BuyLimits.boughtDelta("BOUGHT", 4151, 10, null));
        OpenOfferDto other = new OpenOfferDto(561, true, 3, 10, 200, T0);
        assertEquals(5, BuyLimits.boughtDelta("BUYING", 4151, 5, other));
    }

    @Test public void savesOpenWindowsOnlyAndRebindsPerAccount() {
        Map<Integer, BuyLimits.Window> store = new HashMap<>();
        BuyLimits b = new BuyLimits();
        b.rebind(null, m -> { store.clear(); store.putAll(m); }, true);
        b.record(4151, 5, T0);
        b.record(561, 100, T0 + 5 * H); // 4151's window has expired by now
        assertEquals(1, store.size());
        assertEquals(100, store.get(561).bought);

        BuyLimits other = new BuyLimits();
        other.record(2, 50, T0); // seen before the account was known
        Map<Integer, BuyLimits.Window> saved = new HashMap<>();
        saved.put(561, new BuyLimits.Window(T0, 7));
        other.rebind(saved, m -> {}, true);
        assertEquals(7, other.status(561, 0, 0, T0 + M).used);
        assertEquals("first load keeps pre-account windows", 50, other.status(2, 0, 0, T0 + M).used);
        other.rebind(new HashMap<>(), m -> {}, false);
        assertEquals("account switch drops the previous character's windows", 0, other.status(2, 0, 0, T0 + M).used);
    }

    @Test public void windowStartingInTheFutureCountsAsExpired() {
        Map<Integer, BuyLimits.Window> saved = new HashMap<>();
        saved.put(4151, new BuyLimits.Window(T0 + 61_000, 70));   // clock skew / bad save
        saved.put(561, new BuyLimits.Window(T0 + 60_000, 100));   // within the 60s tolerance
        Map<Integer, BuyLimits.Window> store = new HashMap<>();
        BuyLimits b = new BuyLimits();
        b.rebind(saved, m -> { store.clear(); store.putAll(m); }, false);
        BuyLimits.Status s = b.status(4151, 70, 0, T0);
        assertEquals("future window: full limit back", 70, s.remaining());
        assertEquals("and no countdown", 0, s.resetAtMs);
        assertEquals(100, b.status(561, 25_000, 0, T0).used);
        b.record(4151, 5, T0);
        assertEquals("a buy now opens a fresh window", 5, b.status(4151, 70, 0, T0).used);
        assertEquals(T0 + 4 * H, b.status(4151, 70, 0, T0).resetAtMs);
        assertEquals(T0, store.get(4151).startMs);
        assertTrue(BuyLimits.expired(new BuyLimits.Window(T0 + 61_000, 1), T0));
        assertFalse(BuyLimits.expired(new BuyLimits.Window(T0 + 60_000, 1), T0));
        assertTrue(BuyLimits.expired(new BuyLimits.Window(T0 - 4 * H, 1), T0));
        assertFalse(BuyLimits.expired(new BuyLimits.Window(T0 - 4 * H + 1, 1), T0));
    }
}
