package com.gecopilot.client.api;

public class LoginResult {
    public boolean ok;
    public String token;
    public String tier;
    public boolean reached;   // did we get any HTTP response (vs timeout/unreachable)
    public int code;          // HTTP status when reached (401 = bad creds; 5xx = server issue)
    // Mandatory account-setup gate (Iris/Lex): true if this account is missing a recovery email
    // or ToS assent and must complete the gate before the plugin shows the main panel.
    public boolean needsOnboarding;
}
