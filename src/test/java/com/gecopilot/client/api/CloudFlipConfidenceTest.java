package com.gecopilot.client.api;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CloudFlipConfidenceTest {
    private static final ApiClient API = new ApiClient(null, new com.google.gson.Gson(), "");
    @Test public void parsesConfidenceWhenPresentNullOtherwise() {
        List<CloudFlip> withConf = API.parseFlips(
            "{\"flips\":[{\"id\":1,\"name\":\"Whip\",\"confidence\":78}]}");
        assertEquals(Integer.valueOf(78), withConf.get(0).confidence);

        List<CloudFlip> noConf = API.parseFlips(
            "{\"flips\":[{\"id\":1,\"name\":\"Whip\"}]}");
        assertNull(noConf.get(0).confidence);
    }
}
