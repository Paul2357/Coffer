package com.gecopilot.client.api;

public class AlertsStatus {
    public boolean webhookSet;
    public boolean enabled;
    public boolean recover = true;
    public boolean position = true;
    public boolean crash = true;
    public int cooldownMin = 30;
    public boolean quietEnabled;
    public int quietStart;
    public int quietEnd;
    public String tz = "";
}
