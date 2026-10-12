package com.gecopilot.client.ui;

/**
 * Preview → account prompt (copy A). Hidden on first paint.
 *
 * The preview list is read-only: no watch star and no offer view. The earliest
 * meaningful action is choosing a flip card. That choice reveals the prompt once
 * per session. "Continue without saving" dismisses it for the rest of the session.
 */
public final class PreviewSavePrompt {
    public enum Phase { HIDDEN, ASK, FIELDS, DISMISSED }

    private Phase phase = Phase.HIDDEN;
    private int pickId;

    public Phase phase() { return phase; }

    /** Item the player chose when the prompt opened. 0 if none. */
    public int pickId() { return pickId; }

    /** @return true only on the transition that first reveals the prompt */
    public boolean onFlipChosen(int itemId) {
        if (itemId <= 0 || phase != Phase.HIDDEN) return false;
        pickId = itemId;
        phase = Phase.ASK;
        return true;
    }

    public void saveClicked() {
        if (phase == Phase.ASK) phase = Phase.FIELDS;
    }

    public void continueWithoutSaving() {
        phase = Phase.DISMISSED;
        pickId = 0;
    }

    public void reset() {
        phase = Phase.HIDDEN;
        pickId = 0;
    }
}
