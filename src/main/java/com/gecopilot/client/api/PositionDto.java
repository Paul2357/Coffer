package com.gecopilot.client.api;

public class PositionDto {
    public int id; public String name; public boolean buy;
    public int sold, total, price; public Long edge; public int openMin; // edge null = hide the row
    public String advice; public boolean warn;
    public Integer target; public String targetLabel; public Long alchProfit;
    public String verdict; public String confidence;
    /** Server 0.1.32+: sell whose cost is not fully known; knownLoss is the loss the known units lock in (never a gain). */
    public boolean partialCost; public Integer knownQty; public Long knownLoss;
    public Integer avgDownQty; public Integer avgDownPrice; public Integer avgDownBreakeven;
}
