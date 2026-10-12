package com.gecopilot.client.ui;

import java.text.NumberFormat;

/**
 * Non-blocking check for the GE offer-setup screen.
 *
 * The prices are the ones the plugin already fetched for the overlay quote, not a new request:
 * {@code marketLow} is {@code quote.buy} (what buyers are paying now — hour average low, else latest
 * low) and {@code marketHigh} is {@code quote.sell} (hour average high, else latest high).
 *
 * A sell below that low would fill for less than buyers are paying right now. A buy above that
 * high would pay more than the current market high.
 */
public final class OfferPriceWarning {
    private static final NumberFormat GP = NumberFormat.getIntegerInstance();

    private OfferPriceWarning() {}

    public static final class Warning {
        public final boolean buy;
        public final int offerPrice;
        public final int marketPrice;
        public final int gapGp;
        public final double gapPct;

        Warning(boolean buy, int offerPrice, int marketPrice, int gapGp, double gapPct) {
            this.buy = buy;
            this.offerPrice = offerPrice;
            this.marketPrice = marketPrice;
            this.gapGp = gapGp;
            this.gapPct = gapPct;
        }

        public String headline() {
            return buy ? "Buy over market" : "Sell under buyers";
        }

        /** Gap in gp and percent, plus the market price it is measured against. */
        public String detail() {
            String pct = String.format("%.1f", gapPct);
            String relation = buy ? "over" : "under";
            return GP.format(gapGp) + " gp (" + pct + "%) " + relation + " " + GP.format(marketPrice);
        }
    }

    /**
     * After-tax market margin is too thin to flip. Same skip FlipEngine uses:
     * {@code margin = sell - buy - tax(sell)}, then drop the item when
     * {@code margin <= 0} or {@code margin < minMargin} or {@code marginPct < minRoi}
     * ({@code marginPct = margin * 100 / buy}). Thresholds are the user's flip
     * config (plugin defaults 10 gp and 0.3%), not new numbers.
     *
     * Buy setup only. A sell doesn't choose to enter a thin market.
     */
    public static final class ThinMargin {
        public final long marginGp;
        public final double marginPct;

        ThinMargin(long marginGp, double marginPct) {
            this.marginGp = marginGp;
            this.marginPct = marginPct;
        }

        public String headline() { return "Margin too thin to flip"; }

        /** After-tax margin in gp and percent. */
        public String detail() {
            return GP.format(marginGp) + " gp (" + String.format("%.1f", marginPct) + "%) after tax";
        }
    }

    /** Same rule as {@code com.gecopilot.engine.GeTax}: 2% floored, cap 5M, exempt under 50 gp. */
    static long tax(int price) {
        if (price < 50) return 0;
        return Math.min((long) Math.floor(price * 0.02), 5_000_000L);
    }

    public static ThinMargin evaluateMargin(boolean buy, int marketBuy, int marketSell, long minMargin, double minRoi) {
        if (!buy || marketBuy <= 0 || marketSell <= 0) return null;
        long margin = (long) marketSell - marketBuy - tax(marketSell);
        double marginPct = margin * 100.0 / marketBuy;
        if (margin > 0 && margin >= minMargin && marginPct >= minRoi) return null;
        return new ThinMargin(margin, marginPct);
    }

    public static Warning evaluate(boolean buy, int offerPrice, int marketLow, int marketHigh) {
        if (offerPrice <= 0) return null;
        if (!buy) {
            if (marketLow <= 0 || offerPrice >= marketLow) return null;
            int gap = marketLow - offerPrice;
            return new Warning(false, offerPrice, marketLow, gap, 100.0 * gap / marketLow);
        }
        if (marketHigh <= 0 || offerPrice <= marketHigh) return null;
        int gap = offerPrice - marketHigh;
        return new Warning(true, offerPrice, marketHigh, gap, 100.0 * gap / marketHigh);
    }
}
