package com.gecopilot.client.trade;

/** One GE offer lifecycle event to post. Public fields so the pending queue round-trips through Gson. */
public class OfferEvent implements OwnedItem {
    public String kind; // placed, cancelled, completed
    public int itemId;
    public boolean buy;
    public long price;
    public int qty;
    public int filled;
    public int slot;
    public long ts;
    public Long ownerId; // Coffer user (see QueueOwnership); null in queues saved by older builds

    public OfferEvent() {}

    public OfferEvent(String kind, int itemId, boolean buy, long price, int qty, int filled, int slot, long ts) {
        this.kind = kind;
        this.itemId = itemId;
        this.buy = buy;
        this.price = price;
        this.qty = qty;
        this.filled = filled;
        this.slot = slot;
        this.ts = ts;
    }

    @Override public Long getOwnerId() { return ownerId; }
    @Override public void setOwnerId(Long id) { ownerId = id; }
    @Override public long getTs() { return ts; }
}
