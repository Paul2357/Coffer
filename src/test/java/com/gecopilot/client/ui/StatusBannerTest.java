package com.gecopilot.client.ui;

import com.gecopilot.client.api.ServiceStatusDto;
import com.gecopilot.client.api.StatusMonitor;
import com.gecopilot.client.api.PollPolicy;
import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.*;

public class StatusBannerTest {
    private static StatusMonitor.Banner banner(String level, String message, Boolean db) {
        StatusMonitor m = new StatusMonitor(new PollPolicy(() -> 0L, () -> false), () -> 0L);
        ServiceStatusDto s = level == null && message == null && db == null ? null : new ServiceStatusDto();
        if (s != null) { s.level = level; s.message = message; s.db = db; }
        assertTrue(m.tryStart());
        return m.attempt(() -> s).banner;
    }

    private static void flush() throws Exception { SwingUtilities.invokeAndWait(() -> { }); }

    @Test public void bannerShowsOnEveryCardAndHidesTheSmallConnNotice() throws Exception {
        System.setProperty("java.awt.headless", "true");
        CloudPanel[] holder = new CloudPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new CloudPanel(null));
        CloudPanel p = holder[0];
        assertNull(p.serviceStatusText());

        p.setConnNotice("Can't reach Coffer \u2014 retrying\u2026");
        flush();
        assertEquals("Can't reach Coffer \u2014 retrying\u2026", p.connNoticeText());

        p.setServiceStatus(banner(null, null, null)); // unreachable -> auto outage
        flush();
        assertEquals(StatusMonitor.DEFAULT_OUTAGE, p.serviceStatusText());
        assertNull("the banner already says it", p.connNoticeText());

        p.setServiceStatus(null); // recovered
        flush();
        assertNull(p.serviceStatusText());
        assertEquals("the conn notice comes back if still unreachable", "Can't reach Coffer \u2014 retrying\u2026", p.connNoticeText());
    }

    @Test public void serverTextIsPlainNotHtml() throws Exception {
        System.setProperty("java.awt.headless", "true");
        StatusBanner[] holder = new StatusBanner[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new StatusBanner());
        StatusMonitor.Banner b = banner("info", "<html><b>hi</b></html>", true);
        SwingUtilities.invokeAndWait(() -> holder[0].show(b));
        assertEquals("<html><b>hi</b></html>", holder[0].shownText()); // shown literally in a JTextArea
    }

    @Test public void sinceIsRelative() {
        long now = java.time.Instant.parse("2026-10-19T21:00:00Z").toEpochMilli();
        assertEquals("Since 12m ago", StatusBanner.sinceText("2026-10-19T20:48:00Z", now));
        assertEquals("Since 3h ago", StatusBanner.sinceText("2026-10-19T18:00:00Z", now));
        assertEquals("Since 2d ago", StatusBanner.sinceText("2026-10-17T20:00:00Z", now));
        assertEquals("Since 1m ago", StatusBanner.sinceText("2026-10-19T20:59:50Z", now));
        assertEquals("future (clock skew) reads as 1m", "Since 1m ago", StatusBanner.sinceText("2026-10-19T22:00:00Z", now));
        assertNull(StatusBanner.sinceText("nope", now));
        assertNull(StatusBanner.sinceText(null, now));
    }

    @Test public void linkLabelShowsWhereItGoes() {
        assertEquals("Copy link: meetcoffer.com/status", StatusBanner.linkLabel("https://meetcoffer.com/status/"));
        assertEquals("Copy link: x.example/a", StatusBanner.linkLabel("https://x.example/a?utm=1#top"));
        String longOne = StatusBanner.linkLabel("https://status.example.com/incidents/2026/10/19/neon-paused-database");
        assertEquals("Copy link: ".length() + StatusBanner.LINK_CHARS, longOne.length());
        assertTrue(longOne.endsWith("\u2026"));
    }
}
