package com.gecopilot.client.trade;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the in-game GE History interface (group 383, list container child 3). Its dynamic children
 * are, per completed trade: a "Bought:"/"Sold:" label, then within the next few slots the item widget
 * (authoritative itemId + quantity) and a "<total> coins … = <each> each" price line. Unit price is
 * computed as round(total/qty) to match how Coffer records fills (round(spent/qty)).
 *
 * Must be called on the client thread.
 */
public final class GeHistoryReader {
    private GeHistoryReader() {}

    private static final int GROUP = 383, LIST_CHILD = 3;
    private static final Pattern NUM = Pattern.compile("([\\d,]+)");
    private static final Pattern GROSS_PAREN = Pattern.compile("\\(([\\d,]+)\\s*-"); // "(729,632 - 14,592)"

    public static List<GeHistoryRow> read(Client client) {
        List<GeHistoryRow> out = new ArrayList<>();
        Widget list = client.getWidget(GROUP, LIST_CHILD);
        if (list == null) return out;
        Widget[] dyn = list.getDynamicChildren();
        if (dyn == null) return out;
        for (int i = 0; i < dyn.length; i++) {
            Widget w = dyn[i];
            String t = w == null ? null : w.getText();
            if (t == null) continue;
            boolean buy;
            if (t.equals("Bought:")) buy = true; else if (t.equals("Sold:")) buy = false; else continue;

            int itemId = -1, qty = 0; String priceText = null;
            for (int j = i + 1; j <= i + 5 && j < dyn.length; j++) {
                Widget c = dyn[j]; if (c == null) continue;
                if (itemId < 0 && c.getItemId() > 0) { itemId = c.getItemId(); qty = c.getItemQuantity(); }
                String ct = c.getText();
                if (priceText == null && ct != null && ct.contains("coins")) priceText = ct;
            }
            if (itemId > 0 && qty > 0 && priceText != null) {
                long gross = grossTotal(priceText);
                if (gross >= 0) out.add(new GeHistoryRow(itemId, buy, qty, (int) Math.round((double) gross / qty)));
            }
        }
        return out;
    }

    /**
     * The GROSS total coins for a history price line, matching how Coffer records fills (gross,
     * pre-tax). A sell shows "715,040 coins (729,632 - 14,592) = 89,380 each" — the 715,040 is net
     * and 729,632 (in parens) is gross; use the gross. A buy shows just "594,663 coins = …" with no
     * tax breakdown, so its plain total is already gross.
     */
    static long grossTotal(String priceText) {
        String s = priceText.replaceAll("<[^>]*>", " ");
        Matcher paren = GROSS_PAREN.matcher(s);
        if (paren.find()) return Long.parseLong(paren.group(1).replace(",", ""));
        return firstNumber(priceText);
    }

    /** First comma-grouped integer in a string ("594,663 coins…" -> 594663), or -1 if none. Strips
     *  HTML-ish tags first so digits inside colour codes (e.g. &lt;col=ffb83f&gt;) aren't matched. */
    static long firstNumber(String s) {
        Matcher m = NUM.matcher(s.replaceAll("<[^>]*>", " "));
        return m.find() ? Long.parseLong(m.group(1).replace(",", "")) : -1;
    }
}
