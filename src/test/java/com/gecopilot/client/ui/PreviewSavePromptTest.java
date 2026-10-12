package com.gecopilot.client.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreviewSavePromptTest {
    @Test public void hiddenUntilFirstCardChoice() {
        PreviewSavePrompt p = new PreviewSavePrompt();
        assertEquals(PreviewSavePrompt.Phase.HIDDEN, p.phase());
        assertFalse(p.onFlipChosen(0));
        assertEquals(PreviewSavePrompt.Phase.HIDDEN, p.phase());
        assertTrue(p.onFlipChosen(4151));
        assertEquals(PreviewSavePrompt.Phase.ASK, p.phase());
        assertEquals(4151, p.pickId());
        // Later choices do not re-open or replace the first pick.
        assertFalse(p.onFlipChosen(2));
        assertEquals(4151, p.pickId());
    }

    @Test public void saveContinuesIntoFieldsAndDismissSticks() {
        PreviewSavePrompt p = new PreviewSavePrompt();
        p.onFlipChosen(4151);
        p.saveClicked();
        assertEquals(PreviewSavePrompt.Phase.FIELDS, p.phase());
        assertFalse(p.onFlipChosen(9));
        p.continueWithoutSaving();
        assertEquals(PreviewSavePrompt.Phase.DISMISSED, p.phase());
        assertEquals(0, p.pickId());
        assertFalse(p.onFlipChosen(4151));
        assertEquals(PreviewSavePrompt.Phase.DISMISSED, p.phase());
    }

    @Test public void resetReturnsToHidden() {
        PreviewSavePrompt p = new PreviewSavePrompt();
        p.onFlipChosen(1);
        p.reset();
        assertEquals(PreviewSavePrompt.Phase.HIDDEN, p.phase());
        assertTrue(p.onFlipChosen(2));
    }
}
