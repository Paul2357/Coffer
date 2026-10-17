package com.gecopilot.client.ui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** RuneLite's onActivate/onDeactivate drive the plugin's poll cadence through onVisibility. */
public class CloudPanelVisibilityTest {
    @Test public void activateAndDeactivateReportVisibility() throws Exception {
        System.setProperty("java.awt.headless", "true");
        List<Boolean> seen = new ArrayList<>();
        CloudPanel[] holder = new CloudPanel[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> holder[0] = new CloudPanel(null));
        CloudPanel p = holder[0];
        p.onVisibility(seen::add);
        assertFalse(p.isActive());
        p.onActivate();
        assertTrue(p.isActive());
        p.onDeactivate();
        assertFalse(p.isActive());
        assertEquals(List.of(true, false), seen);
    }

    @Test public void reopenCueIsQuietAndDoesNotMoveTheLayout() throws Exception {
        System.setProperty("java.awt.headless", "true");
        CloudPanel[] holder = new CloudPanel[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> holder[0] = new CloudPanel(null));
        CloudPanel p = holder[0];
        java.awt.Dimension[] size = new java.awt.Dimension[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> { p.setSize(225, 800); p.doLayout(); size[0] = p.getPreferredSize(); });
        p.beginRefresh();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertEquals("Updating\u2026", p.refreshStatusText());
        javax.swing.SwingUtilities.invokeAndWait(() -> assertEquals(size[0], p.getPreferredSize()));
        p.refreshDone(true);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertEquals("Updated just now", p.refreshStatusText());
        p.refreshDone(false);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertEquals("Last updated 1m ago", p.refreshStatusText());
        javax.swing.SwingUtilities.invokeAndWait(() -> assertEquals(size[0], p.getPreferredSize()));
    }

    /** Every headline figure dims while a refresh runs, including one recoloured mid-refresh. */
    @Test public void refreshDimsEveryHeadlineFigureEvenWhenRecoloured() throws Exception {
        System.setProperty("java.awt.headless", "true");
        CloudPanel[] holder = new CloudPanel[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> holder[0] = new CloudPanel(null));
        CloudPanel p = holder[0];
        com.gecopilot.client.api.Dashboard d = new com.gecopilot.client.api.Dashboard();
        d.realized = 1_000; d.flips = 3; d.fillRate = "80%";
        p.update(new com.gecopilot.client.api.SyncResult(), d, null);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertArrayEquals(new int[]{255, 255, 255, 255}, p.figureAlphas());

        p.beginRefresh();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        int a = CloudPanel.DIM_ALPHA;
        assertEquals(140, a); // ~55%
        assertArrayEquals(new int[]{a, a, a, a}, p.figureAlphas());

        d.realized = -500;                                 // P&L turns red while still updating
        p.update(new com.gecopilot.client.api.SyncResult(), d, null);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertArrayEquals("recoloured figures stay dimmed", new int[]{a, a, a, a}, p.figureAlphas());

        p.refreshDone(true);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertArrayEquals(new int[]{255, 255, 255, 255}, p.figureAlphas());
    }
}
