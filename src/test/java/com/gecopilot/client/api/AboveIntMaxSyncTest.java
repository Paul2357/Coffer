package com.gecopilot.client.api;

import com.gecopilot.client.trade.CompletedFill;
import com.gecopilot.client.trade.OfferCapture;
import com.gecopilot.client.ui.OfferPriceWarning;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * gp past 2,147,483,647. GE trades can clear above it since 30 September 2026 ("GE Improvements:
 * Beyond Max Cash", https://oldschool.runescape.wiki/w/Grand_Exchange). An int field here would
 * make Gson throw and blank the whole sync.
 */
public class AboveIntMaxSyncTest {
    private static final ApiClient API = new ApiClient(null, new Gson(), "");

    @Test public void syncParsesPricesAboveIntMax() {
        String json = "{\"tier\":\"premium\","
            + "\"flips\":[{\"id\":30810,\"buyPrice\":2900000000,\"sellPrice\":3100000000,\"margin\":195000000,"
            + "\"placeBuy\":2900000001,\"placeSell\":3099999999}],"
            + "\"plan\":[{\"id\":30810,\"qty\":1,\"buyPrice\":2900000001,\"sellPrice\":3099999999,\"expProfit\":194999998}],"
            + "\"positions\":[{\"id\":30810,\"buy\":false,\"sold\":0,\"total\":1,\"price\":3002000000,\"edge\":-3000000,"
            + "\"target\":3005000000,\"targetLabel\":\"Breakeven at\",\"avgDownPrice\":2900000000,\"avgDownBreakeven\":2955000000}],"
            + "\"alch\":[],\"crash\":[{\"id\":30810,\"current\":3000000000,\"dayAvg\":3600000000}],"
            + "\"watch\":[{\"id\":30810,\"buy\":2900000000,\"sell\":3100000000,\"margin\":195000000,"
            + "\"buyCost\":3000000000,\"breakeven\":3005000000,\"knownCost\":3000000000,\"sellableNow\":true}]}";
        SyncResult r = API.parseSync(json);
        assertEquals(1, r.flips.size());
        assertEquals(3_100_000_000L, r.flips.get(0).sellPrice);
        assertEquals(2_900_000_001L, r.flips.get(0).placeBuy);
        assertEquals(3_099_999_999L, r.plan.get(0).sellPrice);
        assertEquals(3_002_000_000L, r.positions.get(0).price);
        assertEquals(Long.valueOf(3_005_000_000L), r.positions.get(0).target);
        assertEquals(Long.valueOf(2_955_000_000L), r.positions.get(0).avgDownBreakeven);
        assertEquals(3_600_000_000L, r.crash.get(0).dayAvg);
        assertEquals(3_005_000_000L, r.watch.get(0).breakeven);
        assertEquals(3_000_000_000L, r.watch.get(0).knownCost);
    }

    @Test public void taxCapAndMarginAboveIntMax() {
        assertEquals(5_000_000L, OfferPriceWarning.saleCost(30810, 3_000_000_000L));
        assertEquals(3_300_000_000L, OfferPriceWarning.buyCost(13190, 3_000_000_000L));
        assertEquals(0L, OfferPriceWarning.saleCost(13190, 3_000_000_000L));
        OfferPriceWarning.ThinMargin thin = OfferPriceWarning.evaluateMargin(30810, true,
            3_000_000_000L, 3_004_000_000L, 1, 0);
        assertNotNull(thin);
        assertEquals(-1_000_000L, thin.marginGp);
    }

    @Test public void captureAndQueueKeepFillPricesAboveIntMax() {
        OfferCapture c = new OfferCapture();
        c.onOffer(0, "BUYING", 30810, 2, 0, 0, 1);
        CompletedFill f = c.onOffer(0, "BOUGHT", 30810, 2, 2, 6_000_000_000L, 2);
        assertNotNull(f);
        assertEquals(3_000_000_000L, f.price);
        Gson g = new Gson();
        assertEquals(3_000_000_000L, g.fromJson(g.toJson(f), CompletedFill.class).price);
    }
}
