package com.gecopilot.client.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;

public class ApiClient {
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final Gson gson;
    private final String baseUrl;
    private volatile String token;
    private volatile String tier;
    // Start of the current flipping session (reset on each successful login); the "Session" P&L
    // window on the dashboard is measured from here.
    private volatile long sessionStart = System.currentTimeMillis();
    // Selected RS account for P&L views; null = all accounts. Applied to /api/dashboard and /api/log.
    private volatile String account = null;

    public ApiClient(OkHttpClient http, Gson gson, String baseUrl) {
        this.http = http;
        this.gson = gson;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public boolean hasToken() { return token != null; }
    public String tier() { return tier; }
    public void setToken(String t) { this.token = t; }

    /** Restart the "Session" P&L window (call on login / when the user resets the session). */
    public void resetSession() { this.sessionStart = System.currentTimeMillis(); }

    /** Select the RS account for P&L views; "All accounts"/null/empty = all. */
    public void setAccount(String a) {
        this.account = (a == null || a.isEmpty() || "All accounts".equals(a)) ? null : a;
    }
    private String accountParam(String sep) {
        if (account == null) return "";
        try { return sep + "account=" + java.net.URLEncoder.encode(account, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
    public void clearToken() { this.token = null; this.tier = null; }

    public LoginResult login(String user, String pass) {
        JsonObject b = new JsonObject();
        b.addProperty("username", user);
        b.addProperty("password", pass);
        Request req = new Request.Builder().url(baseUrl + "/auth/login")
            .post(RequestBody.create(JSON, b.toString())).build();
        try (Response res = http.newCall(req).execute()) {
            LoginResult r = (res.body() != null) ? parseLogin(res.body().string()) : new LoginResult();
            r.reached = true;
            r.code = res.code();
            if (r.ok) { token = r.token; tier = r.tier; sessionStart = System.currentTimeMillis(); }
            return r;
        } catch (Exception e) {
            LoginResult r = new LoginResult(); // timeout / unreachable (e.g. Render cold start)
            r.reached = false;
            return r;
        }
    }

    public LoginResult register(String user, String pass) {
        JsonObject b = new JsonObject();
        b.addProperty("username", user);
        b.addProperty("password", pass);
        Request req = new Request.Builder().url(baseUrl + "/auth/register")
            .post(RequestBody.create(JSON, b.toString())).build();
        try (Response res = http.newCall(req).execute()) {
            LoginResult r = (res.body() != null) ? parseLogin(res.body().string()) : new LoginResult();
            r.reached = true;
            r.code = res.code();
            if (r.ok) { token = r.token; tier = r.tier; sessionStart = System.currentTimeMillis(); }
            return r;
        } catch (Exception e) {
            LoginResult r = new LoginResult();
            r.reached = false;
            return r;
        }
    }

    public Me me() {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/me")
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (!res.isSuccessful() || res.body() == null) return null;
            Me m = parseMe(res.body().string());
            if (m != null) tier = m.tier;
            return m;
        } catch (Exception e) { return null; }
    }

    /** Unauthenticated F2P preview (no-account teaser). Never throws — returns empty on any failure. */
    public java.util.List<PreviewFlip> preview() {
        Request req = new Request.Builder().url(baseUrl + "/api/preview").get().build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) return java.util.List.of();
            JsonObject o = gson.fromJson(res.body().string(), JsonObject.class);
            if (o == null || !o.has("flips")) return java.util.List.of();
            PreviewFlip[] arr = gson.fromJson(o.get("flips"), PreviewFlip[].class);
            return arr == null ? java.util.List.of() : java.util.Arrays.asList(arr);
        } catch (Exception e) { return java.util.List.of(); }
    }

    public LoginResult parseLogin(String json) {
        LoginResult r = new LoginResult();
        try {
            JsonObject o = gson.fromJson(json, JsonObject.class);
            if (o != null && o.has("token")) {
                r.ok = true; r.token = o.get("token").getAsString();
                r.tier = o.has("tier") ? o.get("tier").getAsString() : null;
            }
        } catch (Exception ignored) { }
        return r;
    }

    public Me parseMe(String json) {
        try {
            JsonObject o = gson.fromJson(json, JsonObject.class);
            if (o == null || !o.has("username")) return null;
            Me m = new Me();
            m.username = o.get("username").getAsString();
            m.tier = o.has("tier") ? o.get("tier").getAsString() : null;
            return m;
        } catch (Exception e) { return null; }
    }

    public SyncResult sync(long bankroll, int slots, String pace, String access,
                           int minVolume, int minMargin, double minRoi, int fillTarget,
                           java.util.List<com.gecopilot.client.trade.OpenOfferDto> offers) {
        if (token == null) return new SyncResult();
        JsonObject b = new JsonObject();
        b.addProperty("bankroll", bankroll);
        b.addProperty("slots", slots);
        b.addProperty("pace", pace);
        b.addProperty("access", access);
        b.addProperty("minVolume", minVolume);
        b.addProperty("minMargin", minMargin);
        b.addProperty("minRoi", minRoi);
        b.addProperty("fillTarget", fillTarget);
        b.add("openOffers", gson.toJsonTree(offers == null ? java.util.Collections.emptyList() : offers));
        try (Response res = authed("/api/flips", b.toString()).execute()) {
            if (res.code() == 401) { token = null; return new SyncResult(); }
            if (!res.isSuccessful() || res.body() == null) return new SyncResult();
            SyncResult r = parseSync(res.body().string());
            if (r.tier != null) tier = r.tier;
            return r;
        } catch (Exception e) { return new SyncResult(); }
    }

    public List<LogRowDto> log() {
        if (token == null) return new ArrayList<>();
        Request req = new Request.Builder().url(baseUrl + "/api/log" + accountParam("?"))
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return new ArrayList<>(); }
            if (!res.isSuccessful() || res.body() == null) return new ArrayList<>();
            return parseLog(res.body().string());
        } catch (Exception e) { return new ArrayList<>(); }
    }

    /** Returns true only when the server accepted the trade, so callers can retry on failure. */
    public boolean postTrade(int itemId, boolean buy, int qty, int price, long ts) {
        return postTrade(itemId, buy, qty, price, ts, null);
    }

    /** account = RS display name that made the fill (nullable → server buckets it as "Unknown"). */
    public boolean postTrade(int itemId, boolean buy, int qty, int price, long ts, String account) {
        if (token == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("itemId", itemId);
        b.addProperty("side", buy ? "BUY" : "SELL");
        b.addProperty("qty", qty);
        b.addProperty("price", price);
        b.addProperty("ts", ts);
        if (account != null && !account.isEmpty()) b.addProperty("account", account);
        try (Response res = authed("/api/trades", b.toString()).execute()) {
            if (res.code() == 401) { token = null; return false; }
            return res.isSuccessful();
        } catch (Exception e) { return false; }
    }

    /** Post a GE-History-recovered trade (source=history). Separate from live fills. */
    public boolean postHistoryTrade(int itemId, boolean buy, int qty, int price, long ts) {
        if (token == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("itemId", itemId); b.addProperty("side", buy ? "BUY" : "SELL");
        b.addProperty("qty", qty); b.addProperty("price", price); b.addProperty("ts", ts);
        b.addProperty("source", "history");
        try (Response res = authed("/api/trades", b.toString()).execute()) {
            if (res.code() == 401) { token = null; return false; }
            return res.isSuccessful();
        } catch (Exception e) { return false; }
    }

    /** The user's recorded raw trades (GET /api/trades/mine), for GE History dedup. */
    public java.util.List<com.gecopilot.client.trade.GeHistoryReconcile.Recorded> recentTrades() {
        java.util.List<com.gecopilot.client.trade.GeHistoryReconcile.Recorded> out = new ArrayList<>();
        if (token == null) return out;
        Request req = new Request.Builder().url(baseUrl + "/api/trades/mine")
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return out; }
            if (res.code() != 200 || res.body() == null) return out;
            for (JsonElement e : gson.fromJson(res.body().string(), com.google.gson.JsonArray.class)) {
                JsonObject o = e.getAsJsonObject();
                var r = new com.gecopilot.client.trade.GeHistoryReconcile.Recorded();
                r.itemId = o.get("itemId").getAsInt(); r.buy = o.get("buy").getAsBoolean();
                r.qty = o.get("qty").getAsInt(); r.price = o.get("price").getAsInt();
                out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Per-item quote for the GE offer-setup overlay. Returns null when unknown / not entitled (204). */
    public QuoteDto quote(int itemId) {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/quote/" + itemId)
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (res.code() != 200 || res.body() == null) return null;
            return gson.fromJson(res.body().string(), QuoteDto.class);
        } catch (Exception e) { return null; }
    }

    public List<CloudFlip> find(String q) {
        if (token == null || q == null || q.trim().isEmpty()) return new ArrayList<>();
        String url = baseUrl + "/api/find?q=" + java.net.URLEncoder.encode(q.trim(), java.nio.charset.StandardCharsets.UTF_8);
        Request req = new Request.Builder().url(url).header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return new ArrayList<>(); }
            if (res.code() != 200 || res.body() == null) return new ArrayList<>();
            return parseFlipArray(res.body().string());
        } catch (Exception e) { return new ArrayList<>(); }
    }

    /** /api/find returns a bare JSON array of flip rows (not wrapped in {flips:...}). */
    public List<CloudFlip> parseFlipArray(String json) {
        List<CloudFlip> out = new ArrayList<>();
        try {
            for (JsonElement e : gson.fromJson(json, com.google.gson.JsonArray.class)) out.add(gson.fromJson(e, CloudFlip.class));
        } catch (Exception ignored) { }
        return out;
    }

    public static String alertsConfigBody(String webhook, boolean enabled, boolean recover,
                                          boolean position, boolean crash, int cooldownMin,
                                          boolean quietEnabled, int quietStart, int quietEnd, String tz) {
        JsonObject b = new JsonObject();
        b.addProperty("webhookUrl", webhook == null ? "" : webhook);
        b.addProperty("enabled", enabled);
        b.addProperty("recover", recover);
        b.addProperty("position", position);
        b.addProperty("crash", crash);
        b.addProperty("cooldownMin", cooldownMin);
        b.addProperty("quietEnabled", quietEnabled);
        b.addProperty("quietStart", quietStart);
        b.addProperty("quietEnd", quietEnd);
        b.addProperty("tz", tz == null ? "" : tz);
        return b.toString();
    }

    public void alertsConfig(String webhook, boolean enabled, boolean recover,
                             boolean position, boolean crash, int cooldownMin,
                             boolean quietEnabled, int quietStart, int quietEnd, String tz) {
        if (token == null) return;
        try (Response res = authed("/api/alerts/config",
                alertsConfigBody(webhook, enabled, recover, position, crash, cooldownMin,
                        quietEnabled, quietStart, quietEnd, tz)).execute()) {
            if (res.code() == 401) token = null;
        } catch (Exception ignored) { }
    }

    public boolean testAlert() {
        if (token == null) return false;
        try (Response res = authed("/api/alerts/test", "{}").execute()) {
            if (!res.isSuccessful() || res.body() == null) return false;
            JsonObject o = gson.fromJson(res.body().string(), JsonObject.class);
            return o != null && o.has("ok") && o.get("ok").getAsBoolean();
        } catch (Exception e) { return false; }
    }

    public void postOutcome(com.gecopilot.client.trade.Outcome o) {
        if (token == null || o == null) return;
        JsonObject b = new JsonObject();
        b.addProperty("itemId", o.itemId);
        b.addProperty("buy", o.buy);
        b.addProperty("price", o.price);
        b.addProperty("sold", o.sold);
        b.addProperty("total", o.total);
        b.addProperty("waitedMs", o.waitedMs);
        try (Response res = authed("/api/outcomes", b.toString()).execute()) {
            if (res.code() == 401) token = null;
        } catch (Exception ignored) { }
    }

    public AlertsStatus alertsStatus() {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/alerts/status")
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() != 200 || res.body() == null) return null;
            return gson.fromJson(res.body().string(), AlertsStatus.class);
        } catch (Exception e) { return null; }
    }

    /** Unauthenticated /health -> server version, or null if unreachable. */
    public String health() {
        Request req = new Request.Builder().url(baseUrl + "/health").get().build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) return null;
            JsonObject o = gson.fromJson(res.body().string(), JsonObject.class);
            return o != null && o.has("version") ? o.get("version").getAsString() : "";
        } catch (Exception e) { return null; }
    }

    public void setGoal(long goalGp, long deadline) {
        if (token == null) return;
        JsonObject b = new JsonObject();
        b.addProperty("goalGp", goalGp);
        b.addProperty("deadline", deadline);
        try (Response res = authed("/api/goal", b.toString()).execute()) {
            if (res.code() == 401) token = null;
        } catch (Exception ignored) { }
    }

    public void watch(int itemId, boolean on) {
        if (token == null) return;
        JsonObject b = new JsonObject();
        b.addProperty("itemId", itemId);
        b.addProperty("on", on);
        try (Response res = authed("/api/watch", b.toString()).execute()) {
            if (res.code() == 401) token = null;
        } catch (Exception ignored) { }
    }

    public Dashboard dashboard() {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/dashboard?sessionStart=" + sessionStart + accountParam("&"))
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (!res.isSuccessful() || res.body() == null) return null;
            return parseDashboard(res.body().string());
        } catch (Exception e) { return null; }
    }

    private Call authed(String path, String body) {
        Request req = new Request.Builder().url(baseUrl + path)
            .header("Authorization", "Bearer " + token)
            .post(RequestBody.create(JSON, body)).build();
        return http.newCall(req);
    }

    // Static parse helpers (unit-tested without network).
    public List<CloudFlip> parseFlips(String json) {
        List<CloudFlip> out = new ArrayList<>();
        try {
            JsonObject o = gson.fromJson(json, JsonObject.class);
            if (o == null || !o.has("flips")) return out;
            for (JsonElement el : o.getAsJsonArray("flips")) out.add(gson.fromJson(el, CloudFlip.class));
        } catch (Exception ignored) { }
        return out;
    }

    public Dashboard parseDashboard(String json) {
        try { return gson.fromJson(json, Dashboard.class); }
        catch (Exception e) { return null; }
    }

    public SyncResult parseSync(String json) {
        SyncResult r = new SyncResult();
        try {
            JsonObject o = gson.fromJson(json, JsonObject.class);
            if (o == null) return r;
            if (o.has("tier") && !o.get("tier").isJsonNull()) r.tier = o.get("tier").getAsString();
            if (o.has("flips")) for (JsonElement e : o.getAsJsonArray("flips")) r.flips.add(gson.fromJson(e, CloudFlip.class));
            if (o.has("plan")) for (JsonElement e : o.getAsJsonArray("plan")) r.plan.add(gson.fromJson(e, PlanRowDto.class));
            if (o.has("positions")) for (JsonElement e : o.getAsJsonArray("positions")) r.positions.add(gson.fromJson(e, PositionDto.class));
            if (o.has("alch")) for (JsonElement e : o.getAsJsonArray("alch")) r.alch.add(gson.fromJson(e, AlchDto.class));
            if (o.has("crash")) for (JsonElement e : o.getAsJsonArray("crash")) r.crash.add(gson.fromJson(e, CrashDto.class));
            if (o.has("watch")) for (JsonElement e : o.getAsJsonArray("watch")) r.watch.add(gson.fromJson(e, WatchDto.class));
        } catch (Exception ignored) { }
        return r;
    }

    public List<LogRowDto> parseLog(String json) {
        List<LogRowDto> out = new ArrayList<>();
        try {
            for (JsonElement e : gson.fromJson(json, com.google.gson.JsonArray.class)) out.add(gson.fromJson(e, LogRowDto.class));
        } catch (Exception ignored) { }
        return out;
    }
}
