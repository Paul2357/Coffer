package com.gecopilot.client.api;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ApiClientParityTest {
    private static final ApiClient API = new ApiClient(null, new com.google.gson.Gson(), "");
    @Test public void parsesSyncWithPlanAndPositions() {
        String json = "{\"tier\":\"standard\","
            + "\"flips\":[{\"id\":1,\"name\":\"Shark\",\"placeBuy\":101,\"placeSell\":119,\"margin\":18,\"fillProb\":0.7}],"
            + "\"plan\":[{\"id\":1,\"name\":\"Shark\",\"qty\":5,\"buyPrice\":101,\"sellPrice\":119,\"expProfit\":80,\"fillHrs\":1.5,\"limit\":100,\"limitLeft\":100,\"limitResetMs\":0}],"
            + "\"positions\":[{\"id\":2,\"name\":\"Bones\",\"buy\":true,\"sold\":3,\"total\":10,\"price\":90,\"edge\":30,\"openMin\":6,\"advice\":\"Underbidding\",\"warn\":true,\"target\":100,\"targetLabel\":\"Raise buy to\",\"alchProfit\":null}]}";
        SyncResult r = API.parseSync(json);
        assertEquals("standard", r.tier);
        assertEquals(1, r.flips.size());
        assertEquals(1, r.plan.size());
        assertEquals(5, r.plan.get(0).qty);
        assertEquals(1, r.positions.size());
        assertEquals("Underbidding", r.positions.get(0).advice);
        assertEquals(Integer.valueOf(100), r.positions.get(0).target);
    }

    @Test public void parsesLog() {
        String json = "[{\"id\":1,\"name\":\"Shark\",\"qty\":5,\"buyAvg\":100,\"sellAvg\":130,\"profit\":120,\"timeMs\":1699999999}]";
        List<LogRowDto> log = API.parseLog(json);
        assertEquals(1, log.size());
        assertEquals(130, log.get(0).sellAvg);
    }

    @Test public void emptyOnBadJson() {
        assertTrue(API.parseSync("nope").flips.isEmpty());
        assertTrue(API.parseLog("nope").isEmpty());
    }
}
