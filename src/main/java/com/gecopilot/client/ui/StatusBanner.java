package com.gecopilot.client.ui;

import com.gecopilot.client.api.StatusMonitor;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;

/**
 * Service-status banner at the top of a panel card. Plain text only (a JTextArea, never HTML), so a
 * server message can't inject markup. The link is copied to the clipboard rather than opened:
 * LinkBrowser.open is a restricted Plugin Hub API (see 5530c12). EDT only.
 */
final class StatusBanner extends JPanel {
    static final String COPY_LINK = "Copy link for details";
    /** Sidebar width less the card margin, the accent bar and the banner padding. */
    private static final int TEXT_WIDTH = net.runelite.client.ui.PluginPanel.PANEL_WIDTH - 16 - 3 - 16;

    private final JTextArea text = new JTextArea();
    private final JLabel since = new JLabel();
    private final JLabel link = new JLabel(COPY_LINK);
    private String linkUrl;

    StatusBanner() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
        text.setEditable(false); text.setFocusable(false);
        text.setLineWrap(true); text.setWrapStyleWord(true); text.setOpaque(false); text.setBorder(null);
        text.setFont(FontManager.getRunescapeSmallFont());
        text.setForeground(Color.WHITE);
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        // A wrapping JTextArea sizes its height from its current width, which is 0 before the first
        // layout; seed it with the sidebar's text width so the first paint wraps instead of clipping.
        text.setSize(new Dimension(TEXT_WIDTH, Short.MAX_VALUE));
        since.setFont(FontManager.getRunescapeSmallFont());
        since.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        since.setAlignmentX(Component.LEFT_ALIGNMENT);
        since.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        link.setFont(FontManager.getRunescapeSmallFont());
        link.setForeground(new Color(0x4f, 0xc3, 0xf7));
        link.setAlignmentX(Component.LEFT_ALIGNMENT);
        link.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (linkUrl != null) CloudPanel.copyLinkToClipboard(linkUrl, link, linkLabel(linkUrl));
            }
        });
        add(text);
        add(since);
        add(link);
        setVisible(false);
    }

    void show(StatusMonitor.Banner b) {
        if (b == null) { setVisible(false); linkUrl = null; return; }
        Color bg, accent;
        switch (b.level) {
            case INFO: bg = new Color(0x1a, 0x22, 0x2e); accent = new Color(0x4f, 0xc3, 0xf7); break;
            case WARNING: bg = new Color(0x3a, 0x30, 0x10); accent = ColorScheme.BRAND_ORANGE; break;
            default: bg = new Color(0x4a, 0x18, 0x18); accent = ColorScheme.PROGRESS_ERROR_COLOR; break;
        }
        setBackground(bg);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        text.setText(b.text);
        if (text.getWidth() <= 0) text.setSize(new Dimension(TEXT_WIDTH, Short.MAX_VALUE));
        String s = sinceText(b.since, System.currentTimeMillis());
        since.setText(s == null ? "" : s);
        since.setVisible(s != null);
        linkUrl = b.link;
        link.setVisible(b.link != null);
        link.setText(b.link == null ? COPY_LINK : linkLabel(b.link));
        link.setToolTipText(b.link);
        setVisible(true);
        revalidate();
        repaint();
    }

    String shownText() { return isVisible() ? text.getText() : null; }
    String shownLink() { return isVisible() && link.isVisible() ? link.getText() : null; }

    static final int LINK_CHARS = 32;

    /** "Copy link: meetcoffer.com/status", so players see where it goes. Host + path, shortened. */
    static String linkLabel(String url) {
        String shown = url.replaceFirst("(?i)^https://", "");
        int q = shown.indexOf('?');
        if (q >= 0) shown = shown.substring(0, q);
        int h = shown.indexOf('#');
        if (h >= 0) shown = shown.substring(0, h);
        if (shown.endsWith("/")) shown = shown.substring(0, shown.length() - 1);
        if (shown.length() > LINK_CHARS) shown = shown.substring(0, LINK_CHARS - 1) + "\u2026";
        return "Copy link: " + shown;
    }

    /**
     * "Since 12m ago" / "Since 3h ago" / "Since 2d ago": relative, so no time zone to explain. Same
     * wording as the header's refresh cue. A time in the future (clock skew) reads as 1m. Null without
     * a parseable time.
     */
    static String sinceText(String iso, long nowMs) {
        if (iso == null) return null;
        try { return "Since " + RefreshStatus.ago(Math.max(0, nowMs - Instant.parse(iso).toEpochMilli())) + " ago"; }
        catch (RuntimeException e) { return null; }
    }
}
