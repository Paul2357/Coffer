package com.gecopilot.client.ui;

import java.util.Locale;

/**
 * First-session next step. Stages only move forward: open the GE, place or view an offer,
 * wait for the fill, then the first live trade. {@code offersKnown} false (GE not opened yet,
 * or an older client that does not report it) stays on the open-GE step unless the server
 * has already recorded an offer or a live trade.
 */
public final class SessionGuide {
    public enum Stage { OPEN_GE, PLACE_OFFER, WAIT_FILL, DONE }

    private SessionGuide() {}

    public static Stage stage(boolean offersKnown, boolean offersSeen, boolean firstLiveTrade) {
        if (firstLiveTrade) return Stage.DONE;
        if (offersSeen) return Stage.WAIT_FILL;
        if (offersKnown) return Stage.PLACE_OFFER;
        return Stage.OPEN_GE;
    }

    /** A failed sync must not walk the session backwards. */
    public static Stage advance(Stage from, Stage to) {
        if (to == null) return from == null ? Stage.OPEN_GE : from;
        if (from == null) return to;
        return to.ordinal() < from.ordinal() ? from : to;
    }

    /** Persistent next action. Null once the guide is finished. */
    public static String banner(Stage stage, String flipName, int placeBuy) {
        if (stage == null || stage == Stage.DONE) return null;
        if (stage == Stage.OPEN_GE) return "Next: open the Grand Exchange.";
        if (stage == Stage.PLACE_OFFER) {
            if (flipName != null && !flipName.isEmpty() && placeBuy > 0)
                return "Next: in the GE, buy " + flipName + " @ " + String.format(Locale.US, "%,d", placeBuy) + ".";
            return "Next: place or view an offer in the GE.";
        }
        return "Offer synced. Sell it when it fills — see Positions.";
    }

    /** Spoken once when the stage advances. Null on the first observation. */
    public static String transitionToast(Stage from, Stage to) {
        if (to == null || from == null || to == from || to.ordinal() < from.ordinal()) return null;
        if (to == Stage.DONE) return "First trade recorded.";
        if (to == Stage.WAIT_FILL) return "Offer synced. Check Positions.";
        if (to == Stage.PLACE_OFFER) return "GE is open. Place or view an offer.";
        return null;
    }

    public static String emptyFlips(Stage stage) {
        if (stage == Stage.OPEN_GE) return "No flips yet. Open the Grand Exchange.";
        if (stage == Stage.PLACE_OFFER) return "No flips match. Place or view any GE offer.";
        if (stage == Stage.WAIT_FILL) return "No other flips right now. Your offer is on Positions.";
        return "No flips right now.";
    }

    public static String emptyPlan(Stage stage) {
        if (stage == Stage.OPEN_GE) return "No plan yet. Open the Grand Exchange.";
        if (stage == Stage.PLACE_OFFER) return "Nothing to place. View an offer, or ease filters.";
        if (stage == Stage.WAIT_FILL) return "Nothing to place until a slot frees.";
        return "Nothing to place right now.";
    }

    public static String emptyPositions(Stage stage) {
        if (stage == Stage.OPEN_GE) return "No offers yet. Open the Grand Exchange.";
        if (stage == Stage.PLACE_OFFER) return "No offer yet. Buy the top Finder pick in the GE.";
        if (stage == Stage.WAIT_FILL) return "No open offers. Sell the fill to finish the flip.";
        return "No open offers.";
    }
}
