package com.gecopilot.client.ui;

/** The offer chart's tabs, each mapped to the OSRS Wiki timeseries timestep that covers it. */
public enum OfferChartRange {
    DAY("1d", "5m", 5L * 60, 24L * 60 * 60),
    WEEK("1w", "1h", 60L * 60, 7L * 24 * 60 * 60),
    MONTH("1m", "6h", 6L * 60 * 60, 30L * 24 * 60 * 60),
    YEAR("1y", "24h", 24L * 60 * 60, 365L * 24 * 60 * 60);

    public final String label;
    public final String timestep;
    public final long stepSec;
    public final long windowSec;

    OfferChartRange(String label, String timestep, long stepSec, long windowSec) {
        this.label = label;
        this.timestep = timestep;
        this.stepSec = stepSec;
        this.windowSec = windowSec;
    }
}
