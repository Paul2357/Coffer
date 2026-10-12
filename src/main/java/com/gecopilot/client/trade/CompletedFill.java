package com.gecopilot.client.trade;

public class CompletedFill {
    public final int itemId;
    public final boolean buy;
    public final int qty, price;
    public final long ts;
    // RS display name that made the fill, stamped by the plugin after capture (null if unknown, e.g. an
    // offline fill replayed before login). Serialized with the durable pending queue.
    public String account;
    // Stable identity of the terminal offer (slot:epoch:signature). The server ignores a second post
    // with the same key, so a replayed fill can't double-count even if the client dedup misses it.
    public String fillKey;

    public CompletedFill(int itemId, boolean buy, int qty, int price, long ts) {
        this.itemId = itemId;
        this.buy = buy;
        this.qty = qty;
        this.price = price;
        this.ts = ts;
    }
}
