package com.gecopilot.client.ui;

import com.gecopilot.client.api.QuoteDto;
import net.runelite.api.Client;
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
import java.util.function.IntSupplier;

/**
 * Pops up on the GE offer-setup screen with the server's suggested buy/sell for
 * the item you're viewing. Reads the "current GE item" varp each frame and only
 * draws when it matches the last quote the plugin fetched for that item.
 */
public class GeOfferOverlay extends Overlay {
    private static final int CURRENT_GE_ITEM = 1151;
    /** Not-yet-submitted offer price on the GE setup screen (OSRS varbit 4398). */
    private static final int VARBIT_GE_OFFER_PRICE = 4398;
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
    private final PanelComponent panel = new PanelComponent();
    private volatile QuoteDto quote;

    public GeOfferOverlay(Client client, BooleanSupplier warningsEnabled, IntSupplier minMargin, DoubleSupplier minRoi) {
        this.client = client;
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
        if (warningsEnabled.getAsBoolean()) {
            int price = client.getVarbitValue(VARBIT_GE_OFFER_PRICE);
            int type = client.getVarbitValue(VARBIT_GE_OFFER_TYPE); // 0 buy, 1 sell
            if (type == 0 || type == 1) {
                boolean buy = type == 0;
                warning = OfferPriceWarning.evaluate(buy, price, q.buy, q.sell);
                thin = OfferPriceWarning.evaluateMargin(buy, q.buy, q.sell, minMargin.getAsInt(), minRoi.getAsDouble());
            }
        }
        return paint(g, panel, q, warning, thin);
    }

    /** Draws the offer quote, plus a non-blocking warning line when {@code warning} is set.
     *  Package-visible so a headless harness can render the same component the client draws. */
    static Dimension paint(Graphics2D g, PanelComponent panel, QuoteDto q, OfferPriceWarning.Warning warning) {
        return paint(g, panel, q, warning, null);
    }

    static Dimension paint(Graphics2D g, PanelComponent panel, QuoteDto q,
                           OfferPriceWarning.Warning warning, OfferPriceWarning.ThinMargin thin) {
        Color marginColor = q.margin > 0 ? GREEN : RED;
        boolean anyWarn = warning != null || thin != null;
        panel.getChildren().clear();
        panel.setPreferredSize(new Dimension(anyWarn ? (thin != null ? 268 : 248) : 150, 0));
        panel.getChildren().add(TitleComponent.builder().text("Coffer").color(BLUE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Buy").right(GP.format(q.placeBuy)).rightColor(BLUE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Sell").right(GP.format(q.placeSell)).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Margin").right((q.margin > 0 ? "+" : "") + GP.format(q.margin)).rightColor(marginColor).build());
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
}
