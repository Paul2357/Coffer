package com.gecopilot.client.api;

/**
 * One refresh against the server. A full refresh (panel on screen) does everything. A light refresh
 * (panel hidden) keeps what works without the panel: it flushes queued trades and offer events,
 * posts /api/flips (open-offer sync and heartbeat) and runs the coach nudges. It skips
 * /api/dashboard, which only the panel shows, and /api/plan-shown, since nobody saw the plan.
 */
public final class PollCycle {
    /** The plugin's side of a refresh. Implementations do the I/O; PollCycle decides what runs. */
    public interface Steps {
        /** Signed in; nothing else runs without a session. */
        boolean ready();
        /** One-shots that must precede the first sync of the session (recap, history hint). */
        void beforeSync();
        /** Post queued fills and offer events. */
        void flushQueues();
        /** POST /api/flips. */
        SyncResult sync();
        /** GET /api/dashboard. */
        Dashboard dashboard();
        /** Repaint; {@code dash} is null on a light refresh, so the panel keeps its last dashboard. */
        void render(SyncResult sync, Dashboard dash);
        /** POST /api/plan-shown when the plan changed. */
        void logPlan(SyncResult sync);
        /** Coach notifications for open offers. */
        void nudge(SyncResult sync);
        /** The refresh finished; {@code ok} when the server answered (and, for a full one, the dashboard too). */
        default void done(boolean ok) {}
    }

    private final PollPolicy policy;
    private final Steps steps;

    public PollCycle(PollPolicy policy, Steps steps) {
        this.policy = policy;
        this.steps = steps;
    }

    public PollPolicy policy() { return policy; }

    /** The 60s timer. Polls every tick while visible, every {@link PollPolicy#HIDDEN_INTERVAL_MS} while hidden. */
    public void onTick() {
        if (policy.dueOnTick()) refresh();
    }

    /** A refresh whose depth follows panel visibility (GE-event refresh, user actions). */
    public void refresh() {
        run(policy.visible());
    }

    /** Full refresh regardless of visibility; used when the panel opens. */
    public void refreshFull() {
        run(true);
    }

    void run(boolean full) {
        if (!steps.ready()) { steps.done(false); return; }
        boolean ok = false;
        try {
            steps.beforeSync();
            steps.flushQueues();
            policy.noteSync();
            SyncResult sync = steps.sync();
            Dashboard dash = full ? steps.dashboard() : null;
            steps.render(sync, dash);
            if (full) steps.logPlan(sync);
            steps.nudge(sync);
            // A failed /api/flips comes back as an empty result with no tier.
            ok = sync != null && sync.tier != null && (!full || dash != null);
        } finally {
            steps.done(ok); // also on an exception, so the header never sticks on "Updating…"
        }
    }
}
