package com.gecopilot.client.trade;

public class OpenOfferDto {
    public int itemId;
    public boolean buy;
    public int sold, total, price;
    public long openedAt;

    public OpenOfferDto(int itemId, boolean buy, int sold, int total, int price, long openedAt) {
        this.itemId = itemId; this.buy = buy; this.sold = sold;
        this.total = total; this.price = price; this.openedAt = openedAt;
    }
}
