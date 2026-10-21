package com.gecopilot.client.ui;

import com.gecopilot.client.api.QuoteDto;
import com.gecopilot.client.api.TimeseriesCache;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;

/**
 * Price chart over the chatbox while the GE "Set up offer" or "Offer status" screen is open.
 * Built and registered by the plugin in startUp() and removed in shutDown(): it has no injected
 * members, so it can't attach itself to the client while Coffer is disabled.
 */
public class OfferChartOverlay extends Overlay {
    static final int CURRENT_GE_ITEM = 1151;
    /** Price typed on the GE setup screen (VarPlayer GE_NEWOFFER_PRICE; long since max-cash offers). */
    static final int VARP_GE_NEWOFFER_PRICE = VarPlayerID.GE_NEWOFFER_PRICE;
    static final int MESLAYER_INT_INPUT = 7;
    static final int MESLAYER_LONG_INPUT = 30;
    static final String ITEM_SEARCH_PROMPT = "What would you like to buy?";
    private static final Rectangle[] NO_TABS = new Rectangle[0];
    static final int MIN_HEIGHT = 70;

    private final Client client;
    private final TimeseriesCache cache;
    private final BooleanSupplier enabled;
    private final OfferChart.Model model = new OfferChart.Model();
    private final MouseListener mouse = new TabClicks();
    private volatile QuoteDto quote;
    private volatile OfferChartRange range = OfferChartRange.DAY;
    private volatile int hoverTab = -1;
    private volatile Point hover; // mouse position while logged in, for the crosshair tooltip
    volatile Rectangle[] tabs = NO_TABS; // last frame's tab hit boxes; package-visible for tests

    public OfferChartOverlay(Client client, TimeseriesCache cache, BooleanSupplier enabled) {
        this.client = client;
        this.cache = cache;
        this.enabled = enabled;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_LOW);
    }

    public void setQuote(QuoteDto q) { this.quote = q; }

    /** Tab clicks; the plugin registers this with the MouseManager alongside the overlay. */
    public MouseListener mouseListener() { return mouse; }

    /** Shown only with the config toggle on, an item on the GE offer/status screen, and no chatbox prompt over it. */
    static boolean visible(boolean enabled, int itemId, String geFrameText, int mesLayerMode, String chatPrompt) {
        if (!enabled || itemId <= 0) return false;
        if (!isOfferScreen(geFrameText)) return false;
        if (mesLayerMode == MESLAYER_INT_INPUT || mesLayerMode == MESLAYER_LONG_INPUT) return false;
        return chatPrompt == null || !chatPrompt.contains(ITEM_SEARCH_PROMPT);
    }

    static boolean isOfferScreen(String geFrameText) {
        return geFrameText != null && (geFrameText.contains("Set up offer") || geFrameText.contains("Offer status"));
    }

    @Override
    public Dimension render(Graphics2D g) {
        GrandExchangeOffer slotOffer = selectedSlotOffer(client);
        int itemId = activeItemId(client);
        Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
        if (!visible(enabled.getAsBoolean(), itemId, geFrameText(),
            client.getVarcIntValue(VarClientID.MESLAYERMODE), prompt == null ? null : prompt.getText())) {
            hide();
            return null;
        }
        Widget chatbox = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
        Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
        if (chatbox == null || chatbox.isHidden() || (chatArea != null && chatArea.isHidden())) {
            hide(); // chat collapsed in resizable mode: nothing to sit on
            return null;
        }
        Widget ge = client.getWidget(InterfaceID.GeOffers.FRAME);
        Rectangle area = fitBelow(chatbox.getBounds(), ge == null || ge.isHidden() ? null : ge.getBounds(), MIN_HEIGHT);
        if (area == null || area.width < 120) { hide(); return null; }

        OfferChartRange r = range;
        TimeseriesCache.Series s = cache.get(itemId, r.timestep);
        QuoteDto q = quote;
        model.range = r;
        model.points = s == null ? null : s.points;
        model.failed = s != null && s.failed;
        model.dataAgeSec = s == null ? 0 : Math.max(0, (cache.nowMs() - s.dataAtMs) / 1000L);
        model.hoverTab = hoverTab;
        model.hover = hover;
        model.nowSec = System.currentTimeMillis() / 1000L;
        model.offerPrice = offerPrice(slotOffer, client.getVarpLongValue(VARP_GE_NEWOFFER_PRICE));
        model.quote = q != null && q.id == itemId ? q : null;
        tabs = OfferChart.paint(g, area, model, FontManager.getRunescapeSmallFont());
        return new Dimension(area.width, area.height);
    }

    /** Not drawn this frame: drop tab hit boxes and hover so a stale tooltip can't reappear. */
    private void hide() {
        tabs = NO_TABS;
        hoverTab = -1;
        hover = null;
    }

    /**
     * The item the GE screen is about: the selected slot's active offer (Offer status), else the item
     * picked on Set up offer. The plugin quotes this same id, so Coffer's lines show on both screens.
     * Client thread only.
     */
    public static int activeItemId(Client client) {
        return activeItemId(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT), client.getGrandExchangeOffers(),
            client.getVarpValue(CURRENT_GE_ITEM));
    }

    static int activeItemId(int selectedSlot1Based, GrandExchangeOffer[] offers, int setupItem) {
        GrandExchangeOffer o = slotOffer(selectedSlot1Based, offers);
        return o != null ? o.getItemId() : setupItem;
    }

    /** The active offer's price, else the price typed on Set up offer; 0 (no line) when unset. */
    static long offerPrice(GrandExchangeOffer slotOffer, long setupPrice) {
        long v = slotOffer != null ? slotOffer.getPrice() : setupPrice;
        return v > 0 ? v : 0;
    }

    private static GrandExchangeOffer selectedSlotOffer(Client client) {
        return slotOffer(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT), client.getGrandExchangeOffers());
    }

    /** The existing offer in the selected GE slot (GE_SELECTEDSLOT is 1-based; 0 = none). */
    static GrandExchangeOffer slotOffer(int selectedSlot1Based, GrandExchangeOffer[] offers) {
        int slot = selectedSlot1Based - 1;
        if (offers == null || slot < 0 || slot >= offers.length) return null;
        GrandExchangeOffer o = offers[slot];
        return o != null && o.getItemId() > 0 ? o : null;
    }

    private String geFrameText() {
        Widget frame = client.getWidget(InterfaceID.GeOffers.FRAME);
        if (frame == null) return null;
        Widget[] kids = frame.getDynamicChildren();
        if (kids == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Widget w : kids) if (w != null && w.getText() != null) sb.append(w.getText()).append('\n');
        return sb.toString();
    }

    /**
     * The chatbox area, shortened to start below the GE window if that overlaps it (small resizable
     * layouts), or null when what's left is under {@code minHeight}.
     */
    static Rectangle fitBelow(Rectangle chat, Rectangle ge, int minHeight) {
        if (chat == null || chat.isEmpty()) return null;
        if (ge == null || ge.isEmpty() || !ge.intersects(chat)) return chat.height >= minHeight ? chat : null;
        int top = ge.y + ge.height;
        int height = chat.y + chat.height - top;
        return height >= minHeight ? new Rectangle(chat.x, top, chat.width, height) : null;
    }

    /** Index into {@link OfferChartRange#values()} of the tab under {@code p}, or -1. */
    static int tabAt(Rectangle[] tabs, Point p) {
        if (tabs == null || p == null) return -1;
        for (int i = 0; i < tabs.length; i++) if (tabs[i].contains(p)) return i;
        return -1;
    }

    /** Tab under {@code p}, but only while logged in (the chart can't be on screen otherwise). */
    static int clickedTab(GameState state, Rectangle[] tabs, Point p) {
        return state == GameState.LOGGED_IN ? tabAt(tabs, p) : -1;
    }

    OfferChartRange range() { return range; }

    Point hover() { return hover; }

    private final class TabClicks extends MouseAdapter {
        @Override
        public MouseEvent mousePressed(MouseEvent e) {
            int i = clickedTab(client.getGameState(), tabs, e.getPoint());
            if (i < 0) return e;
            range = OfferChartRange.values()[i];
            e.consume();
            return e;
        }

        @Override
        public MouseEvent mouseMoved(MouseEvent e) {
            boolean in = client.getGameState() == GameState.LOGGED_IN;
            hoverTab = in ? tabAt(tabs, e.getPoint()) : -1;
            hover = in ? e.getPoint() : null;
            return e;
        }

        @Override
        public MouseEvent mouseExited(MouseEvent e) {
            hoverTab = -1;
            hover = null;
            return e;
        }

        @Override
        public MouseEvent mouseReleased(MouseEvent e) {
            if (clickedTab(client.getGameState(), tabs, e.getPoint()) >= 0) e.consume();
            return e;
        }

        @Override
        public MouseEvent mouseClicked(MouseEvent e) {
            if (clickedTab(client.getGameState(), tabs, e.getPoint()) >= 0) e.consume();
            return e;
        }
    }
}
