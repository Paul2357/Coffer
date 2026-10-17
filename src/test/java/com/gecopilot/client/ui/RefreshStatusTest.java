package com.gecopilot.client.ui;

import org.junit.Test;

import static com.gecopilot.client.ui.RefreshStatus.State.*;
import static org.junit.Assert.*;

public class RefreshStatusTest {
    private static final long NOW = 10_000_000_000L;

    @Test public void updatingThenUpdated() {
        assertEquals("Updating\u2026", RefreshStatus.text(UPDATING, NOW - 600_000, NOW));
        assertEquals("Updated just now", RefreshStatus.text(OK, NOW, NOW));
    }

    @Test public void failureSaysHowOldTheDataIs() {
        assertEquals("Last updated 1m ago", RefreshStatus.text(FAILED, NOW - 5_000, NOW));
        assertEquals("Last updated 12m ago", RefreshStatus.text(FAILED, NOW - 12 * 60_000L - 30_000, NOW));
        assertEquals("Last updated 59m ago", RefreshStatus.text(FAILED, NOW - 59 * 60_000L, NOW));
        assertEquals("Last updated 2h ago", RefreshStatus.text(FAILED, NOW - 150 * 60_000L, NOW));
        assertEquals("Last updated 3d ago", RefreshStatus.text(FAILED, NOW - 3 * 24 * 3_600_000L, NOW));
        assertEquals("Not updated yet", RefreshStatus.text(FAILED, 0, NOW));
    }

    @Test public void idleKeepsTheRowHeight() {
        assertEquals(" ", RefreshStatus.text(IDLE, 0, NOW));
    }

    @Test public void widestTextCoversEveryState() {
        String[] all = {
            RefreshStatus.text(UPDATING, 0, NOW), RefreshStatus.text(OK, NOW, NOW),
            RefreshStatus.text(FAILED, NOW - 59 * 60_000L, NOW), RefreshStatus.text(FAILED, NOW - 23 * 3_600_000L, NOW),
            RefreshStatus.text(FAILED, NOW - 99 * 24 * 3_600_000L, NOW), RefreshStatus.text(FAILED, 0, NOW)};
        for (String t : all) assertTrue(t, t.length() <= RefreshStatus.WIDEST.length());
    }
}
