package com.gecopilot.client.api;

/** GET /api/status. Every field may be null; {@code db} is the server's last known database reachability. */
public class ServiceStatusDto {
    public String level;   // info | warning | outage | null
    public String message;
    public String link;    // https only (checked again here)
    public String since;   // ISO-8601 instant
    public Boolean db;
}
