package com.gecopilot.client.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fetches an item's price history from the OSRS Wiki real-time prices API, asynchronously. */
public final class WikiTimeseries implements TimeseriesCache.Loader {
    static final String URL = "https://prices.runescape.wiki/api/v1/osrs/timeseries";
    static final String USER_AGENT = "Coffer RuneLite plugin - github.com/Paul2357/ge-copilot";
    static final long CALL_TIMEOUT_SEC = 10;
    /** A full 365-point series is ~40 KB; anything near this is not a timeseries. */
    static final long MAX_BODY_BYTES = 1L << 20;

    private final OkHttpClient http;
    private final Gson gson;

    /** Derives a client with a call timeout once; calls go through OkHttp's dispatcher, never the caller's thread. */
    public WikiTimeseries(OkHttpClient http, Gson gson) {
        this.http = http == null ? null : http.newBuilder().callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS).build();
        this.gson = gson;
    }

    OkHttpClient client() { return http; }

    @Override
    public void load(int itemId, String timestep, TimeseriesCache.Callback cb) {
        HttpUrl url = HttpUrl.get(URL).newBuilder()
            .addQueryParameter("id", Integer.toString(itemId))
            .addQueryParameter("timestep", timestep)
            .build();
        Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { cb.onFailure(e); }

            @Override public void onResponse(Call call, Response res) {
                List<SparklineDto.Point> pts;
                try (Response r = res) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("wiki timeseries HTTP " + r.code());
                    pts = parse(readCapped(r.body(), MAX_BODY_BYTES));
                } catch (Throwable t) {
                    cb.onFailure(t);
                    return;
                }
                cb.onSuccess(pts);
            }
        });
    }

    /** Reads the body as UTF-8, failing instead of buffering more than {@code max} bytes. */
    static String readCapped(ResponseBody body, long max) throws IOException {
        if (body.contentLength() > max) throw new IOException("wiki timeseries body too large: " + body.contentLength());
        BufferedSource src = body.source();
        if (src.request(max + 1)) throw new IOException("wiki timeseries body over " + max + " bytes");
        return src.getBuffer().readString(StandardCharsets.UTF_8);
    }

    /** {data:[{timestamp, avgHighPrice, avgLowPrice, ...}]} to points; null or out-of-range prices become 0. */
    public List<SparklineDto.Point> parse(String json) {
        List<SparklineDto.Point> out = new ArrayList<>();
        JsonObject o = gson.fromJson(json, JsonObject.class);
        if (o == null || !o.has("data") || !o.get("data").isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray("data")) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            SparklineDto.Point pt = new SparklineDto.Point();
            pt.ts = timestamp(p);
            pt.high = price(p, "avgHighPrice");
            pt.low = price(p, "avgLowPrice");
            if (pt.ts <= 0 || (pt.high == 0 && pt.low == 0)) continue;
            pt.highVol = count(p, "highPriceVolume");
            pt.lowVol = count(p, "lowPriceVolume");
            pt.mid = pt.high > 0 && pt.low > 0 ? (pt.high + pt.low) / 2.0 : Math.max(pt.high, pt.low);
            out.add(pt);
        }
        return out;
    }

    /**
     * Highest GE price: coins plus platinum tokens, since the 30 September 2026 update
     * (https://oldschool.runescape.wiki/w/Grand_Exchange, "GE Improvements: Beyond Max Cash").
     */
    static final long MAX_GE_PRICE = 2_149_631_130_647L;

    /** A GE price in 1..{@link #MAX_GE_PRICE}, else 0 (missing). */
    static long price(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        double d;
        try { d = e.getAsDouble(); } catch (NumberFormatException ex) { return 0; }
        // Range-check as a double: a huge integer read via getAsLong() can wrap back into range.
        return d >= 1 && d <= MAX_GE_PRICE ? (long) d : 0;
    }

    /** Latest plausible epoch second (year 2286); anything else is treated as missing. */
    static final double MAX_TS = 9_999_999_999d;

    /** Unix seconds, range-checked as a double like prices; 0 when missing or out of range. */
    static long timestamp(JsonObject o) {
        double d = dbl(o, "timestamp");
        return d >= 1 && d <= MAX_TS ? (long) d : 0;
    }

    /** A trade count in 0..Integer.MAX_VALUE, else 0. */
    static long count(JsonObject o, String key) {
        double d = dbl(o, key);
        return d >= 0 && d <= Integer.MAX_VALUE ? (long) d : 0;
    }

    private static double dbl(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return Double.NaN;
        try { return e.getAsDouble(); } catch (NumberFormatException ex) { return Double.NaN; }
    }
}
