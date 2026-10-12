package com.gecopilot.client.api;

/** DTO parsed from /api/dashboard. */
public class Dashboard {
    public long realized;
    public int flips;
    public String fillRate;
    public GoalDto goal;
    public Windows windows;
    /** Distinct RS accounts this user has traded under (for the account selector). */
    public java.util.List<String> accounts;

    /** Realized profit + flip count over a time window (bucketed by sell time). */
    public static class Win {
        public long gp;
        public int flips;
    }

    /** Session (since panel opened), 24h, 7d, and all-time windows. */
    public static class Windows {
        public Win session, d1, d7, all;
    }
}
