package com.gecopilot.client.trade;

public class OpenOfferDto {
    public int itemId;
    public boolean buy;
    public int sold, total;
    public long price;
    public long openedAt;
    /** Last time this client saw the offer move (placed or a fill). Transient: local slot timer only,
     *  not part of the /api/flips payload. 0 = unknown, read as openedAt. */
    public transient long movedAt;
    /** Market features at placement. Null until the server answers /api/fill-features. */
    public double[] features;
    public int fv;

    public OpenOfferDto() {}

    public OpenOfferDto(int itemId, boolean buy, int sold, int total, long price, long openedAt) {
        this.itemId = itemId; this.buy = buy; this.sold = sold;
        this.total = total; this.price = price; this.openedAt = openedAt;
        this.movedAt = openedAt;
    }

    /** When the offer last moved, falling back to when it was placed. */
    public long lastMovedAt() { return movedAt > 0 ? movedAt : openedAt; }
}
