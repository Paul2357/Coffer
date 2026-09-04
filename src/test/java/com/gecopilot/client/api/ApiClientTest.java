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
}
