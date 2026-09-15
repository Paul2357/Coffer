package com.gecopilot.client.api;

/** Result of POST /auth/reset. */
public class ResetResult {
    public boolean ok;
    public boolean reached;   // did we get any HTTP response (vs timeout/unreachable)
    public int code;          // HTTP status when reached (400 = bad/expired token/password; 429 = throttled)
    public String error;      // server-provided error message, when present
}
