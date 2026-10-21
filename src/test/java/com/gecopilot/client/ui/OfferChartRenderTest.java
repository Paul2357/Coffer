package com.gecopilot.client.ui;

import com.gecopilot.client.api.QuoteDto;
import com.gecopilot.client.api.SparklineDto;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Renders the real chart painter headlessly over a chatbox-sized area and writes PNGs. */
public class OfferChartRenderTest {
    private static final long NOW = 1_792_500_000L;
    private static final Rectangle AREA = new Rectangle(0, 0, 519, 142);

    @Test public void rendersOfferChartStates() throws Exception {
        Path dir = Paths.get(System.getProperty("overlay.out", "build/overlay-screenshots"));
        Files.createDirectories(dir);

        OfferChart.Model m = model();
        BufferedImage day = render(m);
        ImageIO.write(day, "png", dir.resolve("offer-chart-1d.png").toFile());
        assertTrue(count(day, OfferChart.INSTABUY, 0) > 50);
        assertTrue(count(day, OfferChart.INSTASELL, 0) > 50);
        assertTrue(count(day, OfferChart.COFFER, 0) > 20);
        assertEquals("no stale note when fresh", 0, count(day, OfferChart.WARN, 0));

        m = model();
        m.failed = true;
        m.dataAgeSec = 12 * 60;
        BufferedImage stale = render(m);
        ImageIO.write(stale, "png", dir.resolve("offer-chart-stale.png").toFile());
        assertTrue("stale note drawn", count(stale, OfferChart.WARN, 0) > 20);
        assertTrue("series dimmed", count(stale, OfferChart.INSTABUY, 0) * 2 < count(day, OfferChart.INSTABUY, 0));

        m = model();
        m.offerPrice = 20; // Paul's "Your offer 20": far below the series, pinned to the bottom edge
        BufferedImage off = render(m);
        ImageIO.write(off, "png", dir.resolve("offer-chart-offchart.png").toFile());
        int bottomRow = AREA.height - OfferChart.PAD - 2;
        assertTrue("offer line pinned to the plot bottom", countRow(off, OfferChart.OFFER, bottomRow) > 100);
        assertEquals("axis still starts near the series, not at 0", day.getRGB(20, 60), off.getRGB(20, 60));

        m = model();
        m.offerPrice = 0;
        BufferedImage noOffer = render(m);
        assertEquals("no offer line or label when the price is unset", 0, count(noOffer, OfferChart.OFFER, 0));

        m = model();
        m.hover = new java.awt.Point(300, 80);
        m.zone = java.time.ZoneId.of("America/New_York");
        BufferedImage hover = render(m);
        ImageIO.write(hover, "png", dir.resolve("offer-chart-hover.png").toFile());
        assertTrue("crosshair + tooltip drawn", diff(day, hover) > 300);
        for (java.awt.Point p : new java.awt.Point[]{new java.awt.Point(300, 80), new java.awt.Point(480, 40), new java.awt.Point(480, 130)}) {
            m = model();
            m.hover = p;
            assertEquals("tooltip at " + p + " leaves the tab/legend header untouched", 0,
                diff(day, render(m), 0, OfferChart.HEADER_H));
        }

        // Background is opaque over the whole overlay, even on a transparent canvas.
        BufferedImage argb = new BufferedImage(AREA.width, AREA.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ga = argb.createGraphics();
        ga.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f)); // a leaked composite
        OfferChart.paint(ga, AREA, model(), FontManager.getRunescapeSmallFont());
        ga.dispose();
        for (int y = 0; y < AREA.height; y++)
            for (int x = 0; x < AREA.width; x++)
                assertEquals("alpha at " + x + "," + y, 255, argb.getRGB(x, y) >>> 24);

        m = model();
        m.quote.placeBuy = 34_400; m.quote.placeSell = 34_500; m.quote.margin = 0; m.offerPrice = 34_450;
        ImageIO.write(render(m), "png", dir.resolve("offer-chart-tight-labels.png").toFile());

        m = model();
        m.points = null;
        BufferedImage loading = render(m);
        ImageIO.write(loading, "png", dir.resolve("offer-chart-loading.png").toFile());
        assertTrue(count(loading, OfferChart.INSTABUY, 0) * 3 < count(day, OfferChart.INSTABUY, 0));
    }

    private static OfferChart.Model model() {
        QuoteDto q = new QuoteDto();
        q.id = 1; q.buy = 33_800; q.sell = 35_400; q.placeBuy = 33_900; q.placeSell = 35_300; q.margin = 694;
        OfferChart.Model m = new OfferChart.Model();
        m.range = OfferChartRange.DAY;
        m.points = sample(288, 300);
        m.nowSec = NOW;
        m.offerPrice = 26_900;
        m.quote = q;
        return m;
    }

    private static BufferedImage render(OfferChart.Model m) {
        BufferedImage img = new BufferedImage(AREA.width, AREA.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x5d, 0x54, 0x47));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        Rectangle[] tabs = OfferChart.paint(g, AREA, m, FontManager.getRunescapeSmallFont());
        g.dispose();
        assertEquals(4, tabs.length);
        return img;
    }

    private static List<SparklineDto.Point> sample(int n, long stepSec) {
        Random rnd = new Random(42);
        List<SparklineDto.Point> pts = new ArrayList<>();
        double mid = 31_000;
        for (int i = 0; i < n; i++) {
            mid += (rnd.nextGaussian() * 350) + (34_600 - mid) * 0.01;
            long spread = 500 + rnd.nextInt(900);
            SparklineDto.Point p = new SparklineDto.Point();
            p.ts = NOW - (n - 1 - i) * stepSec - 480;
            p.high = Math.round(mid + spread / 2.0);
            p.low = rnd.nextInt(12) == 0 ? 0 : Math.round(mid - spread / 2.0);
        p.highVol = 1 + rnd.nextInt(40);
        p.lowVol = p.low == 0 ? 0 : 1 + rnd.nextInt(40);
            pts.add(p);
        }
        return pts;
    }

    private static int diff(BufferedImage a, BufferedImage b) {
        return diff(a, b, 0, a.getHeight());
    }

    private static int diff(BufferedImage a, BufferedImage b, int y0, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = 0; x < a.getWidth(); x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    /** Pixels within {@code tol} of {@code c} per channel (tol 0 = within 24). */
    private static int count(BufferedImage img, Color c, int tol) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) n += countRow(img, c, y);
        return n;
    }

    private static int countRow(BufferedImage img, Color c, int y) {
        int n = 0;
        for (int x = 0; x < img.getWidth(); x++) {
            Color p = new Color(img.getRGB(x, y));
            if (Math.abs(p.getRed() - c.getRed()) < 24 && Math.abs(p.getGreen() - c.getGreen()) < 24
                && Math.abs(p.getBlue() - c.getBlue()) < 24) n++;
        }
        return n;
    }
}
