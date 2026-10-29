package com.gecopilot.client.trade;

public class CompletedFill implements OwnedItem {
    public final int itemId;
    public final boolean buy;
    public final int qty;
    public final long price;
    public final long ts;
    // RS display name that made the fill, stamped by the plugin after capture (null if unknown, e.g. an
    // offline fill replayed before login). Serialized with the durable pending queue.
    public String account;
    // Stable identity of the terminal offer (slot:epoch:signature). The server ignores a second post
    // with the same key, so a replayed fill can't double-count even if the client dedup misses it.
    public String fillKey;
    // Coffer user this fill belongs to (see QueueOwnership). Null in queues saved by older builds.
    public Long ownerId;

    public CompletedFill(int itemId, boolean buy, int qty, long price, long ts) {
        this.itemId = itemId;
        this.buy = buy;
        this.qty = qty;
        this.price = price;
        this.ts = ts;
    }

    @Override public Long getOwnerId() { return ownerId; }
    @Override public void setOwnerId(Long id) { ownerId = id; }
    @Override public long getTs() { return ts; }
}
