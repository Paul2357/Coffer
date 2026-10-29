package com.gecopilot.client.ui;

import com.gecopilot.client.trade.OfferCapture;
import com.gecopilot.client.trade.OpenOfferDto;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.function.BooleanSupplier;

/**
 * Time since each live GE offer last moved (placed or a fill), as a short label in the top-right
 * corner of its slot on the GE offers screen. Reads the open offers {@link OfferCapture} already
 * tracks (the same openedAt the server's Positions openMin is computed from), plus the time of the
 * last fill. Built and registered by the plugin in startUp() and removed in shutDown().
 */
public class SlotTimerOverlay extends Overlay {
    static final int[] SLOTS = {
        InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
        InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5,
        InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7,
    };
    /** Same 15 min the panel's Positions "Open" row turns orange at. */
    static final long STALE_MS = 15 * 60_000L;
    static final long OLD_MS = 60 * 60_000L;
    /** Chip ink, used for the fresh chip's fill and the text on the filled amber/red chips. */
    static final Color INK = new Color(0x0d, 0x0f, 0x14);
    static final Color FRESH_BG = new Color(0x0d, 0x0f, 0x14, 230);
    static final Color FRESH_TEXT = new Color(0xa8, 0xa8, 0xa8);
    static final Color STALE = new Color(0xff, 0xb0, 0x20);
    static final Color OLD = new Color(0xe5, 0x48, 0x4d);
    static final int PAD = 2;

    /** Fill and text colour of one chip. */
    static final class Chip {
        final Color fill, text;
        Chip(Color fill, Color text) { this.fill = fill; this.text = text; }
    }

    static final Chip FRESH_CHIP = new Chip(FRESH_BG, FRESH_TEXT);
    static final Chip STALE_CHIP = new Chip(STALE, INK);
    static final Chip OLD_CHIP = new Chip(OLD, INK);

    private final Client client;
    private final OfferCapture capture;
    private final BooleanSupplier enabled;

    public SlotTimerOverlay(Client client, OfferCapture capture, BooleanSupplier enabled) {
        this.client = client;
        this.capture = capture;
        this.enabled = enabled;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g) {
        // The slot widgets stay loaded under Set up offer / Offer status; no chips there.
        if (!visible(enabled.getAsBoolean(), OfferChartOverlay.geFrameText(client))) return null;
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) return null;
        long now = System.currentTimeMillis();
        Font font = FontManager.getRunescapeSmallFont();
        for (int i = 0; i < SLOTS.length && i < offers.length; i++) {
            Widget w = client.getWidget(SLOTS[i]);
            if (w == null || w.isHidden()) continue;
            Long moved = movedAt(offers[i], capture.openAt(i));
            if (moved == null) continue;
            long age = Math.max(0, now - moved);
            paintLabel(g, w.getBounds(), ShortDuration.format(age), chip(age), font);
        }
        return null;
    }

    /**
     * When the slot's live offer last moved, or null for no label: an empty or finished slot, or a
     * tracked offer that isn't the one in the slot (a stale restore).
     */
    static Long movedAt(GrandExchangeOffer o, OpenOfferDto tracked) {
        if (o == null || tracked == null) return null;
        GrandExchangeOfferState st = o.getState();
        boolean buying = st == GrandExchangeOfferState.BUYING;
        if (!buying && st != GrandExchangeOfferState.SELLING) return null;
        if (tracked.itemId != o.getItemId() || tracked.buy != buying) return null;
        return tracked.lastMovedAt();
    }

    /** Only on the GE offers overview: toggle on, and not the Set up offer / Offer status screens. */
    static boolean visible(boolean enabled, String geFrameText) {
        return enabled && !OfferChartOverlay.isOfferScreen(geFrameText);
    }

    /** Dark chip under 15 min, solid amber from 15 min, solid red from 1h. */
    static Chip chip(long ageMs) {
        return ageMs >= OLD_MS ? OLD_CHIP : ageMs >= STALE_MS ? STALE_CHIP : FRESH_CHIP;
    }

    /** Draws {@code text} on a chip in the slot's top-right corner. Package-visible for the render test. */
    static Rectangle paintLabel(Graphics2D g, Rectangle slot, String text, Chip c, Font font) {
        if (slot == null || slot.isEmpty()) return null;
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + PAD * 2;
        int h = fm.getAscent() + 2;
        // Tucked into the corner: the slot's centred "Buy"/"Sell" title sits just left of it.
        Rectangle chip = new Rectangle(slot.x + slot.width - w - PAD, slot.y + PAD + 1, w, h);
        g.setColor(c.fill);
        g.fillRect(chip.x, chip.y, chip.width, chip.height);
        int bx = chip.x + PAD, by = chip.y + fm.getAscent();
        g.setColor(c.text);
        g.drawString(text, bx, by);
        return chip;
    }
}
