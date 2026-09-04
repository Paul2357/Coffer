package com.gecopilot.client.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiClientAuthTest {
    private static final ApiClient API = new ApiClient(null, new com.google.gson.Gson(), "");
    @Test public void parsesLogin() {
        LoginResult r = API.parseLogin("{\"token\":\"abc\",\"tier\":\"premium\"}");
        assertTrue(r.ok);
        assertEquals("abc", r.token);
        assertEquals("premium", r.tier);
        assertFalse(API.parseLogin("{\"error\":\"bad\"}").ok);
        assertFalse(API.parseLogin("nope").ok);
    }

    @Test public void parsesMe() {
        Me m = API.parseMe("{\"username\":\"alice\",\"tier\":\"standard\"}");
        assertEquals("alice", m.username);
        assertEquals("standard", m.tier);
        assertNull(API.parseMe("nope"));
    }
}
