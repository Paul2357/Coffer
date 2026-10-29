package com.gecopilot.client.ui;

import com.gecopilot.client.GeCopilotCloudPlugin;
import com.gecopilot.client.api.QuoteDto;
import com.gecopilot.client.api.SparklineDto;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;

import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class OfferChartTest {
    private static final String STATUS = "Grand Exchange: Offer status\n";
    private static final String SETUP = "Grand Exchange: Set up offer\n";

    @Test public void tabsMapToWikiTimesteps() {
        assertEquals(Arrays.asList("1d", "1w", "1m", "1y"), labels());
        assertEquals("5m", OfferChartRange.DAY.timestep);
        assertEquals("1h", OfferChartRange.WEEK.timestep);
        assertEquals("6h", OfferChartRange.MONTH.timestep);
        assertEquals("24h", OfferChartRange.YEAR.timestep);
        // The wiki returns up to 365 points per timestep; each window must fit in that.
        assertTrue(365 * 5 * 60 >= OfferChartRange.DAY.windowSec);
        assertTrue(365 * 3600 >= OfferChartRange.WEEK.windowSec);
        assertTrue(365 * 6 * 3600 >= OfferChartRange.MONTH.windowSec);
        assertTrue(365 * 24 * 3600 >= OfferChartRange.YEAR.windowSec);
    }

    @Test public void visibleOnlyWhenEnabledOnAnOfferScreenWithNoPrompt() {
        assertTrue(OfferChartOverlay.visible(true, 4151, STATUS, 0, ""));
        assertTrue(OfferChartOverlay.visible(true, 4151, SETUP, 0, null));
        assertFalse("config toggle off", OfferChartOverlay.visible(false, 4151, STATUS, 0, ""));
        assertFalse("no item", OfferChartOverlay.visible(true, 0, SETUP, 0, ""));
        assertFalse("GE slot overview", OfferChartOverlay.visible(true, 4151, "Grand Exchange\n", 0, ""));
        assertFalse("GE closed", OfferChartOverlay.visible(true, 4151, null, 0, ""));
        assertFalse("price prompt", OfferChartOverlay.visible(true, 4151, SETUP, OfferChartOverlay.MESLAYER_INT_INPUT, ""));
        assertFalse("long prompt", OfferChartOverlay.visible(true, 4151, SETUP, OfferChartOverlay.MESLAYER_LONG_INPUT, ""));
        assertFalse("item search", OfferChartOverlay.visible(true, 4151, SETUP, 0, "What would you like to buy?"));
    }

    /**
     * RuneLite builds every installed plugin's injector even while it's disabled, so anything injected
     * into the plugin is constructed then. The chart must not be injectable (or self-registering);
     * the plugin creates it in startUp() and removes it in shutDown().
     */
    @Test public void overlayCannotAttachItselfWhileCofferIsDisabled() {
        List<AccessibleObject> members = new ArrayList<>();
        members.addAll(Arrays.asList(OfferChartOverlay.class.getDeclaredConstructors()));
        members.addAll(Arrays.asList(OfferChartOverlay.class.getDeclaredMethods()));
        members.addAll(Arrays.asList(OfferChartOverlay.class.getDeclaredFields()));
        for (AccessibleObject m : members) {
            assertFalse(m + " is @Inject", m.isAnnotationPresent(javax.inject.Inject.class));
            assertFalse(m + " is @Inject", m.isAnnotationPresent(com.google.inject.Inject.class));
        }
        for (Field f : GeCopilotCloudPlugin.class.getDeclaredFields()) {
            if (f.getType() != OfferChartOverlay.class) continue;
            assertFalse("plugin injects the chart", f.isAnnotationPresent(javax.inject.Inject.class)
                || f.isAnnotationPresent(com.google.inject.Inject.class));
        }
    }

    @Test public void tabHitTesting() {
        Rectangle[] tabs = {new Rectangle(0, 0, 10, 10), new Rectangle(12, 0, 10, 10)};
        assertEquals(0, OfferChartOverlay.tabAt(tabs, new Point(5, 5)));
        assertEquals(1, OfferChartOverlay.tabAt(tabs, new Point(15, 5)));
        assertEquals(-1, OfferChartOverlay.tabAt(tabs, new Point(11, 5)));
        assertEquals(-1, OfferChartOverlay.tabAt(new Rectangle[0], new Point(5, 5)));
    }

    @Test public void tabClicksOnlyWhileLoggedIn() {
        Rectangle[] tabs = {new Rectangle(0, 0, 10, 10), new Rectangle(12, 0, 10, 10)};
        Point onWeek = new Point(15, 5);
        assertEquals(1, OfferChartOverlay.clickedTab(GameState.LOGGED_IN, tabs, onWeek));
        assertEquals(-1, OfferChartOverlay.clickedTab(GameState.LOGIN_SCREEN, tabs, onWeek));
        assertEquals(-1, OfferChartOverlay.clickedTab(GameState.LOADING, tabs, onWeek));
        assertEquals(-1, OfferChartOverlay.clickedTab(GameState.HOPPING, tabs, onWeek));

        // Through the real mouse listener, with a Client that reports the game state.
        GameState[] state = {GameState.LOGIN_SCREEN};
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, m, args) -> "getGameState".equals(m.getName()) ? state[0] : null);
        OfferChartOverlay overlay = new OfferChartOverlay(client, null, () -> true);
        overlay.tabs = tabs;
        MouseEvent e = press(onWeek);
        overlay.mouseListener().mousePressed(e);
        assertFalse("not consumed off the login screen", e.isConsumed());
        assertEquals(OfferChartRange.DAY, overlay.range());

        state[0] = GameState.LOGGED_IN;
        e = press(onWeek);
        overlay.mouseListener().mousePressed(e);
        assertTrue(e.isConsumed());
        assertEquals(OfferChartRange.WEEK, overlay.range());
    }

    @Test public void axisTicksAreBoundedForExtremeRanges() {
        long[] normal = OfferChart.ticks(25_000, 36_000);
        assertTrue(normal.length >= 2 && normal.length <= 6);
        assertTrue(OfferChart.ticks(0, Long.MAX_VALUE).length <= OfferChart.MAX_TICKS);
        assertTrue(OfferChart.ticks(Long.MAX_VALUE - 10, Long.MAX_VALUE).length <= OfferChart.MAX_TICKS);
        assertTrue(OfferChart.ticks(1, 2).length <= OfferChart.MAX_TICKS);
        assertEquals(1, OfferChart.ticks(5, 5).length);
        assertEquals(1, OfferChart.ticks(9, 3).length);
    }

    private static MouseEvent press(Point p) {
        return new MouseEvent(new javax.swing.JPanel(), MouseEvent.MOUSE_PRESSED, 0, 0, p.x, p.y, 1, false, MouseEvent.BUTTON1);
    }

    @Test public void legendAndAgeFormatting() {
        assertEquals("39,958", OfferChart.gp(39_958));
        assertEquals("850", OfferChart.gp(850));
        assertEquals("2,147,483,647", OfferChart.gp(Integer.MAX_VALUE));
        assertEquals("just now", OfferChart.ago(30));
        assertEquals("8m ago", OfferChart.ago(8 * 60 + 5));
        assertEquals("3h ago", OfferChart.ago(3 * 3600));
        assertEquals("2d ago", OfferChart.ago(2 * 86_400));
        assertTrue(OfferChart.staleNote(12 * 60, null).endsWith("showing 12m old data"));
        assertTrue(OfferChart.staleNote(12 * 60, null).startsWith("Couldn't refresh"));
    }

    @Test public void axisDecimalsFollowTheTickStep() {
        // Narrow range: 100 gp steps on ~12K need one decimal, so no two ticks print the same.
        long[] t = OfferChart.ticks(12_000, 12_250);
        long step = t[1] - t[0];
        List<String> labels = new ArrayList<>();
        for (long v : t) labels.add(OfferChart.axisLabel(v, step, 12_250));
        assertEquals(labels.size(), new java.util.HashSet<>(labels).size());
        assertEquals("12.1K", OfferChart.axisLabel(12_100, 100, 12_250));
        assertEquals("35K", OfferChart.axisLabel(35_000, 5_000, 40_000));
        assertEquals("27.5K", OfferChart.axisLabel(27_500, 2_500, 40_000));
        assertEquals("12.15K", OfferChart.axisLabel(12_150, 50, 12_250));
        assertEquals("1.25M", OfferChart.axisLabel(1_250_000, 250_000, 2_000_000));
        assertEquals("9,500", OfferChart.axisLabel(9_500, 500, 9_900));
        assertEquals("2.0B", OfferChart.axisLabel(2_000_000_000L, 500_000_000L, 2_100_000_000L));
        assertEquals("2B", OfferChart.axisLabel(2_000_000_000L, 1_000_000_000L, 2_100_000_000L));
    }

    @Test public void labelsSortedAndSpacedAtLeastElevenPx() {
        int[] y = OfferChart.spread(new int[]{50, 52, 53}, OfferChart.LABEL_GAP, 10, 140);
        assertArrayEquals(new int[]{50, 61, 72}, y);
        int[] bottom = OfferChart.spread(new int[]{130, 135, 138}, OfferChart.LABEL_GAP, 10, 140);
        assertEquals(140, bottom[2]);
        assertEquals(129, bottom[1]);
        assertEquals(118, bottom[0]);

        List<OfferChart.Marker> ms = new ArrayList<>();
        ms.add(marker(80, "Your offer"));
        ms.add(marker(60, "Coffer sell"));
        ms.add(marker(62, "Coffer buy"));
        OfferChart.layoutLabels(ms, 30, 140);
        assertEquals("Coffer sell", ms.get(0).name);
        assertEquals("Coffer buy", ms.get(1).name);
        for (int i = 1; i < ms.size(); i++) assertTrue(ms.get(i).labelY - ms.get(i - 1).labelY >= OfferChart.LABEL_GAP);
        assertEquals("Coffer buy 33,900", ms.get(1).label());
        ms.get(1).offChart = true;
        assertEquals("Coffer buy 33,900 (off chart)", ms.get(1).label());
    }

    @Test public void chartShrinksBelowAnOverlappingGeWindowOrHides() {
        Rectangle chat = new Rectangle(0, 360, 519, 142);
        assertSame(chat, OfferChartOverlay.fitBelow(chat, new Rectangle(10, 10, 480, 300), 70));
        assertEquals(new Rectangle(0, 400, 519, 102), OfferChartOverlay.fitBelow(chat, new Rectangle(90, 70, 480, 330), 70));
        assertNull("too little left under the GE", OfferChartOverlay.fitBelow(chat, new Rectangle(90, 70, 480, 400), 70));
        assertNull(OfferChartOverlay.fitBelow(null, null, 70));
        assertNull(OfferChartOverlay.fitBelow(new Rectangle(0, 0, 519, 40), null, 70));
    }

    private static OfferChart.Marker marker(int lineY, String name) {
        OfferChart.Marker m = new OfferChart.Marker(33_900, name, OfferChart.COFFER, null);
        m.lineY = lineY;
        return m;
    }

    @Test public void windowAndRangeIncludeNearbyLinesOnly() {
        List<SparklineDto.Point> pts = new ArrayList<>();
        pts.add(pt(1_000, 30_000, 29_000));
        pts.add(pt(90_000, 35_000, 34_000));
        assertEquals(1, OfferChart.window(pts, OfferChartRange.DAY, 90_000).size());
        assertEquals(2, OfferChart.window(pts, OfferChartRange.WEEK, 90_000).size());

        QuoteDto q = new QuoteDto();
        q.placeBuy = 29_500; q.placeSell = 36_000;
        long[] r = OfferChart.priceRange(pts, q);
        assertTrue("nearby Coffer lines widen the range", r[0] < 29_000 && r[1] > 36_000);
        q.placeBuy = 5; q.placeSell = 900_000;
        long[] far = OfferChart.priceRange(pts, q);
        assertTrue("far-off Coffer lines don't flatten the chart", far[0] > 20_000 && far[1] < 40_000);
    }

    @Test public void offerPriceNeverWidensTheRange() {
        // Paul's screenshot: an item trading ~1,250-1,615 with "Your offer 20" stretched the axis to 0.
        List<SparklineDto.Point> pts = new ArrayList<>();
        pts.add(pt(1_000, 1_615, 1_249));
        pts.add(pt(2_000, 1_600, 1_260));
        long[] r = OfferChart.priceRange(pts, null);
        assertTrue("range from the series only: " + r[0], r[0] > 1_100 && r[1] < 1_700);
        // priceRange has no offer parameter at all; the line is pinned instead (see the render test).
        for (java.lang.reflect.Method m : OfferChart.class.getDeclaredMethods())
            if (m.getName().equals("priceRange")) assertEquals(2, m.getParameterCount());
    }

    @Test public void offerPriceUnsetHidesTheLine() {
        assertEquals(0, OfferChartOverlay.offerPrice(null, 0));
        assertEquals(0, OfferChartOverlay.offerPrice(null, -1));
        assertEquals(1_500, OfferChartOverlay.offerPrice(null, 1_500));
        assertEquals(26_900, OfferChartOverlay.offerPrice(offer(4151, 26_900), 20));
        assertEquals(0, OfferChartOverlay.offerPrice(offer(4151, 0), 1_500));
        // GE_NEWOFFER_PRICE, not varbit 4398 (that id is PVPTUT_LOADOUT_A_INV_00 in RuneLite's gamevals).
        assertEquals(5753, OfferChartOverlay.VARP_GE_NEWOFFER_PRICE);
    }

    @Test public void quoteAndChartUseTheActiveSlotsItem() {
        net.runelite.api.GrandExchangeOffer[] offers = new net.runelite.api.GrandExchangeOffer[8];
        offers[1] = offer(31_111, 26_900);
        offers[2] = offer(0, 0);
        assertEquals("Offer status: slot 2 (1-based) holds item 31111", 31_111, OfferChartOverlay.activeItemId(2, offers, 0));
        assertEquals("selected slot wins over the search varp", 31_111, OfferChartOverlay.activeItemId(2, offers, 4151));
        assertEquals("Set up offer in an empty slot", 4151, OfferChartOverlay.activeItemId(3, offers, 4151));
        assertEquals("no slot selected", 4151, OfferChartOverlay.activeItemId(0, offers, 4151));
        assertEquals(4151, OfferChartOverlay.activeItemId(9, offers, 4151));
        assertEquals(4151, OfferChartOverlay.activeItemId(2, null, 4151));
    }

    @Test public void missingPricesAndLongGapsBreakTheLine() {
        List<SparklineDto.Point> pts = new ArrayList<>();
        pts.add(pt(0, 1_600, 1_250));
        pts.add(pt(300, 1_610, 0));      // wiki null avgLowPrice
        pts.add(pt(600, 1_605, 1_255));
        pts.add(pt(900, 1_600, 1_260));
        pts.add(pt(900 + 7 * 300, 1_590, 300)); // 7 steps later: over GAP_STEPS
        List<List<SparklineDto.Point>> low = OfferChart.segments(pts, false, 300 * OfferChart.GAP_STEPS);
        assertEquals(3, low.size());
        assertEquals(1, low.get(0).size());
        assertEquals(2, low.get(1).size());
        assertEquals(1, low.get(2).size());
        List<List<SparklineDto.Point>> high = OfferChart.segments(pts, true, 300 * OfferChart.GAP_STEPS);
        assertEquals("high side only breaks on the time gap", 2, high.size());
        assertEquals(4, high.get(0).size());
        assertEquals(300, OfferChartRange.DAY.stepSec);
        assertEquals(86_400, OfferChartRange.YEAR.stepSec);
    }

    @Test public void tooltipMirrorsTheDashboardFields() {
        SparklineDto.Point p = pt(1792595100L, 1_615, 1_249);
        p.highVol = 12; p.lowVol = 30;
        List<String> lines = OfferChart.tooltipLines(0, p, java.time.ZoneId.of("America/New_York"));
        assertEquals("Oct 21 11:05", lines.get(0));
        assertEquals("Buy 1,615", lines.get(1));
        assertEquals("Sell 1,249", lines.get(2));
        assertEquals("Margin 334 after tax", lines.get(3)); // 1,615 - 1,249 - 32 tax
        assertEquals("Vol 12 buy / 30 sell", lines.get(4));
        SparklineDto.Point half = pt(1792595100L, 0, 1_249);
        List<String> h = OfferChart.tooltipLines(0, half, java.time.ZoneId.of("UTC"));
        assertEquals("Buy -", h.get(1));
        assertEquals("no margin without both sides", 3, h.size());
    }

    @Test public void tooltipSaysNoGeTaxForExemptItemsAndTheFeeForBonds() {
        SparklineDto.Point p = pt(1792595100L, 10_050_000, 10_000_000);
        java.time.ZoneId z = java.time.ZoneId.of("UTC");
        assertEquals("Margin 50,000 (no GE tax)", OfferChart.tooltipLines(3853, p, z).get(3));
        assertEquals("Margin -950,000 after 10% bond fee", OfferChart.tooltipLines(13190, p, z).get(3));
        assertEquals("Margin -151,000 after tax", OfferChart.tooltipLines(30810, p, z).get(3));
    }

    @Test public void tooltipStaysInsideTheChart() {
        Rectangle bounds = new Rectangle(0, 0, 519, 142);
        Rectangle tl = OfferChart.placeTooltip(new Point(50, 40), 100, 50, bounds);
        assertEquals(new Rectangle(60, 50, 100, 50), tl);
        Rectangle br = OfferChart.placeTooltip(new Point(510, 135), 100, 50, bounds);
        assertTrue(bounds.contains(br));
        assertTrue("flips left of the cursor", br.x + br.width <= 500);
        Rectangle huge = OfferChart.placeTooltip(new Point(10, 10), 600, 200, bounds);
        assertEquals(1, huge.x);
        assertEquals(1, huge.y);
    }

    @Test public void tooltipStaysBelowTheLegendHeader() {
        Rectangle area = new Rectangle(0, 0, 519, 142);
        Rectangle header = new Rectangle(area.x, area.y, area.width, OfferChart.HEADER_H);
        Rectangle bounds = OfferChart.belowHeader(area);
        for (Point p : new Point[]{new Point(480, 40), new Point(480, 135), new Point(60, 35), new Point(300, 80)}) {
            Rectangle box = OfferChart.placeTooltip(p, 120, 61, bounds);
            assertFalse("tooltip at " + p + " covers the legend", box.intersects(header));
            assertTrue(area.contains(box));
        }
    }

    @Test public void hiddenFrameClearsStaleHover() {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, m, args) -> "getGameState".equals(m.getName()) ? GameState.LOGGED_IN
                : m.getReturnType() == int.class ? (Object) 0 : m.getReturnType() == long.class ? (Object) 0L : null);
        OfferChartOverlay overlay = new OfferChartOverlay(client, null, () -> false);
        overlay.mouseListener().mouseMoved(new MouseEvent(new javax.swing.JPanel(), MouseEvent.MOUSE_MOVED, 0, 0, 200, 90, 0, false));
        assertEquals(new Point(200, 90), overlay.hover());
        assertNull(overlay.render(null));
        assertNull("hidden overlay drops the hover point", overlay.hover());
    }

    @Test public void hoverTracksOnlyWhileLoggedInAndClearsOnExit() {
        GameState[] state = {GameState.LOGIN_SCREEN};
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, m, args) -> "getGameState".equals(m.getName()) ? state[0] : null);
        OfferChartOverlay overlay = new OfferChartOverlay(client, null, () -> true);
        MouseEvent move = new MouseEvent(new javax.swing.JPanel(), MouseEvent.MOUSE_MOVED, 0, 0, 200, 90, 0, false);
        overlay.mouseListener().mouseMoved(move);
        assertNull(overlay.hover());
        state[0] = GameState.LOGGED_IN;
        overlay.mouseListener().mouseMoved(move);
        assertEquals(new Point(200, 90), overlay.hover());
        assertFalse("hover never consumes", move.isConsumed());
        overlay.mouseListener().mouseExited(new MouseEvent(new javax.swing.JPanel(), MouseEvent.MOUSE_EXITED, 0, 0, 0, 0, 0, false));
        assertNull(overlay.hover());
    }

    private static net.runelite.api.GrandExchangeOffer offer(int itemId, long price) {
        return (net.runelite.api.GrandExchangeOffer) Proxy.newProxyInstance(
            net.runelite.api.GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{net.runelite.api.GrandExchangeOffer.class},
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "getItemId": return itemId;
                    case "getPrice": return price;
                    default: return m.getReturnType() == int.class ? 0 : m.getReturnType() == long.class ? 0L : null;
                }
            });
    }

    static SparklineDto.Point pt(long ts, long high, long low) {
        SparklineDto.Point p = new SparklineDto.Point();
        p.ts = ts; p.high = high; p.low = low; p.mid = (high + low) / 2.0;
        return p;
    }

    private static List<String> labels() {
        List<String> out = new ArrayList<>();
        for (OfferChartRange r : OfferChartRange.values()) out.add(r.label);
        return out;
    }
}
