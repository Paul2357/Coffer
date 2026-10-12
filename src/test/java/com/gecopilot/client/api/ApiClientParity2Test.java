package com.gecopilot.client.api;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ApiClientParity2Test {
    private static final ApiClient API = new ApiClient(null, new com.google.gson.Gson(), "");
    @Test public void parsesAlchCrashWatchInSync() {
        String json = "{\"tier\":\"standard\",\"flips\":[],\"plan\":[],\"positions\":[],"
            + "\"alch\":[{\"id\":1,\"name\":\"Onyx\",\"buy\":100,\"highalch\":130,\"profit\":20,\"roi\":20.0,\"volume\":500}],"
            + "\"crash\":[{\"id\":2,\"name\":\"Rune\",\"current\":80,\"dayAvg\":100,\"pctChange\":-20.0,\"volume\":900}],"
            + "\"watch\":[{\"id\":3,\"name\":\"Whip\",\"buy\":100,\"sell\":130,\"margin\":28,\"buyCost\":120,\"breakeven\":123,\"sellableNow\":true}]}";
        SyncResult r = API.parseSync(json);
        assertEquals(1, r.alch.size());
        assertEquals("Onyx", r.alch.get(0).name);
        assertEquals(1, r.crash.size());
        assertEquals(-20.0, r.crash.get(0).pctChange, 0.001);
        assertEquals(1, r.watch.size());
        assertTrue(r.watch.get(0).sellableNow);
    }

    @Test public void parseFindReusesFlipShape() {
        String json = "[{\"id\":1,\"name\":\"Whip\",\"placeBuy\":101,\"placeSell\":119,\"margin\":18,\"fillProb\":0.7}]";
        List<CloudFlip> r = API.parseFlipArray(json);
        assertEquals(1, r.size());
        assertEquals("Whip", r.get(0).name);
    }
}
