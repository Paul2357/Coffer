package com.gecopilot.client.ui;

import com.gecopilot.client.api.QuoteDto;
import com.gecopilot.client.trade.BuyLimits;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.components.PanelComponent;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Headless renders of the buy-limit row (GE offer overlay, also over a fixed-mode Set up offer
 * screen), the panel limit lines, and the GE slot timers over a stand-in GE offers screen, using
 * the same painters the client calls. Overlays are drawn in the RuneScape font the client uses.
 */
public class LimitsTimersRenderTest {
    private static final long M = 60_000L;
    private static final long H = 60 * M;
    private static final long T0 = 1_792_500_000_000L;
    /** Fixed mode (765x503): where the overlay's TOP_LEFT anchor lands and where the Qty/Price boxes start. */
    static final int FIXED_X = 9, FIXED_Y = 24, FIXED_QTY_TOP = 171;
    static final Rectangle FIXED_QTY = new Rectangle(36, 171, 211, 61);
    static final Rectangle FIXED_PRICE = new Rectangle(274, 171, 211, 61);

    private static Path dir() throws Exception {
        Path dir = Paths.get(System.getProperty("overlay.out", "build/overlay-screenshots"));
        Files.createDirectories(dir);
        return dir;
    }

    @Test public void rendersOverlayLimitAndCountdown() throws Exception {
        BuyLimits b = new BuyLimits();
        b.record(4151, 3_800, T0);
        long now = T0 + 107 * M;
        BufferedImage img = renderOverlay(quote(), null, null, b.status(4151, 5_000, 0, now), now);
        ImageIO.write(img, "png", dir().resolve("ge-overlay-limit-countdown.png").toFile());
        assertTrue("amber 1,200 of 5,000", count(img, new Color(0xff, 0xb0, 0x20)) > 10);
    }

    @Test public void rendersOverlayLimitNormalAndUnknown() throws Exception {
        BuyLimits b = new BuyLimits();
        b.record(4151, 1_000, T0);
        b.record(9999, 40, T0);
        long now = T0 + 107 * M;
        BufferedImage normal = renderOverlay(quote(), null, null, b.status(4151, 5_000, 0, now), now);
        ImageIO.write(normal, "png", dir().resolve("ge-overlay-limit-normal.png").toFile());
        assertTrue("green 4,000 of 5,000", count(normal, new Color(0x3f, 0xb9, 0x50)) > 10); // GeOfferOverlay GREEN
        assertEquals(0, count(normal, new Color(0xff, 0xb0, 0x20)));
        assertEquals(0, count(normal, GeOfferOverlay.LIMIT_REACHED));
        BufferedImage unknown = renderOverlay(quote(), null, null, b.status(9999, 0, 0, now), now);
        ImageIO.write(unknown, "png", dir().resolve("ge-overlay-limit-unknown.png").toFile());
        assertEquals(0, count(unknown, GeOfferOverlay.LIMIT_REACHED));
    }

    @Test public void rendersOverlayLimitReachedWithWarning() throws Exception {
        BuyLimits b = new BuyLimits();
        b.record(4151, 5_000, T0);
        long now = T0 + 178 * M;
        QuoteDto q = quote();
        OfferPriceWarning.Warning w = OfferPriceWarning.evaluate(true, 2_800_000, q.buy, q.sell);
        BufferedImage img = renderOverlay(q, w, null, b.status(4151, 5_000, 0, now), now);
        ImageIO.write(img, "png", dir().resolve("ge-overlay-limit-reached.png").toFile());
        assertTrue("#FF7B72 Reached", count(img, GeOfferOverlay.LIMIT_REACHED) > 10);
    }

    @Test public void fixedModeOverlayStaysAboveQtyAndPrice() throws Exception {
        long now = T0 + 107 * M;
        BuyLimits b = new BuyLimits();
        b.record(4151, 3_800, T0);
        b.record(2, 5_000, T0);
        b.record(9999, 40, T0);
        QuoteDto q = quote();
        OfferPriceWarning.Warning w = OfferPriceWarning.evaluate(true, 2_800_000, q.buy, q.sell);
        OfferPriceWarning.ThinMargin thin = new OfferPriceWarning.ThinMargin(2, 0.1);
        BuyLimits.Status partial = b.status(4151, 5_000, 0, now);
        BuyLimits.Status reached = b.status(2, 5_000, 0, now);
        BuyLimits.Status unknown = b.status(9999, 0, 0, now);
        Object[][] cases = {
            {"partial", null, null, partial}, {"partial+warning", w, null, partial},
            {"partial+thin", null, thin, partial}, {"partial+warning+thin", w, thin, partial},
            {"reached+warning", w, null, reached}, {"reached+warning+thin", w, thin, reached},
            {"unknown+warning", w, null, unknown}, {"no-limit+warning+thin (pre-PR max)", w, thin, null},
        };
        for (Object[] c : cases) {
            Rectangle r = fixedBounds(q, (OfferPriceWarning.Warning) c[1], (OfferPriceWarning.ThinMargin) c[2], (BuyLimits.Status) c[3], now);
            assertTrue(c[0] + " bottom " + r.getMaxY() + " clears the Qty/Price boxes at y=" + FIXED_QTY_TOP,
                r.getMaxY() <= FIXED_QTY_TOP);
            assertFalse(c[0] + " Qty", r.intersects(FIXED_QTY));
            assertFalse(c[0] + " Price", r.intersects(FIXED_PRICE));
        }
        ImageIO.write(fixed(q, null, null, partial, now), "png", dir().resolve("ge-overlay-fixed-partial.png").toFile());
        ImageIO.write(fixed(q, w, thin, reached, now), "png", dir().resolve("ge-overlay-fixed-reached-warnings.png").toFile());
    }

    @Test public void rendersPanelLimitLines() throws Exception {
        System.setProperty("java.awt.headless", "true");
        BuyLimits b = new BuyLimits();
        b.record(4151, 3_000, T0);
        b.record(9999, 40, T0);
        b.record(2, 11_000, T0 + 2 * M); // record() prunes windows "from the future", so keep time moving forward
        b.record(536, 6_000, T0 + 2 * M);
        long now = T0 + 107 * M;
        ZoneId zone = ZoneId.of("America/New_York");
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(ColorScheme.DARK_GRAY_COLOR);
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        root.add(card("Abyssal whip", CloudPanel.limitLine(b.status(4151, 5_000, 0, now), now, zone)));
        root.add(card("Cannonball", CloudPanel.limitLine(b.status(2, 11_000, 0, now), now, zone)));
        root.add(card("Nature rune", CloudPanel.limitLine(b.status(561, 18_000, 3_000, now), now, zone)));
        root.add(card("Unknown-limit item", CloudPanel.limitLine(b.status(9999, 0, 0, now), now, zone)));
        root.add(card("Dragon bones", CloudPanel.limitLine(b.status(536, 7_500, 0, now), now, zone)));
        root.setSize(225, root.getPreferredSize().height);
        layout(root);
        for (Component c : root.getComponents()) {
            JLabel l = (JLabel) ((JPanel) c).getComponent(1);
            assertTrue("limit line fits the card: " + l.getText(),
                l.getPreferredSize().width <= ((JPanel) c).getWidth() - 20);
            JPanel title = (JPanel) ((JPanel) c).getComponent(0);
            JLabel name = (JLabel) title.getComponent(1);
            assertEquals("limit line starts at the item name: " + l.getText(),
                title.getX() + name.getX() + name.getInsets().left, l.getX() + l.getInsets().left);
        }
        assertTrue("#8A8A8A reset time unknown", count(scale(paint(root)), CloudPanel.RESET_UNKNOWN) > 10);
        ImageIO.write(scale(paint(root)), "png", dir().resolve("panel-limit-lines.png").toFile());
    }

    private static BufferedImage paint(JPanel root) {
        BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        root.printAll(g);
        g.dispose();
        return img;
    }

    @Test public void rendersSlotTimers() throws Exception {
        BufferedImage img = slots(new long[]{3 * M, 47 * M, 130 * M, -1, 65 * M + 10_000, 20_000, -1, 12 * M});
        ImageIO.write(scale(img), "png", dir().resolve("ge-slots-timers.png").toFile());
        assertTrue("red 2h 10m", count(img, SlotTimerOverlay.OLD) > 20);
        assertTrue("amber 47m", count(img, SlotTimerOverlay.STALE) > 20);
        assertTrue("dark 3m text", count(img, SlotTimerOverlay.FRESH_TEXT) > 5);
    }

    @Test public void rendersLongSlotTimers() throws Exception {
        // Slots 3 and 6 are empty (no chip).
        long[] ages = {9 * H + 59 * M, 51 * H, 12 * 24 * H + 5 * H, -1, 14 * H, 24 * H, -1, 140 * 24 * H};
        assertEquals("12d", ShortDuration.format(ages[2]));
        assertEquals("99d+", ShortDuration.format(ages[7]));
        BufferedImage img = slots(ages);
        ImageIO.write(scale(img), "png", dir().resolve("ge-slots-timers-long.png").toFile());
    }

    /** Eight stand-in slots with chips; asserts every chip stays in its slot and clear of the slot's text. */
    private static BufferedImage slots(long[] age) {
        String[] side = {"Buy", "Sell", "Buy", "Empty", "Sell", "Buy", "Empty", "Sell"};
        String[] item = {"Abyssal whip", "Nature rune", "Cannonball", null, "Dragon bones", "Zulrah's scales", null, "Rune platebody"};
        int[] pct = {30, 70, 5, 0, 50, 0, 0, 90};
        BufferedImage img = new BufferedImage(488, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3e, 0x35, 0x29));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        Font small = FontManager.getRunescapeSmallFont();
        Font bold = FontManager.getRunescapeBoldFont();
        g.setFont(bold);
        g.setColor(new Color(0xff, 0x98, 0x1f));
        g.drawString("Grand Exchange", 190, 20);
        List<Rectangle> chips = new ArrayList<>();
        List<Rectangle> content = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Rectangle slot = new Rectangle(7 + (i % 4) * 119, 40 + (i / 4) * 125, 115, 110);
            boolean empty = item[i] == null;
            if (empty) side[i] = "Empty";
            content.addAll(drawSlot(g, slot, side[i], item[i], pct[i], small, bold));
            if (empty || age[i] < 0) continue;
            Rectangle chip = SlotTimerOverlay.paintLabel(g, slot, ShortDuration.format(age[i]),
                SlotTimerOverlay.chip(age[i]), small);
            assertTrue("chip inside its slot", slot.contains(chip));
            chips.add(chip);
        }
        g.dispose();
        for (Rectangle c : chips)
            for (Rectangle t : content)
                assertFalse("timer chip " + c + " overlaps slot text " + t, c.intersects(t));
        return img;
    }

    /**
     * A GE offer slot after Paul's in-game shot: header strip with the centred title, icon and name,
     * progress bar, price. Returns the title/icon/name boxes (plus a 2px margin around the title).
     */
    private static List<Rectangle> drawSlot(Graphics2D g, Rectangle s, String side, String item, int pct, Font small, Font bold) {
        List<Rectangle> boxes = new ArrayList<>();
        g.setColor(new Color(0x2b, 0x25, 0x1c));
        g.fillRect(s.x, s.y, s.width, s.height);
        g.setColor(new Color(0x16, 0x12, 0x0d));
        g.drawRect(s.x, s.y, s.width - 1, s.height - 1);
        g.drawLine(s.x, s.y + 22, s.x + s.width - 1, s.y + 22);
        g.setFont(bold);
        FontMetrics fb = g.getFontMetrics();
        int tw = fb.stringWidth(side);
        int tx = s.x + (s.width - tw) / 2, ty = s.y + 16;
        g.setColor(new Color(0xff, 0x98, 0x1f));
        g.drawString(side, tx, ty);
        boxes.add(new Rectangle(tx - 2, ty - fb.getAscent(), tw + 4, fb.getAscent()));
        if (item == null) return boxes;
        Rectangle icon = new Rectangle(s.x + 6, s.y + 30, 32, 32);
        g.setColor(new Color(0x6b, 0x5e, 0x48));
        g.fillRect(icon.x, icon.y, icon.width, icon.height);
        boxes.add(icon);
        g.setFont(small);
        FontMetrics fs = g.getFontMetrics();
        g.setColor(Color.WHITE);
        int ly = s.y + 42;
        for (String w : item.split(" ")) {
            g.drawString(w, s.x + 44, ly);
            boxes.add(new Rectangle(s.x + 44, ly - fs.getAscent(), fs.stringWidth(w), fs.getAscent()));
            ly += 12;
        }
        Rectangle bar = new Rectangle(s.x + 6, s.y + 72, s.width - 12, 12);
        g.setColor(new Color(0x10, 0x0d, 0x09));
        g.fillRect(bar.x, bar.y, bar.width, bar.height);
        g.setColor(new Color(0xd8, 0x80, 0x20));
        g.fillRect(bar.x, bar.y, bar.width * pct / 100, bar.height);
        String price = "1,234,567 coins";
        g.setColor(new Color(0xff, 0xb0, 0x40));
        g.drawString(price, s.x + (s.width - fs.stringWidth(price)) / 2, s.y + 99);
        return boxes;
    }

    /** Overlay bounds when drawn at the fixed-mode TOP_LEFT anchor. */
    private static Rectangle fixedBounds(QuoteDto q, OfferPriceWarning.Warning w, OfferPriceWarning.ThinMargin thin,
                                         BuyLimits.Status s, long now) {
        BufferedImage scratch = new BufferedImage(765, 503, BufferedImage.TYPE_INT_ARGB);
        Dimension d = paintTwice(scratch, FIXED_X, FIXED_Y, q, w, thin, s, now);
        return new Rectangle(FIXED_X, FIXED_Y, d.width, d.height);
    }

    /** A 765x503 fixed client with the Set up offer screen, the overlay at its TOP_LEFT anchor, Qty/Price outlined. */
    private static BufferedImage fixed(QuoteDto q, OfferPriceWarning.Warning w, OfferPriceWarning.ThinMargin thin,
                                       BuyLimits.Status s, long now) {
        BufferedImage img = new BufferedImage(765, 503, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x2d, 0x28, 0x22));
        g.fillRect(0, 0, 765, 503);
        g.setColor(new Color(0x40, 0x5a, 0x30));
        g.fillRect(4, 4, 512, 334);
        int x = 16, y = 21;
        g.setColor(new Color(0x49, 0x40, 0x34));
        g.fillRect(x, y, 488, 300);
        g.setColor(new Color(0x3e, 0x35, 0x29));
        g.fillRect(x + 6, y + 6, 476, 23);
        g.setFont(FontManager.getRunescapeFont());
        g.setColor(new Color(0xff, 0x98, 0x1f));
        g.drawString("Grand Exchange: Set up offer", x + 170, y + 22);
        g.setColor(new Color(0x3a, 0x32, 0x27));
        g.fillRect(x + 20, y + 48, 41, 37);
        g.fillRect(FIXED_QTY.x, FIXED_QTY.y, FIXED_QTY.width, FIXED_QTY.height);
        g.fillRect(FIXED_PRICE.x, FIXED_PRICE.y, FIXED_PRICE.width, FIXED_PRICE.height);
        g.setColor(new Color(0xff, 0x98, 0x1f));
        g.drawString("Quantity", FIXED_QTY.x + 10, FIXED_QTY.y + 16);
        g.drawString("Price per item", FIXED_PRICE.x + 10, FIXED_PRICE.y + 16);
        g.setColor(new Color(0x5a, 0x4e, 0x3c));
        g.fillRect(x + 184, y + 255, 120, 32);
        g.setColor(new Color(0xff, 0x98, 0x1f));
        g.drawString("Confirm", x + 222, y + 276);
        g.setColor(new Color(0xc4, 0xb1, 0x8b));
        g.fillRect(0, 338, 519, 142);
        g.dispose();
        paintTwice(img, FIXED_X, FIXED_Y, q, w, thin, s, now);
        Graphics2D o = img.createGraphics();
        o.setColor(Color.MAGENTA);
        o.drawRect(FIXED_QTY.x, FIXED_QTY.y, FIXED_QTY.width, FIXED_QTY.height);
        o.drawRect(FIXED_PRICE.x, FIXED_PRICE.y, FIXED_PRICE.width, FIXED_PRICE.height);
        o.dispose();
        return scale(img);
    }

    private static Dimension paintTwice(BufferedImage img, int x, int y, QuoteDto q, OfferPriceWarning.Warning w,
                                        OfferPriceWarning.ThinMargin thin, BuyLimits.Status s, long now) {
        PanelComponent panel = new PanelComponent();
        Dimension d = null;
        for (int frame = 0; frame < 2; frame++) { // PanelComponent sizes from the previous frame
            Graphics2D g = img.createGraphics();
            g.translate(x, y);
            g.setFont(FontManager.getRunescapeFont()); // what the overlay renderer sets
            d = GeOfferOverlay.paint(g, panel, q, w, thin, s, now);
            g.dispose();
        }
        assertNotNull(d);
        return d;
    }

    private static JPanel card(String name, JLabel line) {
        assertNotNull(name + " has a limit line", line);
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0),
                BorderFactory.createMatteBorder(0, 2, 0, 0, new Color(0x4f, 0xc3, 0xf7))),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Like CloudPanel's title row: a 24px icon, then the name with a 4px gap.
        JPanel title = new JPanel(new java.awt.BorderLayout());
        title.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(24, 24));
        icon.setOpaque(true);
        icon.setBackground(new Color(0x5a, 0x4e, 0x3c)); // item icon stand-in
        title.add(icon, java.awt.BorderLayout.WEST);
        JLabel nm = new JLabel(name);
        nm.setFont(FontManager.getRunescapeBoldFont());
        nm.setForeground(Color.WHITE);
        nm.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        nm.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        title.add(nm, java.awt.BorderLayout.CENTER);
        title.setMaximumSize(new Dimension(Integer.MAX_VALUE, title.getPreferredSize().height));
        card.add(title);
        card.add(line);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container)
            for (Component k : ((java.awt.Container) c).getComponents()) layout(k);
    }

    private static QuoteDto quote() {
        QuoteDto q = new QuoteDto();
        q.id = 4151; q.buy = 2_000_000; q.sell = 2_400_000; q.placeBuy = 2_001_000; q.placeSell = 2_399_000; q.margin = 350_000;
        return q;
    }

    private static BufferedImage renderOverlay(QuoteDto q, OfferPriceWarning.Warning w, OfferPriceWarning.ThinMargin thin,
                                               BuyLimits.Status s, long now) {
        BufferedImage canvas = new BufferedImage(400, 260, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = canvas.createGraphics();
        bg.setColor(new Color(18, 18, 18));
        bg.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        bg.dispose();
        Dimension d = paintTwice(canvas, 0, 0, q, w, thin, s, now);
        return scale(canvas.getSubimage(0, 0, Math.min(canvas.getWidth(), d.width + 4), Math.min(canvas.getHeight(), d.height + 4)));
    }

    private static BufferedImage scale(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth() * 2, src.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
    }

    private static int count(BufferedImage img, Color c) {
        int n = 0, rgb = c.getRGB() & 0xffffff;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) & 0xffffff) == rgb) n++;
        return n;
    }
}
