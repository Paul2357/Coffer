package com.gecopilot.client.ui;

import com.gecopilot.client.api.QuoteDto;
import com.gecopilot.client.api.SparklineDto;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Paints the GE offer price chart: range tabs, instabuy/instasell lines with a latest-price legend,
 * the player's offer price, and Coffer's suggested buy/sell with the margin between them.
 * Stateless so the overlay and a headless test draw exactly the same thing.
 */
public final class OfferChart {
    private OfferChart() {}

    static final Color INK = new Color(0x0d, 0x0f, 0x14);
    /** Opaque: at any lower alpha the chat text shows through in game. */
    static final Color BG = new Color(0x0d, 0x0f, 0x14);
    static final Color TIP_BORDER = new Color(0x3a, 0x40, 0x4c);
    static final Color CROSSHAIR = new Color(255, 255, 255, 110);
    static final Color BORDER = new Color(0x5a, 0x52, 0x41);
    static final Color GRID = new Color(255, 255, 255, 28);
    static final Color AXIS_TEXT = new Color(0xc8, 0xc8, 0xc8);
    static final Color INSTABUY = new Color(0x7e, 0xe7, 0x87);
    static final Color INSTASELL = new Color(0xe5, 0x48, 0x4d);
    static final Color INSTASELL_TEXT = new Color(0xff, 0x7b, 0x72);
    static final Color OFFER = new Color(0xf2, 0xef, 0xe6);
    static final Color COFFER = new Color(0x4f, 0xc3, 0xf7);
    static final Color WARN = new Color(0xff, 0xb0, 0x20);
    static final Color BAND_GOOD = new Color(0x4f, 0xc3, 0xf7, 48);
    static final Color BAND_BAD = new Color(0xf8, 0x51, 0x49, 64);
    static final Color TAB_ON = new Color(0x4f, 0xc3, 0xf7, 220);
    static final Color TAB_HOVER = new Color(95, 95, 95, 220);
    static final Color TAB_OFF = new Color(60, 60, 60, 200);
    static final int DIM_ALPHA = CloudPanel.DIM_ALPHA;

    static final int PAD = 4;
    static final int TAB_W = 26;
    static final int TAB_H = 16;
    static final int TAB_GAP = 3;
    static final int HEADER_H = 26;
    static final int AXIS_W = 40;
    static final int LABEL_GAP = 11;
    static final int MAX_TICKS = 20;
    static final String OFF_CHART = "(off chart)";

    private static final Stroke LINE = new BasicStroke(1.2f);
    private static final Stroke OFFER_DASH = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f);
    private static final Stroke COFFER_DASH = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{7f, 3f}, 0f);

    /** Everything one frame needs. {@code points} may be null while the first load is in flight. */
    public static final class Model {
        public OfferChartRange range = OfferChartRange.DAY;
        public List<SparklineDto.Point> points;
        public long nowSec;
        public long offerPrice;
        public QuoteDto quote;
        /** The last refresh failed; {@code points} (if any) are from an earlier load. */
        public boolean failed;
        /** Seconds since {@code points} were loaded; shown in the stale note. */
        public long dataAgeSec;
        public int hoverTab = -1;
        /** Mouse position (canvas coords) for the crosshair tooltip, or null. */
        public Point hover;
        public ZoneId zone = ZoneId.systemDefault();
    }

    /** A horizontal price line with an inline label. */
    static final class Marker {
        final long price;
        final String name;
        final Color color;
        final Stroke stroke;
        int lineY;
        int labelY;
        boolean offChart;

        Marker(long price, String name, Color color, Stroke stroke) {
            this.price = price;
            this.name = name;
            this.color = color;
            this.stroke = stroke;
        }

        String label() { return name + " " + gp(price) + (offChart ? " " + OFF_CHART : ""); }
    }

    /** Draws the chart into {@code area} and returns the tab hit boxes, in {@link OfferChartRange} order. */
    public static Rectangle[] paint(Graphics2D g, Rectangle area, Model m, Font font) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        g.setComposite(AlphaComposite.SrcOver); // never inherit a reduced alpha for the background
        g.setColor(BG);
        g.fillRect(area.x, area.y, area.width, area.height);
        g.setColor(BORDER);
        g.drawRect(area.x, area.y, area.width - 1, area.height - 1);

        Rectangle[] tabs = drawTabs(g, fm, area, m.range, m.hoverTab);

        List<SparklineDto.Point> pts = window(m.points, m.range, m.nowSec);
        boolean stale = m.failed && !pts.isEmpty();
        drawLegend(g, fm, area, pts, m.nowSec, stale);
        if (stale) {
            g.setColor(WARN);
            Rectangle last = tabs[tabs.length - 1];
            g.drawString(staleNote(m.dataAgeSec, font), last.x + last.width + 8, last.y + 13);
        }

        Rectangle plot = new Rectangle(area.x + PAD + AXIS_W, area.y + HEADER_H + PAD,
            area.width - 2 * PAD - AXIS_W - 2, area.height - HEADER_H - 2 * PAD - 2);
        if (plot.width < 20 || plot.height < 20) return tabs;
        if (pts.isEmpty()) {
            String msg = m.points == null ? "Loading prices..." : (m.failed ? "Couldn't load prices" : "No recent trades");
            g.setColor(AXIS_TEXT);
            g.drawString(msg, plot.x + (plot.width - fm.stringWidth(msg)) / 2, plot.y + plot.height / 2);
            return tabs;
        }

        long[] range = priceRange(pts, m.quote);
        long lo = range[0], hi = range[1];
        long t0 = m.nowSec - m.range.windowSec, t1 = Math.max(m.nowSec, pts.get(pts.size() - 1).ts);

        drawAxis(g, fm, plot, lo, hi);

        QuoteDto q = m.quote;
        boolean hasCoffer = q != null && q.placeBuy > 0 && q.placeSell > 0;
        List<Marker> markers = new ArrayList<>();
        if (hasCoffer) {
            markers.add(new Marker(q.placeSell, "Coffer sell", COFFER, COFFER_DASH));
            markers.add(new Marker(q.placeBuy, "Coffer buy", COFFER, COFFER_DASH));
        }
        if (m.offerPrice > 0) markers.add(new Marker(m.offerPrice, "Your offer", OFFER, OFFER_DASH));
        for (Marker mk : markers) {
            mk.offChart = mk.price < lo || mk.price > hi;
            mk.lineY = mk.price < lo ? plot.y + plot.height : mk.price > hi ? plot.y : y(plot, mk.price, lo, hi);
        }

        Shape clip = g.getClip();
        g.clipRect(plot.x, plot.y, plot.width + 1, plot.height + 1);
        if (hasCoffer && q.placeSell > q.placeBuy) {
            int yTop = y(plot, Math.min(q.placeSell, hi), lo, hi), yBot = y(plot, Math.max(q.placeBuy, lo), lo, hi);
            if (yBot > yTop) {
                g.setColor(q.margin > 0 ? BAND_GOOD : BAND_BAD);
                g.fillRect(plot.x, yTop, plot.width, yBot - yTop);
            }
        }
        g.setStroke(LINE);
        long maxGap = m.range.stepSec * GAP_STEPS;
        drawSeries(g, plot, pts, false, t0, t1, lo, hi, stale, maxGap);
        drawSeries(g, plot, pts, true, t0, t1, lo, hi, stale, maxGap);
        for (Marker mk : markers) {
            g.setColor(mk.color);
            g.setStroke(mk.stroke);
            g.drawLine(plot.x, mk.lineY, plot.x + plot.width, mk.lineY);
        }
        g.setStroke(new BasicStroke(1f));
        g.setClip(clip);
        layoutLabels(markers, plot.y + 9, plot.y + plot.height);
        for (Marker mk : markers) shadowText(g, mk.label(), plot.x + 3, mk.labelY, mk.color);
        if (m.hover != null && plot.contains(m.hover)) drawHover(g, fm, area, plot, pts, m, t0, t1, lo, hi);
        return tabs;
    }

    private static final DateTimeFormatter TIP_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US);

    /** Crosshair at the point nearest the mouse, plus a tooltip kept inside the chart. */
    private static void drawHover(Graphics2D g, FontMetrics fm, Rectangle area, Rectangle plot, List<SparklineDto.Point> pts,
                                  Model m, long t0, long t1, long lo, long hi) {
        SparklineDto.Point p = nearest(pts, plot, m.hover.x, t0, t1);
        if (p == null) return;
        int px = (int) Math.round(x(plot, p.ts, t0, t1));
        g.setColor(CROSSHAIR);
        g.drawLine(px, plot.y, px, plot.y + plot.height);
        if (p.high > 0) dot(g, px, y(plot, p.high, lo, hi), INSTABUY);
        if (p.low > 0) dot(g, px, y(plot, p.low, lo, hi), INSTASELL);

        List<String> lines = tooltipLines(p, m.zone);
        int lineH = 11, w = 0;
        for (String s : lines) w = Math.max(w, fm.stringWidth(s));
        Rectangle box = placeTooltip(new Point(px, m.hover.y), w + 8, lines.size() * lineH + 6, belowHeader(area));
        g.setColor(BG);
        g.fillRect(box.x, box.y, box.width, box.height);
        g.setColor(TIP_BORDER);
        g.drawRect(box.x, box.y, box.width - 1, box.height - 1);
        Color[] colors = {AXIS_TEXT, INSTABUY, INSTASELL_TEXT, Color.WHITE, AXIS_TEXT};
        for (int i = 0; i < lines.size(); i++) {
            g.setColor(colors[Math.min(i, colors.length - 1)]);
            g.drawString(lines.get(i), box.x + 4, box.y + 3 + (i + 1) * lineH - 2);
        }
    }

    private static void dot(Graphics2D g, int x, int y, Color c) {
        g.setColor(c);
        g.fillRect(x - 1, y - 1, 3, 3);
    }

    /**
     * Same fields as the web dashboard's price-chart tooltip (time, Buy, Sell), plus the after-tax
     * margin and the volumes the dashboard shows in its volume chart. Buy = instabuy, Sell = instasell.
     */
    static List<String> tooltipLines(SparklineDto.Point p, ZoneId zone) {
        List<String> out = new ArrayList<>();
        out.add(TIP_TIME.format(Instant.ofEpochSecond(p.ts).atZone(zone)));
        out.add("Buy " + (p.high > 0 ? gp(p.high) : "-"));
        out.add("Sell " + (p.low > 0 ? gp(p.low) : "-"));
        if (p.high > 0 && p.low > 0) {
            long margin = p.high - p.low - OfferPriceWarning.tax((int) Math.min(p.high, Integer.MAX_VALUE));
            out.add("Margin " + gp(margin) + " after tax");
        }
        if (p.highVol > 0 || p.lowVol > 0) out.add("Vol " + gp(p.highVol) + " buy / " + gp(p.lowVol) + " sell");
        return out;
    }

    /** The point whose x is closest to {@code mouseX}. */
    static SparklineDto.Point nearest(List<SparklineDto.Point> pts, Rectangle plot, int mouseX, long t0, long t1) {
        SparklineDto.Point best = null;
        double bestD = Double.MAX_VALUE;
        for (SparklineDto.Point p : pts) {
            double d = Math.abs(x(plot, p.ts, t0, t1) - mouseX);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** The chart minus the tab/legend header band, so the tooltip never covers Instabuy/Instasell. */
    static Rectangle belowHeader(Rectangle area) {
        int top = area.y + HEADER_H + PAD;
        return new Rectangle(area.x, top, area.width, area.y + area.height - top);
    }

    /** Below-right of the anchor, flipped left/up where it would leave {@code bounds}, then clamped inside. */
    static Rectangle placeTooltip(Point anchor, int w, int h, Rectangle bounds) {
        int x = anchor.x + 10, y = anchor.y + 10;
        if (x + w > bounds.x + bounds.width - 1) x = anchor.x - 10 - w;
        if (y + h > bounds.y + bounds.height - 1) y = anchor.y - 10 - h;
        x = Math.max(bounds.x + 1, Math.min(x, bounds.x + bounds.width - 1 - w));
        y = Math.max(bounds.y + 1, Math.min(y, bounds.y + bounds.height - 1 - h));
        return new Rectangle(x, y, w, h);
    }

    private static Rectangle[] drawTabs(Graphics2D g, FontMetrics fm, Rectangle area, OfferChartRange selected, int hover) {
        OfferChartRange[] all = OfferChartRange.values();
        Rectangle[] out = new Rectangle[all.length];
        for (int i = 0; i < all.length; i++) {
            Rectangle r = new Rectangle(area.x + PAD + i * (TAB_W + TAB_GAP), area.y + PAD, TAB_W, TAB_H);
            out[i] = r;
            boolean on = all[i] == selected;
            g.setColor(on ? TAB_ON : (i == hover ? TAB_HOVER : TAB_OFF));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(BORDER);
            g.drawRect(r.x, r.y, r.width, r.height);
            g.setColor(on ? INK : AXIS_TEXT);
            String s = all[i].label;
            g.drawString(s, r.x + (r.width - fm.stringWidth(s)) / 2, r.y + 13);
        }
        return out;
    }

    /** Two rows, price right-aligned against a fixed-width age column at the right edge. */
    private static void drawLegend(Graphics2D g, FontMetrics fm, Rectangle area, List<SparklineDto.Point> pts,
                                   long nowSec, boolean dim) {
        SparklineDto.Point hi = latest(pts, true), lo = latest(pts, false);
        int right = area.x + area.width - PAD - 2;
        int ageCol = fm.stringWidth("23h ago");
        int priceRight = right - ageCol - 8;
        int[] rows = {area.y + PAD + 9, area.y + PAD + 20};
        String[] labels = {"Instabuy: " + (hi == null ? "-" : gp(hi.high)), "Instasell: " + (lo == null ? "-" : gp(lo.low))};
        String[] ages = {hi == null ? "" : ago(nowSec - hi.ts), lo == null ? "" : ago(nowSec - lo.ts)};
        Color[] colors = {INSTABUY, INSTASELL_TEXT};
        for (int i = 0; i < 2; i++) {
            Color c = dim ? alpha(colors[i], DIM_ALPHA) : colors[i];
            g.setColor(c);
            g.drawString(labels[i], priceRight - fm.stringWidth(labels[i]), rows[i]);
            g.drawString(ages[i], right - fm.stringWidth(ages[i]), rows[i]);
        }
    }

    private static void drawAxis(Graphics2D g, FontMetrics fm, Rectangle plot, long lo, long hi) {
        long step = niceStep((hi - lo) / 3.0);
        for (long v : ticks(lo, hi)) {
            int yy = y(plot, v, lo, hi);
            g.setColor(GRID);
            g.drawLine(plot.x, yy, plot.x + plot.width, yy);
            g.setColor(AXIS_TEXT);
            String s = axisLabel(v, step, hi);
            g.drawString(s, plot.x - 3 - fm.stringWidth(s), yy + 4);
        }
    }

    /** Missing for more than this many timesteps in a row: the line breaks instead of bridging the gap. */
    static final int GAP_STEPS = 6;

    private static void drawSeries(Graphics2D g, Rectangle plot, List<SparklineDto.Point> pts, boolean high,
                                   long t0, long t1, long lo, long hi, boolean dim, long maxGapSec) {
        Color c = high ? INSTABUY : INSTASELL;
        g.setColor(dim ? alpha(c, DIM_ALPHA) : c);
        for (List<SparklineDto.Point> seg : segments(pts, high, maxGapSec)) {
            if (seg.size() == 1) { // a lone trade between gaps still shows
                SparklineDto.Point p = seg.get(0);
                g.fillRect((int) Math.round(x(plot, p.ts, t0, t1)) - 1, y(plot, high ? p.high : p.low, lo, hi) - 1, 2, 2);
                continue;
            }
            Path2D path = new Path2D.Double();
            for (int i = 0; i < seg.size(); i++) {
                SparklineDto.Point p = seg.get(i);
                double xx = x(plot, p.ts, t0, t1), yy = y(plot, high ? p.high : p.low, lo, hi);
                if (i == 0) path.moveTo(xx, yy); else path.lineTo(xx, yy);
            }
            g.draw(path);
        }
    }

    /**
     * Runs of consecutive points that have this side's price. A point without the price (the wiki's
     * null avgHighPrice/avgLowPrice) or a time gap over {@code maxGapSec} ends the run.
     */
    static List<List<SparklineDto.Point>> segments(List<SparklineDto.Point> pts, boolean high, long maxGapSec) {
        List<List<SparklineDto.Point>> out = new ArrayList<>();
        List<SparklineDto.Point> cur = null;
        long lastTs = Long.MIN_VALUE;
        for (SparklineDto.Point p : pts) {
            long v = high ? p.high : p.low;
            if (v <= 0) { cur = null; continue; }
            if (cur == null || p.ts - lastTs > maxGapSec) {
                cur = new ArrayList<>();
                out.add(cur);
            }
            cur.add(p);
            lastTs = p.ts;
        }
        return out;
    }

    private static void shadowText(Graphics2D g, String s, int x, int y, Color c) {
        g.setColor(Color.BLACK);
        g.drawString(s, x + 1, y + 1);
        g.setColor(c);
        g.drawString(s, x, y);
    }

    /** Label baselines sit just above their line, sorted top to bottom, at least {@link #LABEL_GAP} apart. */
    static void layoutLabels(List<Marker> markers, int top, int bottom) {
        markers.sort(Comparator.comparingInt((Marker mk) -> mk.lineY));
        int[] ys = new int[markers.size()];
        for (int i = 0; i < ys.length; i++) {
            Marker mk = markers.get(i);
            ys[i] = mk.lineY - 2 < top ? mk.lineY + 10 : mk.lineY - 2;
        }
        int[] out = spread(ys, LABEL_GAP, top, bottom);
        for (int i = 0; i < out.length; i++) markers.get(i).labelY = out[i];
    }

    /** Pushes sorted baselines apart to {@code gap}, then back up if that ran past {@code bottom}. */
    static int[] spread(int[] sortedYs, int gap, int top, int bottom) {
        int[] y = Arrays.copyOf(sortedYs, sortedYs.length);
        for (int i = 1; i < y.length; i++) y[i] = Math.max(y[i], y[i - 1] + gap);
        if (y.length > 0 && y[y.length - 1] > bottom) {
            y[y.length - 1] = bottom;
            for (int i = y.length - 2; i >= 0; i--) y[i] = Math.min(y[i], y[i + 1] - gap);
        }
        if (y.length > 0 && y[0] < top) {
            y[0] = top;
            for (int i = 1; i < y.length; i++) y[i] = Math.max(y[i], y[i - 1] + gap);
        }
        return y;
    }

    /** Points inside the tab's time window, oldest first. */
    static List<SparklineDto.Point> window(List<SparklineDto.Point> pts, OfferChartRange range, long nowSec) {
        List<SparklineDto.Point> out = new ArrayList<>();
        if (pts == null) return out;
        long from = nowSec - range.windowSec;
        for (SparklineDto.Point p : pts) if (p.ts >= from) out.add(p);
        return out;
    }

    /**
     * Y range over the series, widened only for Coffer's lines when they're near it. The player's offer
     * never moves the range; off-scale it is pinned to the edge and labelled instead.
     */
    static long[] priceRange(List<SparklineDto.Point> pts, QuoteDto q) {
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (SparklineDto.Point p : pts) {
            if (p.high > 0) { lo = Math.min(lo, p.high); hi = Math.max(hi, p.high); }
            if (p.low > 0) { lo = Math.min(lo, p.low); hi = Math.max(hi, p.low); }
        }
        long span = Math.max(hi - lo, Math.max(1, hi / 50));
        long[] extra = q == null ? new long[0] : new long[]{q.placeBuy, q.placeSell};
        long dLo = lo, dHi = hi;
        for (long v : extra) {
            if (v <= 0 || v < dLo - span || v > dHi + span) continue; // far-off price: pinned to the edge instead
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        long pad = Math.max(1, (hi - lo) / 12);
        if (hi == lo) pad = Math.max(1, hi / 50);
        return new long[]{Math.max(0, lo - pad), hi + pad};
    }

    static int y(Rectangle plot, long v, long lo, long hi) {
        double f = hi == lo ? 0.5 : (double) (v - lo) / (hi - lo);
        return (int) Math.round(plot.y + plot.height - f * plot.height);
    }

    static double x(Rectangle plot, long ts, long t0, long t1) {
        double f = t1 == t0 ? 1 : (double) (ts - t0) / (t1 - t0);
        return plot.x + Math.max(0, Math.min(1, f)) * plot.width;
    }

    static SparklineDto.Point latest(List<SparklineDto.Point> pts, boolean high) {
        for (int i = pts.size() - 1; i >= 0; i--) {
            SparklineDto.Point p = pts.get(i);
            if ((high ? p.high : p.low) > 0) return p;
        }
        return null;
    }

    /** Gridline values between lo and hi: about three, never more than {@link #MAX_TICKS}. */
    static long[] ticks(long lo, long hi) {
        if (hi <= lo) return new long[]{lo};
        long step = niceStep((hi - lo) / 3.0);
        if (step <= 0) return new long[0];
        long[] out = new long[MAX_TICKS];
        int n = 0;
        for (long v = ceilTo(lo, step); v <= hi && n < MAX_TICKS; v += step) {
            out[n++] = v;
            if (v > Long.MAX_VALUE - step) break;
        }
        return Arrays.copyOf(out, n);
    }

    static long niceStep(double raw) {
        if (raw <= 1) return 1;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / mag;
        double nice = f < 1.5 ? 1 : f < 3.5 ? 2.5 : f < 7.5 ? 5 : 10;
        return Math.max(1, Math.round(nice * mag));
    }

    private static long ceilTo(long v, long step) {
        return ((v + step - 1) / step) * step;
    }

    /**
     * Axis label in the unit of the largest value (K/M/B, or plain gp under 10,000), with just enough
     * decimals for the tick step to be exact, so neighbouring ticks never print the same text.
     */
    static String axisLabel(long v, long step, long maxValue) {
        long unit;
        String suffix;
        if (maxValue >= 1_000_000_000L) { unit = 1_000_000_000L; suffix = "B"; }
        else if (maxValue >= 1_000_000L) { unit = 1_000_000L; suffix = "M"; }
        else if (maxValue >= 10_000L) { unit = 1_000L; suffix = "K"; }
        else return gp(v);
        int decimals = 0;
        long s = Math.max(1, step);
        while (decimals < 3 && (s % unit) != 0) {
            if (s > Long.MAX_VALUE / 10) break;
            s *= 10;
            decimals++;
        }
        return String.format(Locale.US, "%." + decimals + "f", v / (double) unit) + suffix;
    }

    /** Full gp with thousands separators: 39,958. */
    static String gp(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    /** "just now" / "8m ago" / "3h ago" / "2d ago". */
    static String ago(long secs) {
        if (secs < 60) return "just now";
        return age(secs) + " ago";
    }

    /** "<1m" / "12m" / "3h" / "2d". */
    static String age(long secs) {
        if (secs < 60) return "<1m";
        if (secs < 3600) return (secs / 60) + "m";
        if (secs < 86_400) return (secs / 3600) + "h";
        return (secs / 86_400) + "d";
    }

    /** "Couldn't refresh – showing 12m old data"; plain hyphen if the font lacks an en dash. */
    static String staleNote(long ageSec, Font font) {
        String dash = font != null && font.canDisplay('\u2013') ? "\u2013" : "-";
        return "Couldn't refresh " + dash + " showing " + age(ageSec) + " old data";
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
