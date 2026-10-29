package com.gecopilot.client.trade;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JSON for the per-account open-offer map. A list, not a map, so slot keys survive Gson. */
public final class OpenOfferPersistence {
    private OpenOfferPersistence() {}

    public static final class Row {
        public int slot;
        public int itemId;
        public boolean buy;
        public int sold, total;
        public long price;
        public long openedAt;
        public double[] features;
        public int fv;
    }

    public static String toJson(Gson gson, Map<Integer, OpenOfferDto> open) {
        List<Row> rows = new ArrayList<>();
        if (open != null) {
            for (Map.Entry<Integer, OpenOfferDto> e : open.entrySet()) {
                OpenOfferDto o = e.getValue();
                if (e.getKey() == null || o == null) continue;
                Row r = new Row();
                r.slot = e.getKey();
                r.itemId = o.itemId;
                r.buy = o.buy;
                r.sold = o.sold;
                r.total = o.total;
                r.price = o.price;
                r.openedAt = o.openedAt;
                r.features = o.features;
                r.fv = o.fv;
                rows.add(r);
            }
        }
        return gson.toJson(rows);
    }

    public static Map<Integer, OpenOfferDto> fromJson(Gson gson, String json) {
        Map<Integer, OpenOfferDto> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            Row[] rows = gson.fromJson(json, Row[].class);
            if (rows == null) return out;
            for (Row r : rows) {
                if (r == null) continue;
                OpenOfferDto o = new OpenOfferDto(r.itemId, r.buy, r.sold, r.total, r.price, r.openedAt);
                o.features = r.features;
                o.fv = r.fv;
                out.put(r.slot, o);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
