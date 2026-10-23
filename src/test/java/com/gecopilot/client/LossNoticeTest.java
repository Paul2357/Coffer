package com.gecopilot.client;

import com.gecopilot.client.api.PositionDto;
import org.junit.Test;

import static org.junit.Assert.*;

public class LossNoticeTest {
    private static PositionDto glyphic() {
        PositionDto p = new PositionDto();
        p.id = 30810; p.name = "Contract of Glyphic Attenuation"; p.buy = false; p.total = 42; p.price = 157_196;
        p.edge = -1_699_698L; p.advice = "Sells at a loss"; p.warn = true; p.verdict = "REPRICE";
        p.target = 198_491; p.targetLabel = "Breakeven at";
        return p;
    }

    @Test public void finalCopy() {
        assertEquals("Contract of Glyphic Attenuation sells at a loss at 157,196 (\u22121.70m). Breakeven 198,491.",
            new LossNotice().next(glyphic()));
    }

    @Test public void oncePerItemPriceAndAdvice() {
        LossNotice n = new LossNotice();
        assertNotNull(n.next(glyphic()));
        assertNull("same item + price + advice", n.next(glyphic()));
        PositionDto repriced = glyphic();
        repriced.price = 157_000; repriced.edge = -1_708_000L;
        assertNotNull("a new price is a new offer", n.next(repriced));
    }

    @Test public void smallLossesDoNotNotify() {
        PositionDto p = glyphic();
        p.total = 10; p.price = 102_000; p.edge = -400L; // 0.04% of ~1m, under 50k
        assertNull(new LossNotice().next(p));
        p.edge = -50_000L;                                // >= 50k
        assertNotNull(new LossNotice().next(p));
        PositionDto q = glyphic();
        q.total = 10; q.price = 160; q.edge = -430L;      // 21% of 2,000
        assertEquals("Contract of Glyphic Attenuation sells at a loss at 160 (\u2212430). Breakeven 198,491.", new LossNotice().next(q));
    }

    @Test public void partialCostUsesKnownLossAndNoBreakeven() {
        PositionDto p = glyphic();
        p.edge = null; p.knownLoss = -890_318L; p.knownQty = 22; p.target = null; p.partialCost = true;
        assertEquals("Contract of Glyphic Attenuation sells at a loss at 157,196 (\u2212890k on 22 known units).", new LossNotice().next(p));
    }

    @Test public void otherAdviceIsIgnored() {
        PositionDto p = glyphic();
        p.advice = "Overpriced";
        assertNull(new LossNotice().next(p));
    }
}
