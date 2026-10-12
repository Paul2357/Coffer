package com.gecopilot.client.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class InviteLinkTest {
    @Test public void buildsDashboardUrlFromUsername() {
        assertEquals("https://meetcoffer.com/dashboard?invite=ada", InviteLink.forUsername("ada"));
        assertNull(InviteLink.forUsername(""));
        assertNull(InviteLink.forUsername("a"));
        assertNull(InviteLink.forUsername("has space"));
        assertNull(InviteLink.forUsername(null));
    }
}
