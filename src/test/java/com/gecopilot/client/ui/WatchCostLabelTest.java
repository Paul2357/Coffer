package com.gecopilot.client.ui;

import com.gecopilot.client.api.PositionDto;
import com.gecopilot.client.api.WatchDto;
import org.junit.Test;

import static org.junit.Assert.*;

public class WatchCostLabelTest {
    @Test
    public void fullCostKeepsPlainLabel() {
        WatchDto w = new WatchDto();
        w.buyCost = 120;
        assertEquals("server sent no qty", "Your cost", CloudPanel.costLabel(w));
        w.trackedQty = 42; w.heldQty = 42;
        assertEquals("Avg cost (42 known)", CloudPanel.costLabel(w));
        assertEquals(120, CloudPanel.knownCost(w));
    }

    @Test
    public void partialCostSaysHowManyAreKnown() {
        WatchDto w = new WatchDto();
        w.knownCost = 153234; w.partialCost = true; w.trackedQty = 22; w.heldQty = 42;
        assertEquals("Avg cost (22 known)", CloudPanel.costLabel(w));
        assertEquals("Cost known for 22 of 42", CloudPanel.costKnownLine(w));
        assertEquals(153234, CloudPanel.knownCost(w));
    }

    @Test
    public void partialFromAnOlderServerFallsBackToBuyCost() {
        WatchDto w = new WatchDto();
        w.buyCost = 153234; w.partialCost = true; w.trackedQty = 22; w.heldQty = 42;
        assertEquals(153234, CloudPanel.knownCost(w));
    }

    @Test
    public void signedAmounts() {
        assertEquals("0 gp", CloudPanel.signedGp(0));
        assertEquals("+4,948 gp", CloudPanel.signedGp(4_948));
        assertEquals(CloudPanel.MINUS + "890,318 gp", CloudPanel.signedGp(-890_318));
        assertEquals(CloudPanel.NEUTRAL, CloudPanel.edgeColor(0));
        assertEquals(CloudPanel.WARN, CloudPanel.edgeColor(-1));
        // True minus only if the RuneLite panel fonts have the glyph; they currently do not, so hyphen.
        boolean has = net.runelite.client.ui.FontManager.getRunescapeSmallFont().canDisplay('\u2212')
            && net.runelite.client.ui.FontManager.getRunescapeFont().canDisplay('\u2212');
        assertEquals(has ? "\u2212" : "-", CloudPanel.MINUS);
    }

    @Test
    public void adviceColourFollowsMeaning() {
        PositionDto p = new PositionDto();
        p.warn = true; p.verdict = "REPRICE";
        assertEquals(CloudPanel.WARN, CloudPanel.adviceColor(p));
        p = new PositionDto();
        p.partialCost = true; p.verdict = "CHECK";
        assertEquals(CloudPanel.PARTIAL, CloudPanel.adviceColor(p));
        p = new PositionDto();
        p.verdict = "HOLD";
        assertEquals(CloudPanel.NEUTRAL, CloudPanel.adviceColor(p));
        assertEquals(0xFF7B72, CloudPanel.WARN.getRGB() & 0xFFFFFF);
        assertEquals(0xFFB020, CloudPanel.PARTIAL.getRGB() & 0xFFFFFF);
        assertEquals(0xA8A8A8, CloudPanel.NEUTRAL.getRGB() & 0xFFFFFF);
        assertEquals(CloudPanel.NEUTRAL, CloudPanel.verdictColor("CHECK"));
        assertEquals(CloudPanel.PARTIAL, CloudPanel.verdictColor("REPRICE"));
        assertEquals(CloudPanel.WARN, CloudPanel.verdictColor("CUT"));
    }
}
