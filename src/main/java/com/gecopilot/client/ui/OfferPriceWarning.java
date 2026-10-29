package com.gecopilot.client.ui;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
        public final long offerPrice;
        public final long marketPrice;
        public final long gapGp;
        public final double gapPct;

        Warning(boolean buy, long offerPrice, long marketPrice, long gapGp, double gapPct) {
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
        /** " after tax", " (no GE tax)" or " after 10% bond fee". */
        final String note;

        ThinMargin(long marginGp, double marginPct) { this(marginGp, marginPct, " after tax"); }

        ThinMargin(long marginGp, double marginPct, String note) {
            this.marginGp = marginGp;
            this.marginPct = marginPct;
            this.note = note;
        }

        public String headline() { return "Margin too thin to flip"; }

        /** After-tax margin in gp and percent. */
        public String detail() {
            return GP.format(marginGp) + " gp (" + String.format("%.1f", marginPct) + "%)" + note;
        }
    }

    /**
     * Items the GE charges no tax on. Same list as the server's {@code com.gecopilot.engine.GeTax},
     * from https://oldschool.runescape.wiki/w/Grand_Exchange#Exempt_from_tax
     */
    private static final Set<Integer> TAX_EXEMPT = new HashSet<>(Arrays.asList(
        13190, 3008, 3010, 3012, 3014, 882, 806, 884, 807, 558, 886, 808,
        365, 2309, 1891, 2140, 2142, 347, 379, 355, 2327, 351, 329, 315, 361,
        8011, 8010, 28824, 8009, 3853, 28790, 8008, 2552, 8013, 8007,
        1755, 5325, 1785, 2347, 1733, 233, 5341, 8794, 5329, 5343, 1735, 952, 5331));

    /** Old school bond (tradeable). */
    public static final int BOND = 13190;

    public static boolean taxExempt(int itemId) { return TAX_EXEMPT.contains(itemId); }

    /** Same rule as {@code com.gecopilot.engine.GeTax}: 0 for exempt items, else 2% floored, cap 5M, nothing under 50 gp. */
    public static long tax(int itemId, long price) {
        if (price < 50 || TAX_EXEMPT.contains(itemId)) return 0;
        return Math.min(price / 50, 5_000_000L);
    }

    /**
     * A GE-bought bond is untradeable; converting it back costs 10% of its GE value, rounded half up
     * (https://oldschool.runescape.wiki/w/Old_school_bond). Paid once, on the buy ({@link #buyCost}).
     * Same rule as the server's GeTax.
     */
    public static long conversionFee(int itemId, long price) {
        return itemId == BOND && price > 0 ? (price + 5) / 10 : 0;
    }

    /** Label for a buy-then-sell margin: " after tax", " (no GE tax)", or " after 10% bond fee". */
    public static String marginNote(int itemId) {
        if (itemId == BOND) return " after 10% bond fee";
        return taxExempt(itemId) ? " (no GE tax)" : " after tax";
    }

    /** What selling costs: the GE tax. */
    public static long saleCost(int itemId, long price) {
        return tax(itemId, price);
    }

    /** What a GE buy really costs per unit: the price, plus the conversion fee for a bond. */
    public static long buyCost(int itemId, long price) {
        return price + conversionFee(itemId, price);
    }

    /** Per-unit margin of buying at {@code buy} and selling at {@code sell}: fee on the buy, tax on the sale. */
    public static long flipMargin(int itemId, long buy, long sell) {
        return sell - saleCost(itemId, sell) - buyCost(itemId, buy);
    }

    public static ThinMargin evaluateMargin(int itemId, boolean buy, long marketBuy, long marketSell, long minMargin, double minRoi) {
        if (!buy || marketBuy <= 0 || marketSell <= 0) return null;
        long margin = flipMargin(itemId, marketBuy, marketSell);
        double marginPct = margin * 100.0 / marketBuy;
        if (margin > 0 && margin >= minMargin && marginPct >= minRoi) return null;
        return new ThinMargin(margin, marginPct, marginNote(itemId));
    }

    public static Warning evaluate(boolean buy, long offerPrice, long marketLow, long marketHigh) {
        if (offerPrice <= 0) return null;
        if (!buy) {
            if (marketLow <= 0 || offerPrice >= marketLow) return null;
            long gap = marketLow - offerPrice;
            return new Warning(false, offerPrice, marketLow, gap, 100.0 * gap / marketLow);
        }
        if (marketHigh <= 0 || offerPrice <= marketHigh) return null;
        long gap = offerPrice - marketHigh;
        return new Warning(true, offerPrice, marketHigh, gap, 100.0 * gap / marketHigh);
    }
}
