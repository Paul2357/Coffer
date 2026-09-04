package com.gecopilot.client.api;

public class LoginResult {
    public boolean ok;
    public String token;
    public String tier;
    public boolean reached;   // did we get any HTTP response (vs timeout/unreachable)
    public int code;          // HTTP status when reached (401 = bad creds; 5xx = server issue)
}
