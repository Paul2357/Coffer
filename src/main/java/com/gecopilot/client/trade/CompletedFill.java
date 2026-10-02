package com.gecopilot.client.trade;

public class CompletedFill {
    public final int itemId;
    public final boolean buy;
    public final int qty, price;
    public final long ts;
    // RS display name that made the fill, stamped by the plugin after capture (null if unknown, e.g. an
    // offline fill replayed before login). Serialized with the durable pending queue.
    public String account;

    public CompletedFill(int itemId, boolean buy, int qty, int price, long ts) {
        this.itemId = itemId;
        this.buy = buy;
        this.qty = qty;
        this.price = price;
        this.ts = ts;
    }
}
