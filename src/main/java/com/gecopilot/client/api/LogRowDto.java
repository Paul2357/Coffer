package com.gecopilot.client.api;

public class LogRowDto {
    public int id; public String name;
    public int qty, buyAvg, sellAvg;
    public long profit, timeMs;
    /** Sell with no recorded buy. buyAvg is 0 and profit is 0; the row is still shown. */
    public boolean unmatched;
}
