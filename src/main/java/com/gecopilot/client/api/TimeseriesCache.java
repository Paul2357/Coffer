package com.gecopilot.client.api;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory price history for the GE offer chart, keyed by item + wiki timestep. {@link #get} never
 * blocks: it returns whatever is cached (possibly stale) and starts at most one asynchronous load per
 * key when the entry is missing or older than the TTL. A failed load is retried after a shorter delay.
 */
@Slf4j
public final class TimeseriesCache {
    /** Starts a load and reports back on any thread; must not block the caller. */
    public interface Loader {
        void load(int itemId, String timestep, Callback callback);
    }

    public interface Callback {
        void onSuccess(List<SparklineDto.Point> points);
        void onFailure(Throwable e);
    }

    /** One cached series. {@code failed} marks a load error; {@code points} then keeps the previous data. */
    public static final class Series {
        public final List<SparklineDto.Point> points;
        public final long fetchedAtMs;
        /** When {@code points} were loaded (same clock); older than fetchedAtMs after a failed refresh. */
        public final long dataAtMs;
        public final boolean failed;

        Series(List<SparklineDto.Point> points, long fetchedAtMs, long dataAtMs, boolean failed) {
            this.points = points;
            this.fetchedAtMs = fetchedAtMs;
            this.dataAtMs = dataAtMs;
            this.failed = failed;
        }
    }

    public static final long TTL_MS = 3 * 60_000L;
    public static final long RETRY_MS = 30_000L;
    static final int MAX_ENTRIES = 32;

    private final Loader loader;
    private final LongSupplier clockMs;
    private final Map<Key, Series> cache = new ConcurrentHashMap<>();
    private final Set<Key> inFlight = ConcurrentHashMap.newKeySet();

    /** {@code clockMs} is a monotonic millisecond clock (the plugin passes System.nanoTime()/1e6). */
    public TimeseriesCache(Loader loader, LongSupplier clockMs) {
        this.loader = loader;
        this.clockMs = clockMs;
    }

    /** Cached series for this item/timestep, or null before the first load lands. */
    public Series get(int itemId, String timestep) {
        Key key = new Key(itemId, timestep);
        Series s = cache.get(key);
        boolean fresh = s != null && clockMs.getAsLong() - s.fetchedAtMs < (s.failed ? RETRY_MS : TTL_MS);
        if (!fresh && inFlight.add(key)) start(key);
        return s;
    }

    /** The cache's clock, for ages computed against {@link Series#dataAtMs}. */
    public long nowMs() { return clockMs.getAsLong(); }

    boolean isLoading(int itemId, String timestep) {
        return inFlight.contains(new Key(itemId, timestep));
    }

    private void start(Key key) {
        Callback cb = new Callback() {
            @Override public void onSuccess(List<SparklineDto.Point> points) {
                try {
                    long now = clockMs.getAsLong();
                    put(key, new Series(points == null ? Collections.emptyList() : points, now, now, false));
                } finally {
                    inFlight.remove(key);
                }
            }

            @Override public void onFailure(Throwable e) {
                try {
                    log.debug("[Coffer] price history load failed for {} {}, retrying in {}s",
                        key.itemId, key.timestep, RETRY_MS / 1000, e);
                    Series prev = cache.get(key);
                    long now = clockMs.getAsLong();
                    put(key, prev == null
                        ? new Series(Collections.emptyList(), now, now, true)
                        : new Series(prev.points, now, prev.dataAtMs, true));
                } finally {
                    inFlight.remove(key);
                }
            }
        };
        try {
            loader.load(key.itemId, key.timestep, cb);
        } catch (Throwable e) { // an Error too, or the key stays in flight forever
            cb.onFailure(e);
        }
    }

    private void put(Key key, Series s) {
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(key)) cache.clear();
        cache.put(key, s);
    }

    private static final class Key {
        final int itemId;
        final String timestep;

        Key(int itemId, String timestep) {
            this.itemId = itemId;
            this.timestep = timestep;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return k.itemId == itemId && k.timestep.equals(timestep);
        }

        @Override public int hashCode() { return Objects.hash(itemId, timestep); }
    }
}
