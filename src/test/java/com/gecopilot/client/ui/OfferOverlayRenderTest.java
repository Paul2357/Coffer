package com.gecopilot.client.ui;

import com.gecopilot.client.api.QuoteDto;
import net.runelite.client.ui.overlay.components.PanelComponent;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Renders the real overlay panel headlessly and writes the two warning screenshots. */
public class OfferOverlayRenderTest {
    @Test public void rendersSellAndBuyWarnings() throws Exception {
        Path dir = Paths.get(System.getProperty("overlay.out", "build/overlay-screenshots"));
        Files.createDirectories(dir);

        QuoteDto sellQuote = quote(2_000_000, 2_400_000, 2_001_000, 2_399_000, 350_000);
        OfferPriceWarning.Warning sell = OfferPriceWarning.evaluate(false, 1_800_000, sellQuote.buy, sellQuote.sell);
        assertNotNull(sell);
        BufferedImage sellImg = render(sellQuote, sell);
        assertTrue("sell panel pixels=" + paintedCount(sellImg), paintedCount(sellImg) > 20);
        ImageIO.write(sellImg, "png", dir.resolve("overlay-sell-warning.png").toFile());

        QuoteDto buyQuote = quote(2_000_000, 2_400_000, 2_001_000, 2_399_000, 350_000);
        OfferPriceWarning.Warning buy = OfferPriceWarning.evaluate(true, 2_800_000, buyQuote.buy, buyQuote.sell);
        assertNotNull(buy);
        assertEquals(400_000, buy.gapGp);
        BufferedImage buyImg = render(buyQuote, buy);
        assertTrue("buy panel pixels=" + paintedCount(buyImg), paintedCount(buyImg) > 20);
        ImageIO.write(buyImg, "png", dir.resolve("overlay-buy-warning.png").toFile());

        assertNotEquals(fingerprint(sellImg), fingerprint(buyImg));
    }

    @Test public void rendersThinMarginWarning() throws Exception {
        Path dir = Paths.get(System.getProperty("overlay.out", "build/overlay-screenshots"));
        Files.createDirectories(dir);

        // Adamant arrows: high 28, low 26, after-tax margin 2 gp. Place prices follow QuoteRoutes
        // (margin > 4 bumps a tick; 2 gp does not). The user's bid of 27 is between low and high,
        // so the buy-over-high line stays quiet and only the thin-margin warning shows.
        QuoteDto q = quote(26, 28, 26, 28, 2);
        OfferPriceWarning.ThinMargin thin = OfferPriceWarning.evaluateMargin(true, q.buy, q.sell, 10, 0.3);
        assertNotNull(thin);
        assertEquals(2L, thin.marginGp);
        assertNull(OfferPriceWarning.evaluate(true, 27, q.buy, q.sell));

        PanelComponent panel = new PanelComponent();
        BufferedImage canvas = new BufferedImage(400, 280, BufferedImage.TYPE_INT_ARGB);
        Dimension d = null;
        for (int frame = 0; frame < 2; frame++) {
            Graphics2D g = canvas.createGraphics();
            g.setColor(new Color(18, 18, 18));
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            d = GeOfferOverlay.paint(g, panel, q, null, thin);
            g.dispose();
        }
        assertNotNull(d);
        assertTrue(d.width > 0 && d.height > 0);
        int w = Math.min(canvas.getWidth(), d.width + 4);
        int h = Math.min(canvas.getHeight(), d.height + 4);
        BufferedImage crop = canvas.getSubimage(0, 0, w, h);
        BufferedImage scaled = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = scaled.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        sg.drawImage(crop, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        sg.dispose();
        assertTrue("thin panel pixels=" + paintedCount(scaled), paintedCount(scaled) > 20);
        ImageIO.write(scaled, "png", dir.resolve("overlay-thin-margin.png").toFile());
    }

    private static QuoteDto quote(int low, int high, int placeBuy, int placeSell, long margin) {
        QuoteDto q = new QuoteDto();
        q.id = 4151;
        q.buy = low;
        q.sell = high;
        q.placeBuy = placeBuy;
        q.placeSell = placeSell;
        q.margin = margin;
        return q;
    }

    private static BufferedImage render(QuoteDto q, OfferPriceWarning.Warning warning) {
        // PanelComponent sizes itself from the previous frame's children, same as in the client
        // (the overlay renders every game frame). Paint twice and keep the second frame.
        PanelComponent panel = new PanelComponent();
        BufferedImage canvas = new BufferedImage(360, 240, BufferedImage.TYPE_INT_ARGB);
        Dimension d = null;
        for (int frame = 0; frame < 2; frame++) {
            Graphics2D g = canvas.createGraphics();
            g.setColor(new Color(18, 18, 18));
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            d = GeOfferOverlay.paint(g, panel, q, warning);
            g.dispose();
        }
        assertNotNull(d);
        assertTrue(d.width > 0 && d.height > 0);
        int w = Math.min(canvas.getWidth(), d.width + 4);
        int h = Math.min(canvas.getHeight(), d.height + 4);
        BufferedImage crop = canvas.getSubimage(0, 0, w, h);
        BufferedImage scaled = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = scaled.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        sg.drawImage(crop, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        sg.dispose();
        return scaled;
    }

    private static int paintedCount(BufferedImage img) {
        int seen = 0;
        for (int y = 0; y < img.getHeight(); y += 3) {
            for (int x = 0; x < img.getWidth(); x += 3) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) == 0) continue;
                if ((argb & 0xffffff) != 0x121212) seen++;
            }
        }
        return seen;
    }

    private static int fingerprint(BufferedImage img) {
        int h = 0;
        for (int y = 0; y < img.getHeight(); y += 4)
            for (int x = 0; x < img.getWidth(); x += 4)
                h = 31 * h + img.getRGB(x, y);
        return h;
    }
}
