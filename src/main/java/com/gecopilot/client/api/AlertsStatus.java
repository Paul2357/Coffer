package com.gecopilot.client.api;

public class AlertsStatus {
    public boolean webhookSet;
    public boolean enabled;
    /** True if the Discord webhook broke and the server auto-disabled alerts for this user. */
    public boolean webhookFailed;
    public boolean recover = true;
    public boolean position = true;
    public boolean crash = true;
    public int cooldownMin = 30;
    public boolean quietEnabled;
    public int quietStart;
    public int quietEnd;
    public String tz = "";
}
