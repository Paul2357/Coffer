package com.gecopilot.client.trade;

/** One GE offer lifecycle event to post. Public fields so the pending queue round-trips through Gson. */
public class OfferEvent {
    public String kind; // placed, cancelled, completed
    public int itemId;
    public boolean buy;
    public int price;
    public int qty;
    public int filled;
    public int slot;
    public long ts;

    public OfferEvent() {}

    public OfferEvent(String kind, int itemId, boolean buy, int price, int qty, int filled, int slot, long ts) {
        this.kind = kind;
        this.itemId = itemId;
        this.buy = buy;
        this.price = price;
        this.qty = qty;
        this.filled = filled;
        this.slot = slot;
        this.ts = ts;
    }
}
