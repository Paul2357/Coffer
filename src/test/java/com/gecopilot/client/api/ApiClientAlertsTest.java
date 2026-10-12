package com.gecopilot.client.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiClientAlertsTest {
    @Test public void buildsConfigBody() {
        String body = ApiClient.alertsConfigBody("https://discord.com/api/webhooks/abc", true, true, false, true, 15,
                false, 0, 0, "");
        assertTrue(body.contains("\"webhookUrl\":\"https://discord.com/api/webhooks/abc\""));
        assertTrue(body.contains("\"enabled\":true"));
        assertTrue(body.contains("\"recover\":true"));
        assertTrue(body.contains("\"position\":false"));
        assertTrue(body.contains("\"crash\":true"));
        assertTrue(body.contains("\"cooldownMin\":15"));
    }
}
