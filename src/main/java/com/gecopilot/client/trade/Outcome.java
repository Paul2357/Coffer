package com.gecopilot.client.trade;

public class Outcome {
    public final int itemId;
    public final boolean buy;
    public final int price, sold, total;
    public final long waitedMs;
    public Outcome(int itemId, boolean buy, int price, int sold, int total, long waitedMs) {
        this.itemId = itemId; this.buy = buy; this.price = price;
        this.sold = sold; this.total = total; this.waitedMs = waitedMs;
    }
}
