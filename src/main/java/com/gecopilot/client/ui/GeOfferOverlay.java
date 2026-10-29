package com.gecopilot.client.ui;

import com.gecopilot.client.api.QuoteDto;
import com.gecopilot.client.trade.BuyLimits;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.text.NumberFormat;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * Pops up on the GE offer-setup screen with the server's suggested buy/sell for
 * the item you're viewing. Reads the "current GE item" varp each frame and only
 * draws when it matches the last quote the plugin fetched for that item.
 */
public class GeOfferOverlay extends Overlay {
    private static final int CURRENT_GE_ITEM = 1151;
    /** Price typed on the GE setup screen (VarPlayer GE_NEWOFFER_PRICE; varbit 4398 is a PvP-tutorial var). */
    static final int VARP_GE_NEWOFFER_PRICE = VarPlayerID.GE_NEWOFFER_PRICE;
    /** 0 = buy, 1 = sell. Same id as {@code Varbits.GE_OFFER_CREATION_TYPE}. */
    private static final int VARBIT_GE_OFFER_TYPE = 4397;
    private static final NumberFormat GP = NumberFormat.getIntegerInstance();
    private static final Color GREEN = new Color(0x3f, 0xb9, 0x50);
    private static final Color RED = new Color(0xf8, 0x51, 0x49);
    private static final Color BLUE = new Color(0x4f, 0xc3, 0xf7);
    private static final Color WARN = new Color(0xff, 0xb0, 0x20);

    private final Client client;
    private final BooleanSupplier warningsEnabled;
    private final IntSupplier minMargin;
    private final DoubleSupplier minRoi;
    private final IntFunction<BuyLimits.Status> limits;
    private final PanelComponent panel = new PanelComponent();
    private volatile QuoteDto quote;

    public GeOfferOverlay(Client client, BooleanSupplier warningsEnabled, IntSupplier minMargin, DoubleSupplier minRoi) {
        this(client, warningsEnabled, minMargin, minRoi, null);
    }

    /** @param limits buy-limit status for an item id, read on the client thread; null for none */
    public GeOfferOverlay(Client client, BooleanSupplier warningsEnabled, IntSupplier minMargin, DoubleSupplier minRoi,
                          IntFunction<BuyLimits.Status> limits) {
        this.client = client;
        this.limits = limits;
        this.warningsEnabled = warningsEnabled != null ? warningsEnabled : () -> true;
        // Plugin defaults (GeCopilotCloudConfig), not the server's absent-field fallbacks of 1 and 0.
        this.minMargin = minMargin != null ? minMargin : () -> 10;
        this.minRoi = minRoi != null ? minRoi : () -> 0.3;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void setQuote(QuoteDto q) { this.quote = q; }

    public int currentItemId() { return client.getVarpValue(CURRENT_GE_ITEM); }

    @Override
    public Dimension render(Graphics2D g) {
        int itemId = client.getVarpValue(CURRENT_GE_ITEM);
        if (itemId <= 0) return null;            // not on the offer-setup screen
        QuoteDto q = quote;
        if (q == null || q.id != itemId) return null; // stale or not fetched yet

        OfferPriceWarning.Warning warning = null;
        OfferPriceWarning.ThinMargin thin = null;
        int type = client.getVarbitValue(VARBIT_GE_OFFER_TYPE); // 0 buy, 1 sell
        if (warningsEnabled.getAsBoolean()) {
            int price = offerPrice(client.getVarpLongValue(VARP_GE_NEWOFFER_PRICE));
            if (type == 0 || type == 1) {
                boolean buy = type == 0;
                warning = OfferPriceWarning.evaluate(buy, price, q.buy, q.sell);
                thin = OfferPriceWarning.evaluateMargin(itemId, buy, q.buy, q.sell, minMargin.getAsInt(), minRoi.getAsDouble());
            }
        }
        BuyLimits.Status limit = type == 0 && limits != null ? limits.apply(itemId) : null;
        return paint(g, panel, q, warning, thin, limit, System.currentTimeMillis());
    }

    /** The typed price as the int the warning math takes. The varp is a long (max-cash offers); clamp, don't wrap. */
    static int offerPrice(long varp) {
        if (varp <= 0) return 0;
        return (int) Math.min(varp, Integer.MAX_VALUE);
    }

    /** Draws the offer quote, plus a non-blocking warning line when {@code warning} is set.
     *  Package-visible so a headless harness can render the same component the client draws. */
    static Dimension paint(Graphics2D g, PanelComponent panel, QuoteDto q, OfferPriceWarning.Warning warning) {
        return paint(g, panel, q, warning, null);
    }

    static Dimension paint(Graphics2D g, PanelComponent panel, QuoteDto q,
                           OfferPriceWarning.Warning warning, OfferPriceWarning.ThinMargin thin) {
        return paint(g, panel, q, warning, thin, null, 0);
    }

    /**
     * As above, plus one buy-limit row (see {@link #limitRow}). The overlay never grows past the eight
     * rows it could already reach (both warnings), so in fixed mode it stays above the Quantity and
     * Price boxes: with both warnings showing, a reached limit replaces the thin-margin pair (no buy
     * can go in anyway) and a partial limit is left to the panel.
     */
    static Dimension paint(Graphics2D g, PanelComponent panel, QuoteDto q,
                           OfferPriceWarning.Warning warning, OfferPriceWarning.ThinMargin thin,
                           BuyLimits.Status limit, long nowMs) {
        Color marginColor = q.margin > 0 ? GREEN : RED;
        String[] limitRow = limitRow(limit, nowMs);
        if (limitRow != null && warning != null && thin != null) {
            if (limit.reached()) thin = null;
            else limitRow = null;
        }
        boolean anyWarn = warning != null || thin != null;
        int width = anyWarn ? (thin != null ? 268 : 248) : 150;
        if (limitRow != null) {
            java.awt.FontMetrics fm = g.getFontMetrics();
            width = Math.max(width, fm.stringWidth(limitRow[0]) + fm.stringWidth(limitRow[1]) + LIMIT_ROW_SLACK);
        }
        panel.getChildren().clear();
        panel.setPreferredSize(new Dimension(width, 0));
        panel.getChildren().add(TitleComponent.builder().text("Coffer").color(BLUE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Buy").right(GP.format(q.placeBuy)).rightColor(BLUE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Sell").right(GP.format(q.placeSell)).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Margin").right((q.margin > 0 ? "+" : "") + GP.format(q.margin)).rightColor(marginColor).build());
        if (limitRow != null) {
            panel.getChildren().add(LineComponent.builder()
                .left(limitRow[0]).right(limitRow[1]).rightColor(limitColor(limit)).build());
        }
        if (warning != null) {
            panel.getChildren().add(LineComponent.builder()
                .left(warning.headline()).leftColor(WARN).build());
            panel.getChildren().add(LineComponent.builder()
                .left(warning.detail()).leftColor(WARN).build());
        }
        if (thin != null) {
            panel.getChildren().add(LineComponent.builder()
                .left(thin.headline()).leftColor(WARN).build());
            panel.getChildren().add(LineComponent.builder()
                .left(thin.detail()).leftColor(WARN).build());
        }
        return panel.render(g);
    }

    /** Panel border plus a gap between label and value. */
    static final int LIMIT_ROW_SLACK = 24;

    /**
     * The one buy-limit row, reset appended so it never takes a second row: "Limit left" with
     * "1,200 of 5,000 · 2h 13m" or "Reached · 1h 02m"; with an unknown limit, "Bought" with
     * "40 (limit unknown)". Null when there is nothing to say.
     */
    static String[] limitRow(BuyLimits.Status s, long nowMs) {
        if (s == null) return null;
        String reset = s.resetAtMs > 0 ? " \u00b7 " + ShortDuration.format(Math.max(0, s.resetAtMs - nowMs)) : "";
        if (s.known()) {
            String v = s.reached() ? "Reached" : GP.format(s.remaining()) + " of " + GP.format(s.limit);
            return new String[]{"Limit left", v + reset};
        }
        if (s.used > 0) return new String[]{"Bought", GP.format(s.used) + " (limit unknown)" + reset};
        return null;
    }

    static final Color LIMIT_REACHED = new Color(0xff, 0x7b, 0x72);

    /** Green, amber at a quarter or less left, #FF7B72 when reached; white for an unknown limit. */
    static Color limitColor(BuyLimits.Status s) {
        if (s == null || !s.known()) return Color.WHITE;
        if (s.reached()) return LIMIT_REACHED;
        return lowLimit(s) ? WARN : GREEN;
    }

    /** A quarter or less of the limit left (and not yet reached). Shared with the panel. */
    static boolean lowLimit(BuyLimits.Status s) {
        return s != null && s.known() && !s.reached() && s.remaining() * 4 <= s.limit;
    }
}
