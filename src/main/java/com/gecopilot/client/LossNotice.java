package com.gecopilot.client;

import com.gecopilot.client.api.PositionDto;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The in-client "sells at a loss" notification: once per item + price + advice, only for a loss of at
 * least 1% of cost or 50k (the server alert uses the same bar), and the same copy as the server alert.
 * In memory only; cleared when the client restarts.
 */
final class LossNotice {
    static final String ADVICE = "Sells at a loss";
    static final long LOSS_GP = 50_000;
    static final long LOSS_PCT = 1;
    private static final int MAX_KEYS = 500;
    private static final NumberFormat GP = NumberFormat.getIntegerInstance(Locale.US);

    private final Map<String, Boolean> sent = new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > MAX_KEYS; }
    };

    static boolean applies(PositionDto p) {
        return p != null && !p.buy && ADVICE.equals(p.advice);
    }

    /** The text to show, or null if this offer was already notified or the loss is too small. */
    synchronized String next(PositionDto p) {
        if (!applies(p)) return null;
        long loss = loss(p);
        // Full cost: the 1% / 50k bar. Partial cost: the server only says "Sells at a loss" once the
        // known units pass its stricter floor (50k, or >= 25% of the stack and >= 1%), so trust it.
        if (p.edge != null ? !material(loss, basis(p, loss)) : loss <= 0) return null;
        String key = p.id + ":" + p.price + ":" + p.advice;
        if (sent.put(key, Boolean.TRUE) != null) return null;
        return text(p.name, p.price, loss, p.target, p.edge == null ? p.knownQty : null);
    }

    /** Positive loss in gp: the whole-offer edge, or the known-units loss when cost is partial. */
    static long loss(PositionDto p) {
        Long e = p.edge != null ? p.edge : p.knownLoss;
        return e == null || e >= 0 ? 0 : -e;
    }

    /** Cost of the units the loss is measured on: units × ask after tax, plus the loss. */
    static long basis(PositionDto p, long loss) {
        int units = p.edge != null ? p.total : (p.knownQty != null ? p.knownQty : 0);
        return (long) units * (p.price - tax(p.price)) + loss;
    }

    static boolean material(long loss, long basis) {
        return loss > 0 && (loss >= LOSS_GP || (basis > 0 && loss * 100 >= basis * LOSS_PCT));
    }

    /** "Contract of Glyphic Attenuation sells at a loss at 157,196 (−1.70m). Breakeven 198,493." */
    static String text(String name, int price, long loss, Integer breakeven, Integer knownUnits) {
        return name + " sells at a loss at " + GP.format(price) + " (" + signedK(-loss)
            + (knownUnits != null ? " on " + knownUnits + " known unit" + (knownUnits == 1 ? "" : "s") : "") + ")."
            + (breakeven != null ? " Breakeven " + GP.format(breakeven) + "." : "");
    }

    static String signedK(long n) {
        long a = Math.abs(n);
        String sign = n < 0 ? "\u2212" : "+";
        if (a >= 1_000_000) return sign + String.format(Locale.ROOT, "%.2fm", a / 1_000_000.0);
        if (a >= 10_000) return sign + (a / 1_000) + "k";
        return sign + GP.format(a);
    }

    /** GE tax: 2% floored, capped at 5m, nothing under 50 gp. */
    static long tax(int price) {
        if (price < 50) return 0;
        return Math.min(5_000_000L, (long) Math.floor(price * 0.02));
    }
}
