package com.gecopilot.client.api;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class TimeseriesCacheTest {
    /** Loads started by the cache; the test completes them like OkHttp's dispatcher would. */
    private final List<TimeseriesCache.Callback> pending = new ArrayList<>();
    private final List<Integer> requestedIds = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private TimeseriesCache cache() {
        return new TimeseriesCache((id, step, cb) -> { requestedIds.add(id); pending.add(cb); }, now::get);
    }

    private void succeedAll() {
        List<TimeseriesCache.Callback> copy = new ArrayList<>(pending);
        pending.clear();
        for (int i = 0; i < copy.size(); i++) copy.get(i).onSuccess(Collections.singletonList(point(requestedIds.get(i))));
        requestedIds.clear();
    }

    private void failAll() {
        List<TimeseriesCache.Callback> copy = new ArrayList<>(pending);
        pending.clear();
        requestedIds.clear();
        for (TimeseriesCache.Callback cb : copy) cb.onFailure(new java.io.IOException("down"));
    }

    @Test public void firstGetReturnsNullAndStartsOneLoad() {
        TimeseriesCache c = cache();
        assertNull(c.get(4151, "5m"));
        assertNull(c.get(4151, "5m"));
        assertNull(c.get(4151, "5m"));
        assertEquals("concurrent gets share one in-flight load", 1, pending.size());
        assertTrue(c.isLoading(4151, "5m"));
        succeedAll();
        assertFalse(c.isLoading(4151, "5m"));
        TimeseriesCache.Series s = c.get(4151, "5m");
        assertNotNull("callback populated the cache", s);
        assertFalse(s.failed);
        assertEquals(4152L, s.points.get(0).high);
        assertTrue(pending.isEmpty());
    }

    @Test public void keyIsItemPlusTimestep() {
        TimeseriesCache c = cache();
        c.get(4151, "5m");
        c.get(4151, "1h");
        c.get(11802, "5m");
        assertEquals(3, pending.size());
    }

    @Test public void freshWithinTtlThenReloadsButServesStale() {
        TimeseriesCache c = cache();
        c.get(4151, "5m");
        succeedAll();
        now.addAndGet(TimeseriesCache.TTL_MS - 1);
        assertNotNull(c.get(4151, "5m"));
        assertTrue("still fresh", pending.isEmpty());
        now.addAndGet(2);
        assertNotNull("stale data keeps drawing while the reload runs", c.get(4151, "5m"));
        assertEquals(1, pending.size());
        c.get(4151, "5m");
        assertEquals("no duplicate reload", 1, pending.size());
    }

    @Test public void failureKeepsOldPointsAndBacksOffThirtySeconds() {
        TimeseriesCache c = cache();
        c.get(4151, "5m");
        succeedAll();
        now.addAndGet(TimeseriesCache.TTL_MS);
        c.get(4151, "5m");
        failAll();
        TimeseriesCache.Series s = c.get(4151, "5m");
        assertTrue(s.failed);
        assertEquals("previous points kept", 1, s.points.size());
        assertEquals("data age counts from the last good load", TimeseriesCache.TTL_MS, c.nowMs() - s.dataAtMs);
        assertTrue("no retry inside the backoff", pending.isEmpty());
        now.addAndGet(TimeseriesCache.RETRY_MS - 1);
        c.get(4151, "5m");
        assertTrue(pending.isEmpty());
        now.addAndGet(1);
        c.get(4151, "5m");
        assertEquals("retried after the backoff", 1, pending.size());
    }

    @Test public void firstLoadFailureCachesEmptyFailedSeries() {
        TimeseriesCache c = cache();
        c.get(4151, "5m");
        failAll();
        TimeseriesCache.Series s = c.get(4151, "5m");
        assertNotNull(s);
        assertTrue(s.failed);
        assertTrue(s.points.isEmpty());
        assertFalse(c.isLoading(4151, "5m"));
    }

    @Test public void loaderThrowingSynchronouslyDoesNotWedgeTheKey() {
        TimeseriesCache c = new TimeseriesCache((id, step, cb) -> { throw new IllegalStateException("boom"); }, now::get);
        assertNull(c.get(4151, "5m"));
        assertFalse(c.isLoading(4151, "5m"));
        assertTrue(c.get(4151, "5m").failed);
    }

    @Test public void loaderThrowingAnErrorDoesNotWedgeTheKey() {
        TimeseriesCache c = new TimeseriesCache((id, step, cb) -> { throw new NoClassDefFoundError("boom"); }, now::get);
        assertNull(c.get(4151, "5m"));
        assertFalse(c.isLoading(4151, "5m"));
        assertTrue(c.get(4151, "5m").failed);
    }

    private static SparklineDto.Point point(int id) {
        SparklineDto.Point p = new SparklineDto.Point();
        p.ts = 100; p.high = id + 1; p.low = id;
        return p;
    }
}
