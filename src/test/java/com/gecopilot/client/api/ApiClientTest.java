package com.gecopilot.client.api;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ApiClientTest {
    private static final ApiClient API = new ApiClient(null, new com.google.gson.Gson(), "");
    @Test public void parsesFlips() {
        String json = "{\"tier\":\"standard\",\"flips\":[{\"id\":1,\"name\":\"Shark\",\"buyPrice\":100,"
            + "\"sellPrice\":120,\"margin\":18,\"marginPct\":18.0,\"canBuy\":5,\"cycleProfit\":90,"
            + "\"fillProb\":0.7,\"placeBuy\":101,\"placeSell\":119}]}";
        List<CloudFlip> flips = API.parseFlips(json);
        assertEquals(1, flips.size());
        CloudFlip f = flips.get(0);
        assertEquals("Shark", f.name);
        assertEquals(100, f.buyPrice);
        assertEquals(120, f.sellPrice);
        assertEquals(101, f.placeBuy);
    }

    @Test public void parsesDashboard() {
        Dashboard d = API.parseDashboard("{\"realized\":12345,\"flips\":3,\"fillRate\":\"86%\"}");
        assertEquals(12345, d.realized);
        assertEquals(3, d.flips);
        assertEquals("86%", d.fillRate);
    }

    @Test public void badJsonYieldsEmpty() {
        assertTrue(API.parseFlips("not json").isEmpty());
        assertNull(API.parseDashboard("nope"));
    }

    @Test public void parsesSparklineDroppingZeroPointsAndCappingTo24() {
        StringBuilder wiki = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
            if (i > 0) wiki.append(",");
            wiki.append("{\"ts\":").append(i).append(",\"high\":100,\"low\":80}");
        }
        wiki.append(",{\"ts\":99,\"high\":0,\"low\":0}"); // dropped: both zero
        wiki.append("]");
        String json = "{\"id\":4151,\"name\":\"Whip\",\"wiki\":" + wiki + "}";
        SparklineDto d = API.parseSparkline(json);
        assertEquals(4151, d.id);
        assertEquals("Whip", d.name);
        assertEquals(24, d.points.size()); // capped to last 24 of the 30 non-zero points
        assertEquals(6, d.points.get(0).ts); // first of the last 24 (indices 6..29)
        assertEquals(90.0, d.points.get(0).mid, 0.001); // (100+80)/2
    }

    @Test public void parseSparklineYieldsNullWithoutWiki() {
        assertNull(API.parseSparkline("{\"id\":1}"));
        assertNull(API.parseSparkline("not json"));
    }
}
