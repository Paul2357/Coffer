package com.gecopilot.client.api;

/** DTO parsed from /api/flips. Field names match the server JSON. */
public class CloudFlip {
    public int id;
    public String name;
    public int buyPrice, sellPrice;
    public long margin;
    public double marginPct;
    public int canBuy;
    public long cycleProfit;
    public double fillProb;
    public int placeBuy, placeSell;
    public String risk;        // LOW | MED | HIGH safety grade
    public String riskReason;  // one-line worst contributor
    public Integer confidence; // Premium: backtested margin-held %, null when absent
    public Integer crowdFillPct;  // Premium: crowd-observed fill rate for this pick's buy bucket
    public Integer crowdSamples;  // sample count behind crowdFillPct
    public Integer crowdCount;    // Premium: other users currently buying this (0 = clear)
    public int limit;             // 4h GE buy limit (0 = unknown)
    public int limitLeft;         // remaining this window
}
