package com.gecopilot.client.api;

import java.util.ArrayList;
import java.util.List;

/** Client-side view of the wiki timeseries from GET /api/item/{id}, trimmed for the panel sparkline. */
public class SparklineDto {
    public int id;
    public String name;
    public List<Point> points = new ArrayList<>();

    public static class Point {
        public long ts;
        public long high, low;
        public double mid;
        /** Trade counts behind high/low; only the wiki timeseries fills these (0 otherwise). */
        public long highVol, lowVol;
    }
}
