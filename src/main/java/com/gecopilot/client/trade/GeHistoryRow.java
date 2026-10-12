package com.gecopilot.client.trade;

/** One completed trade read from the in-game GE History interface. */
public class GeHistoryRow {
    public final int itemId;
    public final boolean buy;
    public final int qty;
    public final int unitPrice; // total coins / qty

    public GeHistoryRow(int itemId, boolean buy, int qty, int unitPrice) {
        this.itemId = itemId; this.buy = buy; this.qty = qty; this.unitPrice = unitPrice;
    }
}
