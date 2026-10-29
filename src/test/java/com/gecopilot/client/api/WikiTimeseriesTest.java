package com.gecopilot.client.api;

import com.google.gson.Gson;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class WikiTimeseriesTest {
    // Test-only Gson; the plugin hands WikiTimeseries the injected one.
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String SAMPLE = "{\"data\":[{\"timestamp\":1792487400,\"avgHighPrice\":780000,\"avgLowPrice\":770000}]}";

    @Test public void parsesWikiTimeseriesAndNullPrices() {
        WikiTimeseries w = new WikiTimeseries(null, GSON);
        List<SparklineDto.Point> pts = w.parse("{\"data\":["
            + "{\"timestamp\":1792487400,\"avgHighPrice\":780000,\"highPriceVolume\":5,\"avgLowPrice\":770000,\"lowPriceVolume\":2},"
            + "{\"timestamp\":1792487700,\"avgHighPrice\":null,\"highPriceVolume\":0,\"avgLowPrice\":771000,\"lowPriceVolume\":1},"
            + "{\"timestamp\":1792488000,\"avgHighPrice\":null,\"avgLowPrice\":null}"
            + "]}");
        assertEquals(2, pts.size());
        assertEquals(1792487400L, pts.get(0).ts);
        assertEquals(780000L, pts.get(0).high);
        assertEquals(770000L, pts.get(0).low);
        assertEquals(0L, pts.get(1).high);
        assertEquals(771000L, pts.get(1).low);
        assertTrue(w.parse("{\"error\":\"bad\"}").isEmpty());
    }

    @Test public void dropsOutOfRangePrices() {
        WikiTimeseries w = new WikiTimeseries(null, GSON);
        List<SparklineDto.Point> pts = w.parse("{\"data\":["
            + "{\"timestamp\":1,\"avgHighPrice\":2149631130648,\"avgLowPrice\":5},"        // high > the GE ceiling
            + "{\"timestamp\":2,\"avgHighPrice\":-7,\"avgLowPrice\":2147483647},"           // negative high, low = int max
            + "{\"timestamp\":3,\"avgHighPrice\":100000000000000000000,\"avgLowPrice\":0}," // 1e20 would wrap via getAsLong
            + "{\"timestamp\":4,\"avgHighPrice\":\"abc\",\"avgLowPrice\":1e300}"
            + "]}");
        assertEquals("rows with no valid price are skipped", 2, pts.size());
        assertEquals(0L, pts.get(0).high);
        assertEquals(5L, pts.get(0).low);
        assertEquals(0L, pts.get(1).high);
        assertEquals((long) Integer.MAX_VALUE, pts.get(1).low);
    }

    @Test public void keepsPricesAboveIntMaxUpToTheGeCeiling() {
        WikiTimeseries w = new WikiTimeseries(null, GSON);
        List<SparklineDto.Point> pts = w.parse("{\"data\":["
            + "{\"timestamp\":1,\"avgHighPrice\":3000000000,\"avgLowPrice\":2147483648},"
            + "{\"timestamp\":2,\"avgHighPrice\":2149631130647,\"avgLowPrice\":0}]}");
        assertEquals(2, pts.size());
        assertEquals(3_000_000_000L, pts.get(0).high);
        assertEquals(2_147_483_648L, pts.get(0).low);
        assertEquals(2_149_631_130_647L, pts.get(1).high);
    }

    @Test public void parsesVolumesAndRangeChecksTimestamps() {
        WikiTimeseries w = new WikiTimeseries(null, GSON);
        List<SparklineDto.Point> pts = w.parse("{\"data\":["
            + "{\"timestamp\":1792487400,\"avgHighPrice\":10,\"highPriceVolume\":5,\"avgLowPrice\":9,\"lowPriceVolume\":2},"
            + "{\"timestamp\":1e20,\"avgHighPrice\":10,\"avgLowPrice\":9},"
            + "{\"timestamp\":-5,\"avgHighPrice\":10,\"avgLowPrice\":9},"
            + "{\"timestamp\":\"soon\",\"avgHighPrice\":10,\"avgLowPrice\":9},"
            + "{\"timestamp\":1792487700,\"avgHighPrice\":10,\"highPriceVolume\":-3,\"avgLowPrice\":9,\"lowPriceVolume\":1e12}"
            + "]}");
        assertEquals("bad timestamps dropped", 2, pts.size());
        assertEquals(5L, pts.get(0).highVol);
        assertEquals(2L, pts.get(0).lowVol);
        assertEquals(0L, pts.get(1).highVol);
        assertEquals(0L, pts.get(1).lowVol);
    }

    @Test public void bodyCapRejectsOversizedResponses() throws IOException {
        assertEquals("ok", WikiTimeseries.readCapped(ResponseBody.create(JSON, "ok"), 2));
        try {
            WikiTimeseries.readCapped(ResponseBody.create(JSON, "toolong"), 2);
            fail("over cap");
        } catch (IOException expected) { }
        // No Content-Length: the streamed read is bounded too.
        ResponseBody chunked = ResponseBody.create(JSON, -1, new okio.Buffer().writeUtf8("0123456789"));
        try {
            WikiTimeseries.readCapped(chunked, 5);
            fail("over cap without content-length");
        } catch (IOException expected) { }
        assertEquals(1L << 20, WikiTimeseries.MAX_BODY_BYTES);
    }

    @Test public void identifiesItselfAndSetsCallTimeout() {
        assertTrue(WikiTimeseries.USER_AGENT.startsWith("Coffer"));
        assertTrue(WikiTimeseries.URL.startsWith("https://prices.runescape.wiki/"));
        WikiTimeseries w = new WikiTimeseries(new OkHttpClient(), GSON);
        assertEquals(10_000, w.client().callTimeoutMillis());
    }

    @Test public void asyncLoadDeliversPointsThroughTheCache() throws Exception {
        AtomicReference<String> ua = new AtomicReference<>();
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            ua.set(chain.request().header("User-Agent"));
            assertEquals("5m", chain.request().url().queryParameter("timestep"));
            return response(chain.request(), 200, SAMPLE);
        }).build();
        TimeseriesCache cache = new TimeseriesCache(new WikiTimeseries(fake, GSON), () -> System.nanoTime() / 1_000_000L);
        assertNull("get() returns immediately; the call runs on OkHttp's dispatcher", cache.get(4151, "5m"));
        TimeseriesCache.Series s = await(cache, 4151, "5m");
        assertFalse(s.failed);
        assertEquals(780000L, s.points.get(0).high);
        assertEquals(WikiTimeseries.USER_AGENT, ua.get());
    }

    @Test public void asyncHttpErrorMarksSeriesFailed() throws Exception {
        OkHttpClient fake = new OkHttpClient.Builder()
            .addInterceptor(chain -> response(chain.request(), 503, "{}")).build();
        TimeseriesCache cache = new TimeseriesCache(new WikiTimeseries(fake, GSON), () -> System.nanoTime() / 1_000_000L);
        cache.get(4151, "5m");
        TimeseriesCache.Series s = await(cache, 4151, "5m");
        assertTrue(s.failed);
        assertTrue(s.points.isEmpty());
    }

    @Test public void asyncNetworkFailureReachesCallback() throws Exception {
        OkHttpClient fake = new OkHttpClient.Builder()
            .addInterceptor(chain -> { throw new IOException("offline"); }).build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        new WikiTimeseries(fake, GSON).load(4151, "5m", new TimeseriesCache.Callback() {
            @Override public void onSuccess(List<SparklineDto.Point> points) { done.countDown(); }
            @Override public void onFailure(Throwable e) { err.set(e); done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(err.get());
    }

    private static TimeseriesCache.Series await(TimeseriesCache cache, int id, String step) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!cache.isLoading(id, step)) {
                TimeseriesCache.Series s = cache.get(id, step);
                if (s != null) return s;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("load never completed");
    }

    private static Response response(okhttp3.Request req, int code, String body) {
        return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(ResponseBody.create(JSON, body)).build();
    }
}
