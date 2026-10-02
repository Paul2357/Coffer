package com.gecopilot.client.api;

/** DTO parsed from GET /api/activity-recap ("since you last opened" session banner). */
public class ActivityRecap {
    public long minutesAway;
    public int closedFlips;
    public long realizedGp;
    public int filledWhileAway;
}
