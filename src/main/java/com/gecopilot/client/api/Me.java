package com.gecopilot.client.api;

public class Me {
    public String username;
    public String tier;
    public String email;
    // Mandatory account-setup gate (Iris/Lex): true if this account is missing a recovery email
    // or ToS assent and must complete the gate before the plugin shows the main panel.
    public boolean needsOnboarding;
    /** Epoch ms from /api/me. 0 until that stage has happened. */
    public long pluginSeenAt;
    public long offersSeenAt;
}
