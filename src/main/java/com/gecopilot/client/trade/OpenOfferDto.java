package com.gecopilot.client.trade;

public class OpenOfferDto {
    public int itemId;
    public boolean buy;
    public int sold, total;
    public long price;
    public long openedAt;
    /** Market features at placement. Null until the server answers /api/fill-features. */
    public double[] features;
    public int fv;

    public OpenOfferDto() {}

    public OpenOfferDto(int itemId, boolean buy, int sold, int total, long price, long openedAt) {
        this.itemId = itemId; this.buy = buy; this.sold = sold;
        this.total = total; this.price = price; this.openedAt = openedAt;
    }
}
