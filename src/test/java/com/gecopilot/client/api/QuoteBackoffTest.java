package com.gecopilot.client.api;

import org.junit.Test;

import static org.junit.Assert.*;

/** The GE overlay quote fetch must not retry every game tick. */
public class QuoteBackoffTest {
    private static final long TICK = 600;

    @Test public void oneRequestInFlightPerItem() {
        QuoteBackoff b = new QuoteBackoff();
        assertTrue(b.tryStart(4151, 0));
        for (int t = 1; t <= 100; t++) assertFalse("tick " + t, b.tryStart(4151, t * TICK));
    }

    @Test public void failuresBackOffExponentiallyToTheCap() {
        assertEquals(1_000, QuoteBackoff.delayMs(1));
        assertEquals(2_000, QuoteBackoff.delayMs(2));
        assertEquals(4_000, QuoteBackoff.delayMs(3));
        assertEquals(16_000, QuoteBackoff.delayMs(5));
        assertEquals(30_000, QuoteBackoff.delayMs(6));
        assertEquals(30_000, QuoteBackoff.delayMs(500));
    }

    @Test public void failedItemWaitsBeforeRetrying() {
        QuoteBackoff b = new QuoteBackoff();
        assertTrue(b.tryStart(4151, 0));
        b.failed(4151, 0);
        assertFalse(b.tryStart(4151, 999));
        assertTrue(b.tryStart(4151, 1_000));
        b.failed(4151, 1_000);
        assertFalse(b.tryStart(4151, 2_999));
        assertTrue(b.tryStart(4151, 3_000));
    }

    @Test public void tenMinutesOfFailuresIsAFewDozenRequestsNotAThousand() {
        QuoteBackoff b = new QuoteBackoff();
        int requests = 0;
        for (long now = 0; now < 10 * 60_000L; now += TICK) {
            if (b.tryStart(4151, now)) { requests++; b.failed(4151, now); }
        }
        // Old behaviour: a request every 0.6s tick = 1000. Capped at 30s: about 25.
        assertTrue("requests=" + requests, requests <= 30);
        assertTrue("still retries", requests >= 15);
    }

    @Test public void successClearsTheBackoff() {
        QuoteBackoff b = new QuoteBackoff();
        b.tryStart(4151, 0);
        b.failed(4151, 0);
        b.tryStart(4151, 1_000);
        b.failed(4151, 1_000);
        assertTrue(b.tryStart(4151, 3_000));
        assertTrue(b.succeeded(4151));
        assertTrue(b.tryStart(4151, 3_001));
    }

    @Test public void newItemStartsFreshAndStaleResultsAreIgnored() {
        QuoteBackoff b = new QuoteBackoff();
        assertTrue(b.tryStart(4151, 0));
        assertTrue("switching item does not wait on the old request", b.tryStart(11802, TICK));
        assertFalse("old item's late answer must not land", b.succeeded(4151));
        b.failed(4151, TICK);         // ignored too
        assertFalse(b.tryStart(11802, 2 * TICK)); // new item still in flight
        assertTrue(b.succeeded(11802));
    }

    @Test public void resetOnLeavingTheGeScreen() {
        QuoteBackoff b = new QuoteBackoff();
        b.tryStart(4151, 0);
        b.failed(4151, 0);
        b.reset();
        assertTrue(b.tryStart(4151, 1));
    }

    @Test public void attemptClearsInFlightEvenWhenTheFetchThrowsAnError() {
        QuoteBackoff b = new QuoteBackoff();
        assertTrue(b.tryStart(4151, 0));
        try {
            b.attempt(4151, () -> { throw new AssertionError("not an Exception"); }, () -> 0L);
            fail("expected the error to propagate");
        } catch (AssertionError expected) { }
        assertFalse("backs off like any failure", b.tryStart(4151, 999));
        assertTrue("not stuck in flight", b.tryStart(4151, 1_000));
    }

    @Test public void attemptReturnsTheQuoteOnSuccess() {
        QuoteBackoff b = new QuoteBackoff();
        assertTrue(b.tryStart(4151, 0));
        assertEquals("q", b.attempt(4151, () -> "q", () -> 0L));
        assertTrue(b.tryStart(4151, 1));
    }

    @Test public void attemptTreatsNullAsAFailure() {
        QuoteBackoff b = new QuoteBackoff();
        assertTrue(b.tryStart(4151, 0));
        assertNull(b.attempt(4151, () -> null, () -> 0L));
        assertFalse(b.tryStart(4151, 500));
        assertTrue(b.tryStart(4151, 1_000));
    }

    @Test public void attemptDropsALateAnswerForAnItemThePlayerLeft() {
        QuoteBackoff b = new QuoteBackoff();
        assertTrue(b.tryStart(4151, 0));
        assertTrue(b.tryStart(11802, 600));
        assertNull(b.attempt(4151, () -> "stale", () -> 600L));
        assertFalse("new item still in flight", b.tryStart(11802, 1_200));
    }
}
