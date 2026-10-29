package com.gecopilot.client.api;

public class WatchDto {
    public int id; public String name; public long buy, sell; public long margin; public long buyCost, breakeven; public boolean sellableNow;
    /** Server 0.1.32+: units known held vs units with a recorded cost. Older servers send neither (0/false). */
    public int heldQty, trackedQty; public boolean partialCost;
    /** Server 0.1.32+: average over the known units. With partialCost the server sends buyCost=0 so older plugins hide the cost block. */
    public long knownCost;
}
