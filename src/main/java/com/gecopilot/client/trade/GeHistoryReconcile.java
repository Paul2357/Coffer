package com.gecopilot.client.trade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure reconcile step for GE History: given the trades read from the in-game History and the trades
 * already recorded, decide what to import and which recorded prices to correct.
 *
 * <p>Exact signature ({@code item:side:qty:unitPrice}) still wins, across the whole account, so a
 * repeated identical flip imports only the extra copies and a second open of History is a no-op.
 *
 * <p>A live fill often lands at the <em>offer</em> price ({@code spent == offer × qty} on the first
 * observation) while History shows the coins that actually moved. Those rows do not share a
 * signature, so the old count match imported History on top of the live row — the sell was counted
 * twice, and the live price stayed wrong. When exactly the same item, side, and qty is left over on
 * both sides and the prices are within {@link #PRICE_BAND_NUMER}/{@link #PRICE_BAND_DENOM} of each
 * other, History corrects the recorded price instead of inserting another trade. The GE History list
 * is only the last {@link #HISTORY_WINDOW} listings, so a correction may only touch a recorded trade
 * inside that newest window; an older flip of the same quantity is left alone and the History row
 * is imported.
 */
public final class GeHistoryReconcile {
    private GeHistoryReconcile() {}

    /** In-game History keeps the last 40 listings (since the 2019 expansion). */
    static final int HISTORY_WINDOW = 40;
    /** Prices within 25% of the higher one can be the same fill (offer vs actual). */
    static final int PRICE_BAND_NUMER = 1;
    static final int PRICE_BAND_DENOM = 4;

    /** A raw recorded trade from GET /api/trades/mine (server's source of truth). */
    public static final class Recorded {
        public int itemId; public boolean buy; public int qty; public long price;
        /** Epoch ms. 0 when the server didn't send one — then every row is "recent". */
        public long ts;
    }

    /** History's unit price replaces a recorded offer price on the same item/side/qty. */
    public static final class Correction {
        public final int itemId;
        public final boolean buy;
        public final int qty;
        public final long fromPrice;
        public final long toPrice;

        public Correction(int itemId, boolean buy, int qty, long fromPrice, long toPrice) {
            this.itemId = itemId; this.buy = buy; this.qty = qty;
            this.fromPrice = fromPrice; this.toPrice = toPrice;
        }
    }

    /** What to do with one History read. Both lists may be empty (already in sync). */
    public static final class Plan {
        public final List<GeHistoryRow> imports;
        public final List<Correction> corrections;

        public Plan(List<GeHistoryRow> imports, List<Correction> corrections) {
            this.imports = imports;
            this.corrections = corrections;
        }
    }

    private static String sig(int itemId, boolean buy, int qty, long price) {
        return itemId + ":" + (buy ? "B" : "S") + ":" + qty + ":" + price;
    }

    private static String bucket(int itemId, boolean buy, int qty) {
        return itemId + ":" + (buy ? "B" : "S") + ":" + qty;
    }

    /** Gap is at most a quarter of the higher price (about 33% above the lower). */
    static boolean withinBand(long a, long b) {
        if (a <= 0 || b <= 0) return false;
        long max = Math.max(a, b);
        long diff = Math.abs((long) a - b);
        return diff * PRICE_BAND_DENOM <= max * PRICE_BAND_NUMER;
    }

    /** History rows that are not already covered by recorded trades, preserving History order. */
    public static List<GeHistoryRow> missing(List<GeHistoryRow> history, List<Recorded> recorded) {
        return plan(history, recorded).imports;
    }

    public static Plan plan(List<GeHistoryRow> history, List<Recorded> recorded) {
        List<Recorded> all = recorded == null ? new ArrayList<Recorded>() : recorded;
        Map<String, ArrayDeque<Recorded>> exact = new HashMap<>();
        for (Recorded r : all) exact.computeIfAbsent(sig(r.itemId, r.buy, r.qty, r.price), k -> new ArrayDeque<>()).add(r);

        List<GeHistoryRow> unmatched = new ArrayList<>();
        Set<Recorded> exactConsumed = new HashSet<>();
        if (history != null) {
            for (GeHistoryRow h : history) {
                ArrayDeque<Recorded> q = exact.get(sig(h.itemId, h.buy, h.qty, h.unitPrice));
                if (q != null && !q.isEmpty()) exactConsumed.add(q.removeFirst());
                else unmatched.add(h);
            }
        }

        List<Recorded> recent = recentWindow(all);
        Map<String, List<Recorded>> recBy = new HashMap<>();
        for (Recorded r : recent) {
            if (exactConsumed.contains(r)) continue;
            recBy.computeIfAbsent(bucket(r.itemId, r.buy, r.qty), k -> new ArrayList<>()).add(r);
        }
        Map<String, List<GeHistoryRow>> histBy = new HashMap<>();
        for (GeHistoryRow h : unmatched) histBy.computeIfAbsent(bucket(h.itemId, h.buy, h.qty), k -> new ArrayList<>()).add(h);

        Set<GeHistoryRow> paired = new HashSet<>();
        List<Correction> corrections = new ArrayList<>();
        for (Map.Entry<String, List<GeHistoryRow>> e : histBy.entrySet()) {
            List<GeHistoryRow> hs = e.getValue();
            List<Recorded> rs = recBy.containsKey(e.getKey()) ? recBy.get(e.getKey()) : new ArrayList<Recorded>();
            boolean[] tookH = new boolean[hs.size()];
            boolean[] usedR = new boolean[rs.size()];
            while (true) {
                int bi = -1, bj = -1;
                long best = Long.MAX_VALUE;
                for (int i = 0; i < hs.size(); i++) {
                    if (tookH[i]) continue;
                    for (int j = 0; j < rs.size(); j++) {
                        if (usedR[j]) continue;
                        if (!withinBand(hs.get(i).unitPrice, rs.get(j).price)) continue;
                        long diff = Math.abs(hs.get(i).unitPrice - rs.get(j).price);
                        if (diff < best) { best = diff; bi = i; bj = j; }
                    }
                }
                if (bi < 0) break;
                tookH[bi] = true;
                usedR[bj] = true;
                GeHistoryRow h = hs.get(bi);
                Recorded r = rs.get(bj);
                paired.add(h);
                if (r.price != h.unitPrice) corrections.add(new Correction(h.itemId, h.buy, h.qty, r.price, h.unitPrice));
            }
        }

        List<GeHistoryRow> imports = new ArrayList<>();
        for (GeHistoryRow h : unmatched) if (!paired.contains(h)) imports.add(h);
        return new Plan(imports, corrections);
    }

    /** Newest {@link #HISTORY_WINDOW} recorded trades. Without timestamps, keep the whole list. */
    private static List<Recorded> recentWindow(List<Recorded> all) {
        boolean anyTs = false;
        for (Recorded r : all) if (r.ts > 0) { anyTs = true; break; }
        if (!anyTs || all.size() <= HISTORY_WINDOW) return all;
        List<Recorded> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Long.compare(b.ts, a.ts));
        return new ArrayList<>(sorted.subList(0, HISTORY_WINDOW));
    }

    /** Banner copy for a non-empty plan. */
    public static String prompt(Plan plan) {
        int nImp = plan.imports.size();
        int nCor = plan.corrections.size();
        if (nImp > 0 && nCor > 0) {
            return nImp + " missed, " + nCor + " price" + (nCor == 1 ? "" : "s") + " to fix";
        }
        if (nCor > 0) {
            return nCor + " trade price" + (nCor == 1 ? "" : "s") + " don't match GE History";
        }
        return nImp + " GE History trade" + (nImp == 1 ? "" : "s") + " not recorded";
    }
}
