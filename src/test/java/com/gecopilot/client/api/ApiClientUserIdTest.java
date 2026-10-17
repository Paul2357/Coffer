package com.gecopilot.client.api;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

public class ApiClientUserIdTest {
    private static String jwt(String payload) {
        Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        return b.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + b.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test public void userIdIsTheTokenSubject() {
        ApiClient api = new ApiClient(null, new com.google.gson.Gson(), "http://x");
        assertEquals(Long.valueOf(42), api.subjectOf(jwt("{\"sub\":\"42\",\"tier\":\"premium\"}")));
        assertNull(api.subjectOf(jwt("{\"tier\":\"premium\"}")));
        assertNull(api.subjectOf("not-a-jwt"));
        assertNull(api.subjectOf(null));
        assertNull(api.userId());
        api.setToken(jwt("{\"sub\":\"9\"}"));
        assertEquals(Long.valueOf(9), api.userId());
        api.signOut(null);
        assertNull(api.userId());
    }
}
