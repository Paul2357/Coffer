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

/**
 * Pops up on the GE offer-setup screen with the server's suggested buy/sell for
 * the item you're viewing. Reads the "current GE item" varp each frame and only
 * draws when it matches the last quote the plugin fetched for that item.
 */
public class GeOfferOverlay extends Overlay {
    private static final int CURRENT_GE_ITEM = 1151;
    private static final NumberFormat GP = NumberFormat.getIntegerInstance();
    private static final Color GREEN = new Color(0x3f, 0xb9, 0x50);
    private static final Color RED = new Color(0xf8, 0x51, 0x49);
    private static final Color BLUE = new Color(0x4f, 0xc3, 0xf7);

    private final Client client;
    private final PanelComponent panel = new PanelComponent();
    private volatile QuoteDto quote;

    public GeOfferOverlay(Client client) {
        this.client = client;
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

        Color marginColor = q.margin > 0 ? GREEN : RED;
        panel.getChildren().clear();
        panel.setPreferredSize(new Dimension(150, 0));
        panel.getChildren().add(TitleComponent.builder().text("Coffer").color(BLUE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Buy").right(GP.format(q.placeBuy)).rightColor(BLUE).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Sell").right(GP.format(q.placeSell)).build());
        panel.getChildren().add(LineComponent.builder()
            .left("Margin").right((q.margin > 0 ? "+" : "") + GP.format(q.margin)).rightColor(marginColor).build());
        return panel.render(g);
    }
}
