package com.gecopilot.client.trade;

public class Outcome {
    public final int itemId;
    public final boolean buy;
    public final int price, sold, total;
    public final long waitedMs;
    /** Placement features captured when the offer opened. Null if the plugin never fetched them. */
    public final double[] features;
    public final int featureVersion;

    public Outcome(int itemId, boolean buy, int price, int sold, int total, long waitedMs) {
        this(itemId, buy, price, sold, total, waitedMs, null, 0);
    }

    public Outcome(int itemId, boolean buy, int price, int sold, int total, long waitedMs,
                   double[] features, int featureVersion) {
        this.itemId = itemId; this.buy = buy; this.price = price;
        this.sold = sold; this.total = total; this.waitedMs = waitedMs;
        this.features = features; this.featureVersion = featureVersion;
    }
}
