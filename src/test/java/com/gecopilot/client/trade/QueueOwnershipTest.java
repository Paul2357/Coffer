package com.gecopilot.client.trade;

import com.google.gson.Gson;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.Assert.*;

/** Queued fills and offer events only ever post under the Coffer login they belong to. */
public class QueueOwnershipTest {
    private static final long NOW = 1_700_000_000_000L, ALICE = 1, BOB = 2;

    private static CompletedFill fill(int item, Long owner, long ts) {
        CompletedFill f = new CompletedFill(item, true, 1, 100, ts);
        f.ownerId = owner;
        return f;
    }

    @Test public void onlyTheCurrentLoginsFillsAreSentAndOthersAreHeld() {
        ConcurrentLinkedDeque<CompletedFill> q = new ConcurrentLinkedDeque<>();
        q.add(fill(1, ALICE, NOW)); q.add(fill(2, BOB, NOW)); q.add(fill(3, ALICE, NOW));
        List<Integer> posted = new ArrayList<>();
        assertTrue(QueueOwnership.drain(q, BOB, NOW, f -> posted.add(f.itemId), () -> true));
        assertEquals(List.of(2), posted);
        assertEquals(2, q.size());
        assertTrue(q.stream().allMatch(f -> f.ownerId == ALICE));
        // Alice signs back in: her fills go, in order.
        QueueOwnership.drain(q, ALICE, NOW, f -> posted.add(f.itemId), () -> true);
        assertEquals(List.of(2, 1, 3), posted);
        assertTrue(q.isEmpty());
    }

    @Test public void failureKeepsTheRestInOrderAndALoginChangeStopsTheFlush() {
        ConcurrentLinkedDeque<CompletedFill> q = new ConcurrentLinkedDeque<>();
        q.add(fill(1, ALICE, NOW)); q.add(fill(2, ALICE, NOW)); q.add(fill(3, ALICE, NOW));
        int[] calls = {0};
        QueueOwnership.drain(q, ALICE, NOW, f -> ++calls[0] == 1, () -> true);
        assertEquals(2, q.size());
        assertEquals(2, q.peekFirst().itemId);
        QueueOwnership.drain(q, ALICE, NOW, f -> true, () -> false); // logged out / switched mid-flush
        assertEquals(2, q.size());
    }

    @Test public void foreignItemsAreDroppedAfterTheHoldAndUnownedOnesAreAdopted() {
        ConcurrentLinkedDeque<CompletedFill> q = new ConcurrentLinkedDeque<>();
        q.add(fill(1, ALICE, NOW - QueueOwnership.FOREIGN_HOLD_MS - 1)); // Alice's, too old to hold
        q.add(fill(2, ALICE, NOW - 60_000));                             // Alice's, held
        q.add(fill(3, null, NOW));                                       // no owner (older build)
        List<Integer> posted = new ArrayList<>();
        QueueOwnership.drain(q, BOB, NOW, f -> posted.add(f.itemId), () -> true);
        assertEquals("the unowned fill is adopted by the first login", List.of(3), posted);
        assertEquals(1, q.size());
        assertEquals(2, q.peekFirst().itemId);
    }

    @Test public void offerEventBatchesSendOnlyTheLoginsEvents() {
        ConcurrentLinkedDeque<OfferEvent> q = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < 5; i++) {
            OfferEvent e = new OfferEvent("placed", i, true, 100, 1, 0, 0, NOW);
            e.ownerId = i % 2 == 0 ? ALICE : BOB;
            q.add(e);
        }
        List<List<OfferEvent>> posts = new ArrayList<>();
        QueueOwnership.drainBatch(q, ALICE, NOW, 2, b -> posts.add(b), () -> true);
        assertEquals(1, posts.size());
        assertEquals(List.of(0, 2), List.of(posts.get(0).get(0).itemId, posts.get(0).get(1).itemId));
        assertEquals(3, q.size());
        QueueOwnership.drainBatch(q, ALICE, NOW, 2, b -> false, () -> true); // failed post keeps them
        assertEquals(3, q.size());
        QueueOwnership.drainBatch(q, ALICE, NOW, 2, b -> true, () -> true);
        assertTrue(q.stream().allMatch(e -> e.ownerId == BOB));
    }

    @Test public void queuesSavedByOlderBuildsLoadWithNoOwner() {
        Gson g = new Gson();
        CompletedFill f = g.fromJson("{\"itemId\":4151,\"buy\":true,\"qty\":1,\"price\":100,\"ts\":5}", CompletedFill.class);
        OfferEvent e = g.fromJson("{\"kind\":\"placed\",\"itemId\":4151,\"ts\":5}", OfferEvent.class);
        assertNull(f.ownerId);
        assertNull(e.ownerId);
        f.ownerId = 7L;
        assertEquals(Long.valueOf(7), g.fromJson(g.toJson(f), CompletedFill.class).ownerId);
    }

    @Test public void trimDropsHeldEventsBeforeTheCurrentLoginsOwn() {
        ConcurrentLinkedDeque<OfferEvent> q = new ConcurrentLinkedDeque<>();
        Long[] owners = {ALICE, BOB, BOB, null, ALICE, BOB};   // oldest first
        for (int i = 0; i < owners.length; i++) {
            OfferEvent e = new OfferEvent("placed", i, true, 100, 1, 0, 0, NOW + i);
            e.ownerId = owners[i];
            q.add(e);
        }
        assertEquals(3, QueueOwnership.trim(q, 3, ALICE));
        List<Integer> left = new ArrayList<>();
        for (OfferEvent e : q) left.add(e.itemId);
        assertEquals("Bob's three held events went; Alice's and the unowned one stay", List.of(0, 3, 4), left);
        assertEquals(1, QueueOwnership.trim(q, 2, ALICE));
        left.clear();
        for (OfferEvent e : q) left.add(e.itemId);
        assertEquals("then unowned, before Alice's own", List.of(0, 4), left);
        assertEquals(1, QueueOwnership.trim(q, 1, ALICE));
        assertEquals(4, q.peekFirst().itemId);              // finally Alice's oldest
        assertEquals(0, QueueOwnership.trim(q, 1, ALICE));
    }

    @Test public void heldCountIsOtherLoginsItemsOnly() {
        ConcurrentLinkedDeque<CompletedFill> q = new ConcurrentLinkedDeque<>();
        q.add(fill(1, ALICE, NOW)); q.add(fill(2, BOB, NOW)); q.add(fill(3, null, NOW)); q.add(fill(4, BOB, NOW));
        assertEquals(2, QueueOwnership.heldForOthers(q, ALICE));
        assertEquals(1, QueueOwnership.heldForOthers(q, BOB));
    }
}
