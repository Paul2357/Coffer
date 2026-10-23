package com.gecopilot.client.ui;

import com.gecopilot.client.api.PositionDto;
import com.gecopilot.client.api.WatchDto;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Renders the real position and watch cards (PR #43 states) headlessly and writes PNGs. */
public class CostCardRenderTest {
    private static final int W = 203; // panel card width: 187 inner + 2 stripe + 2*8 padding - 2

    @Test public void rendersCostCards() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path dir = Paths.get(System.getProperty("cards.out", "build/card-renders"));
        Files.createDirectories(dir);
        CloudPanel[] h = new CloudPanel[1];
        SwingUtilities.invokeAndWait(() -> h[0] = new CloudPanel(null));
        CloudPanel panel = h[0];

        PositionDto buy = pos(true, 3, 10, 149_105, 49_480L, "HOLD", "Competitive — will fill", false);
        PositionDto partial = pos(false, 0, 42, 157_196, null, "CHECK", "Cost known for 22 of 42", false);
        partial.partialCost = true; partial.knownQty = 22;
        // Known units already lose 890k (past 1% / 50k): REPRICE with the loss warning, no breakeven.
        PositionDto partialLoss = pos(false, 0, 42, 157_196, null, "REPRICE", "Sells at a loss", true);
        partialLoss.partialCost = true; partialLoss.knownQty = 22; partialLoss.knownLoss = -890_318L;
        partialLoss.confidence = "HIGH";
        PositionDto cut = pos(false, 0, 42, 157_196, -1_699_698L, "CUT", "Falling \u2014 cut to sell", true);
        cut.confidence = "HIGH";
        PositionDto coveredLoss = pos(false, 0, 42, 157_196, -1_699_698L, "REPRICE", "Sells at a loss", true);
        coveredLoss.confidence = "HIGH"; coveredLoss.target = 198_491; coveredLoss.targetLabel = "Breakeven at";

        WatchDto wPartial = watch(true); WatchDto wFull = watch(false);
        WatchDto wSellable = watch(false); wSellable.sellableNow = true; wSellable.breakeven = 156_361;
        PositionDto zero = pos(true, 0, 10, 154_053, 0L, "HOLD", "Competitive \u2014 will fill", false);
        // 1 known of 600 losing 12 gp: under the partial floor, so CHECK with an amber loss row.
        PositionDto smallLoss = pos(false, 0, 600, 188, null, "CHECK", "Cost known for 1 of 600", false);
        smallLoss.name = "Contract of Glyphic Attenuation";
        smallLoss.partialCost = true; smallLoss.knownQty = 1; smallLoss.knownLoss = -12L;

        List<String> texts = new ArrayList<>();
        render(panel, () -> panel.positionCard(buy), dir, "position-buy", texts);
        assertTrue(texts.contains("Market margin"));
        assertFalse(texts.contains("Flip edge"));
        render(panel, () -> panel.positionCard(partial), dir, "position-sell-partial", texts);
        assertTrue(texts.contains("Profit")); assertTrue(texts.contains("unknown"));
        assertTrue(texts.contains("Cost known for 22 of 42"));
        render(panel, () -> panel.positionCard(partialLoss), dir, "position-sell-partial-loss", texts);
        assertTrue(texts.contains("Loss (22 known)")); assertTrue(texts.contains(CloudPanel.MINUS + "890,318 gp"));
        assertTrue(texts.contains("Sells at a loss")); assertTrue(texts.contains("REPRICE \u00b7 HIGH"));
        render(panel, () -> panel.positionCard(cut), dir, "position-sell-cut", texts);
        assertTrue(texts.contains("CUT \u00b7 HIGH"));
        render(panel, () -> panel.positionCard(coveredLoss), dir, "position-sell-covered-loss", texts);
        assertTrue(texts.contains("Sells at a loss")); assertTrue(texts.contains(CloudPanel.MINUS + "1,699,698 gp"));
        render(panel, () -> panel.watchCard(wPartial), dir, "watch-partial", texts);
        assertTrue(texts.contains("Avg cost (22 known)")); assertTrue(texts.contains("Cost known for 22 of 42"));
        assertTrue(texts.contains("No profit or recovery shown"));
        assertFalse(texts.stream().anyMatch(t -> t.startsWith("Recovers at") || t.startsWith("Sellable")));
        render(panel, () -> panel.watchCard(wFull), dir, "watch-full", texts);
        assertTrue(texts.contains("Avg cost (42 known)")); assertTrue(texts.contains("Recovers at 156,361"));
        render(panel, () -> panel.watchCard(wSellable), dir, "watch-full-sellable", texts);
        assertTrue(texts.contains("Sellable now"));
        render(panel, () -> panel.positionCard(zero), dir, "position-buy-zero-edge", texts);
        assertTrue(texts.contains("0 gp")); assertFalse(texts.contains("+0 gp"));
        render(panel, () -> panel.positionCard(smallLoss), dir, "position-sell-partial-small-loss", texts);
        assertTrue(texts.contains("CHECK")); assertTrue(texts.contains("Loss (1 known)"));
        assertTrue(texts.contains(CloudPanel.MINUS + "12 gp"));
    }

    /** Draws U+2212 and a hyphen in the panel fonts so the glyph check can be eyeballed. */
    @Test public void minusGlyphCheck() throws Exception {
        Path dir = Paths.get(System.getProperty("cards.out", "build/card-renders"));
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(260, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x28, 0x28, 0x28)); g.fillRect(0, 0, 260, 60);
        g.setColor(Color.WHITE);
        g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
        g.drawString("U+2212: [\u2212890,318]  hyphen: [-890,318]", 6, 22);
        g.setFont(net.runelite.client.ui.FontManager.getRunescapeFont());
        g.drawString("U+2212: [\u2212890]  hyphen: [-890]", 6, 46);
        g.dispose();
        ImageIO.write(img, "png", dir.resolve("minus-glyph-check.png").toFile());
    }

    private static PositionDto pos(boolean buy, int sold, int total, int price, Long edge, String verdict, String advice, boolean warn) {
        PositionDto p = new PositionDto();
        p.id = 30810; p.name = "Contract of Glyphic Attenuation"; p.buy = buy; p.sold = sold; p.total = total;
        p.price = price; p.edge = edge; p.openMin = 12; p.verdict = verdict; p.advice = advice; p.warn = warn;
        return p;
    }

    private static WatchDto watch(boolean partial) {
        WatchDto w = new WatchDto();
        w.id = 30810; w.name = "Contract of Glyphic Attenuation"; w.buy = 149_105; w.sell = 157_196; w.margin = 4_948;
        w.heldQty = 42; w.trackedQty = partial ? 22 : 42; w.partialCost = partial;
        if (partial) { w.knownCost = 153_234; } else { w.buyCost = 153_234; w.knownCost = 153_234; w.breakeven = 156_361; }
        return w;
    }

    private interface Card { JPanel make(); }

    private static void render(CloudPanel panel, Card make, Path dir, String name, List<String> texts) throws Exception {
        BufferedImage[] out = new BufferedImage[2];
        texts.clear();
        SwingUtilities.invokeAndWait(() -> {
            JPanel card = make.make();
            card.setSize(W, 10);
            layout(card);
            card.setSize(W, card.getPreferredSize().height);
            layout(card);
            collect(card, texts);
            assertTrue("full item name shown (wrapped, not cut): " + texts, texts.contains("Contract of Glyphic Attenuation"));
            assertTrue("tooltip carries the full name", hasTooltip(card, "Contract of Glyphic Attenuation"));
            for (int s = 1; s <= 2; s++) {
                BufferedImage img = new BufferedImage(W * s, card.getHeight() * s, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.scale(s, s);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                card.printAll(g);
                g.dispose();
                out[s - 1] = img;
            }
        });
        ImageIO.write(out[0], "png", dir.resolve(name + ".png").toFile());
        ImageIO.write(out[1], "png", dir.resolve(name + "@2x.png").toFile());
        assertTrue(out[0].getHeight() > 60);
    }

    private static void layout(Component c) {
        if (c instanceof Container) {
            ((Container) c).doLayout();
            for (Component k : ((Container) c).getComponents()) layout(k);
        }
    }

    private static boolean hasTooltip(Component c, String tip) {
        if (c instanceof JComponent && tip.equals(((JComponent) c).getToolTipText())) return true;
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) if (hasTooltip(k, tip)) return true;
        return false;
    }

    @Test public void wrapsLongNamesToTwoLinesAndEllipsizesOnlyBeyondThat() {
        java.awt.FontMetrics fm = new JLabel().getFontMetrics(net.runelite.client.ui.FontManager.getRunescapeBoldFont());
        String[] two = CloudPanel.wrapTitle("Contract of Glyphic Attenuation", fm, CloudPanel.TITLE_W);
        assertEquals(2, two.length);
        assertEquals("Contract of Glyphic Attenuation", two[0] + " " + two[1]);
        for (String l : two) assertTrue(l, fm.stringWidth(l) <= CloudPanel.TITLE_W);
        assertArrayEquals(new String[]{"Coal"}, CloudPanel.wrapTitle("Coal", fm, CloudPanel.TITLE_W));
        String[] huge = CloudPanel.wrapTitle("Ancient ceremonial top of the very long named thing (broken) and more words", fm, CloudPanel.TITLE_W);
        assertEquals(2, huge.length);
        assertTrue(huge[1].endsWith("..."));
        assertTrue(fm.stringWidth(huge[1]) <= CloudPanel.TITLE_W);
    }

    private static void collect(Component c, List<String> texts) {
        if (c instanceof JLabel && ((JLabel) c).getText() != null) {
            String t = ((JLabel) c).getText().replace("<br>", " ").replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim();
            if (!t.isEmpty()) texts.add(t);
            // A label wider than it is laid out is truncated with "...": fail on that.
            JLabel l = (JLabel) c;
            if (l.getWidth() > 0 && l.getPreferredSize().width > l.getWidth() + 1)
                fail("truncated label: " + t + " (" + l.getPreferredSize().width + " > " + l.getWidth() + ")");
        }
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) collect(k, texts);
    }
}
