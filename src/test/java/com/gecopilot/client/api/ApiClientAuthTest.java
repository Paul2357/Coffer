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
        assertFalse(r.needsOnboarding);
        assertFalse(API.parseLogin("{\"error\":\"bad\"}").ok);
        assertFalse(API.parseLogin("nope").ok);
    }

    @Test public void parsesLoginNeedsOnboarding() {
        LoginResult r = API.parseLogin("{\"token\":\"abc\",\"tier\":\"free\",\"needsOnboarding\":true}");
        assertTrue(r.ok);
        assertTrue(r.needsOnboarding);
    }

    @Test public void parsesMe() {
        Me m = API.parseMe("{\"username\":\"alice\",\"tier\":\"standard\"}");
        assertEquals("alice", m.username);
        assertEquals("standard", m.tier);
        assertFalse(m.needsOnboarding);
        assertNull(API.parseMe("nope"));
    }

    @Test public void parsesMeNeedsOnboardingAndEmail() {
        Me m = API.parseMe("{\"username\":\"bob\",\"tier\":\"free\",\"email\":\"\",\"needsOnboarding\":true}");
        assertEquals("", m.email);
        assertTrue(m.needsOnboarding);
    }
}
