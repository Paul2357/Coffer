package com.gecopilot.client.api;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CloudFlipCrowdTest {
    private static final ApiClient API = new ApiClient(null, new com.google.gson.Gson(), "");
    @Test public void parsesCrowdFieldsWhenPresent() {
        List<CloudFlip> f = API.parseFlips(
            "{\"flips\":[{\"id\":1,\"name\":\"Whip\",\"crowdFillPct\":82,\"crowdSamples\":140}]}");
        assertEquals(Integer.valueOf(82), f.get(0).crowdFillPct);
        assertEquals(Integer.valueOf(140), f.get(0).crowdSamples);
        List<CloudFlip> none = API.parseFlips("{\"flips\":[{\"id\":1,\"name\":\"Whip\"}]}");
        assertNull(none.get(0).crowdFillPct);
    }
}
