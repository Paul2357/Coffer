package com.gecopilot.client.ui;

/**
 * Buddy invite. The code is the inviter's username (already unique and URL-safe).
 * Friends open the web signup; the server stores the inviter when the code matches
 * an existing account. No rewards and no outbound messaging.
 */
public final class InviteLink {
    public static final String PREFIX = "https://meetcoffer.com/dashboard?invite=";

    private InviteLink() {}

    public static String forUsername(String username) {
        if (username == null || !username.matches("^[A-Za-z0-9_]{3,20}$")) return null;
        return PREFIX + username;
    }
}
