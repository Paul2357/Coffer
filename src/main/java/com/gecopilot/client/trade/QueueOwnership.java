package com.gecopilot.client.trade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Keeps queued fills and offer events with the Coffer account they were captured under, so a
 * queue left behind by one login is never posted under another.
 * <ul>
 *   <li>Only items owned by the current login are sent.</li>
 *   <li>Items owned by another login are held for that login, and dropped once older than
 *       {@link #FOREIGN_HOLD_MS} (the server would clamp an offer event that old anyway).</li>
 *   <li>Items with no owner (captured before this RS account ever had a Coffer login, or queued by
 *       an older build) are adopted by the first login that flushes. That is what every queued
 *       item did before, so an upgrade loses nothing; it can only happen once per item.</li>
 * </ul>
 */
public final class QueueOwnership {
    public static final long FOREIGN_HOLD_MS = 7L * 24 * 60 * 60 * 1000;

    private QueueOwnership() {}

    /** Stamp unowned items with {@code userId}. Returns true if any changed. */
    public static boolean adopt(Collection<? extends OwnedItem> q, long userId) {
        boolean changed = false;
        for (OwnedItem i : q) if (i.getOwnerId() == null) { i.setOwnerId(userId); changed = true; }
        return changed;
    }

    /** Remove items owned by someone other than {@code userId} that are older than the hold. Returns true if any went. */
    public static boolean expireForeign(Collection<? extends OwnedItem> q, long userId, long nowMs) {
        return q.removeIf(i -> i.getOwnerId() != null && i.getOwnerId() != userId && i.getTs() < nowMs - FOREIGN_HOLD_MS);
    }

    /** Items {@code userId} may send, in queue order. */
    public static <T extends OwnedItem> List<T> sendable(Collection<T> q, long userId) {
        List<T> out = new ArrayList<>();
        for (T i : q) if (Objects.equals(i.getOwnerId(), userId)) out.add(i);
        return out;
    }

    /**
     * One flush for {@code userId}: adopt unowned items, expire stale foreign ones, then post this
     * login's items in order through {@code post}, removing each one accepted and stopping at the
     * first failure (or if the login changes mid-flush). Returns true if the queue changed.
     */
    public static <T extends OwnedItem> boolean drain(Collection<T> q, long userId, long nowMs,
                                                      java.util.function.Predicate<T> post,
                                                      java.util.function.BooleanSupplier sameLogin) {
        boolean changed = adopt(q, userId);
        changed |= expireForeign(q, userId, nowMs);
        for (T i : sendable(q, userId)) {
            if (!sameLogin.getAsBoolean() || !post.test(i)) break;
            q.remove(i);
            changed = true;
        }
        return changed;
    }

    /** Like {@link #drain} for a batch endpoint: up to {@code max} of this login's items in one post. */
    public static <T extends OwnedItem> boolean drainBatch(Collection<T> q, long userId, long nowMs, int max,
                                                           java.util.function.Predicate<List<T>> post,
                                                           java.util.function.BooleanSupplier sameLogin) {
        boolean changed = adopt(q, userId);
        changed |= expireForeign(q, userId, nowMs);
        List<T> mine = sendable(q, userId);
        List<T> batch = mine.subList(0, Math.min(max, mine.size()));
        if (!batch.isEmpty() && sameLogin.getAsBoolean() && post.test(new ArrayList<>(batch))) {
            java.util.Set<T> sent = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            sent.addAll(batch);
            q.removeIf(sent::contains);
            changed = true;
        }
        return changed;
    }

    /**
     * Shrink the queue to {@code max}: drop items held for another login first, then unowned ones,
     * then {@code current}'s own, oldest first within each, so held items never push out the
     * current login's. {@code current} null (no login known) treats every owned item alike.
     * Returns how many were dropped.
     */
    public static <T extends OwnedItem> int trim(Collection<T> q, int max, Long current) {
        int dropped = 0;
        for (int pass = 0; pass < 3 && q.size() > max; pass++) {
            final int p = pass;
            java.util.Iterator<T> it = q.iterator();
            while (q.size() > max && it.hasNext()) {
                Long o = it.next().getOwnerId();
                boolean mine = o != null && o.equals(current);
                boolean foreign = o != null && !mine;
                if ((p == 0 && foreign) || (p == 1 && o == null) || (p == 2 && mine)) { it.remove(); dropped++; }
            }
        }
        return dropped;
    }

    /** Items held for a login other than {@code userId}. */
    public static int heldForOthers(Collection<? extends OwnedItem> q, long userId) {
        int n = 0;
        for (OwnedItem i : q) if (i.getOwnerId() != null && i.getOwnerId() != userId) n++;
        return n;
    }
}
