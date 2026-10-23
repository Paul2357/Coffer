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
    /** Hint only — the server does not authorize on this. See ClientHint on the server. */
    private static final String CLIENT_HEADER = "X-Coffer-Client";
    private static final String CLIENT_PLUGIN = "plugin";

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

    /** Connection state as seen by the panel. Derived centrally from every API response so a
     *  401/5xx/network failure can never silently masquerade as "no data". */
    public enum ConnState { OK, SIGNED_OUT, UNREACHABLE }
    private volatile ConnState connState = ConnState.OK;
    private volatile java.util.function.Consumer<ConnState> connListener = s -> {};

    public ConnState connState() { return connState; }
    public void onConnState(java.util.function.Consumer<ConnState> l) { this.connListener = l != null ? l : s -> {}; }

    private void setConn(ConnState s) {
        if (connState == s) return;
        connState = s;
        try { connListener.accept(s); } catch (RuntimeException ignored) { }
    }

    /**
     * Observes every response. Only authenticated calls (Authorization header present) can flip the
     * state to SIGNED_OUT; the server has no refresh endpoint and the plugin stores no password, so
     * no silent re-auth is possible: the token is dropped and the panel shows the login card.
     */
    okhttp3.Response observe(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
        Request req = chain.request();
        boolean authed = req.header("Authorization") != null;
        okhttp3.Response res;
        try { res = chain.proceed(req); }
        catch (java.io.IOException e) { setConn(ConnState.UNREACHABLE); throw e; }
        int c = res.code();
        if (authed && c >= 200 && c < 300) maybeSwapToken(req.header("Authorization"), res.header(RENEW_HEADER));
        if (c == 401 && authed) {
            // Ignore a 401 for a token that has since been replaced (e.g. a re-login raced it).
            String sent = req.header("Authorization");
            synchronized (this) { if (token != null && sent.equals("Bearer " + token)) { token = null; tier = null; } }
            if (token == null) setConn(ConnState.SIGNED_OUT);
        } else if (c >= 500) {
            setConn(ConnState.UNREACHABLE);
        } else if (connState == ConnState.UNREACHABLE || (connState == ConnState.SIGNED_OUT && token != null)) {
            setConn(ConnState.OK);
        }
        return res;
    }

    /** Plugin version on every Coffer request, so the server can tell which clients need updating. */
    public static final String VERSION_HEADER = "X-Coffer-Version";

    static okhttp3.Response stampVersion(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
        return chain.proceed(chain.request().newBuilder()
            .header(VERSION_HEADER, com.gecopilot.client.ServerConfig.VERSION).build());
    }

    /** Rolling-session header (server TokenRenewer). */
    public static final String RENEW_HEADER = "X-Coffer-Token";
    private volatile java.util.function.Consumer<String> tokenListener = t -> {};
    /** Called (off the EDT, on the request thread) with each renewed token so it can be persisted. */
    public void onTokenRenewed(java.util.function.Consumer<String> l) { this.tokenListener = l != null ? l : t -> {}; }

    /**
     * Atomically replace the token iff the request was made with the token that is still current;
     * a renewal answering a stale request (after a logout/re-login) is dropped.
     */
    void maybeSwapToken(String sentAuth, String renewed) {
        if (renewed == null || sentAuth == null) return;
        String fresh = renewed.trim();
        if (fresh.isEmpty() || fresh.indexOf(' ') >= 0 || fresh.split("\\.", -1).length != 3) return; // JWT shape only
        // Swap AND persist under the lock signOut/clearToken take: a logout either happens before
        // (token null -> no swap, nothing saved) or after (it clears what we saved). Never between.
        synchronized (this) {
            if (token == null || !sentAuth.equals("Bearer " + token) || fresh.equals(token)) return;
            token = fresh;
            try { tokenListener.accept(fresh); } catch (RuntimeException ignored) { }
        }
    }

    String currentTokenForTest() { return token; }

    public ApiClient(OkHttpClient http, Gson gson, String baseUrl) {
        this.http = http == null ? null : http.newBuilder().addInterceptor(ApiClient::stampVersion)
            .addInterceptor(this::observe).build();
        // Same client, shorter deadline (a check that hangs is itself the answer), and without the
        // conn-state interceptor: a reachable /api/status must not clear the "unreachable" state that
        // a failing authed call just set.
        this.statusHttp = http == null ? null : http.newBuilder().addInterceptor(ApiClient::stampVersion)
            .callTimeout(STATUS_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS).build();
        this.gson = gson;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public boolean hasToken() { return token != null; }

    /** Coffer user id of the current session (the token's subject), or null when signed out. */
    public synchronized Long userId() { return subjectOf(token); }

    /**
     * The JWT's {@code sub} claim as a user id; null if absent or malformed. Not verified (the server
     * does that). Parses with the client's Gson: the Plugin Hub rejects plugins that create their own.
     */
    Long subjectOf(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) return null;
        try {
            String json = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
            JsonElement sub = gson.fromJson(json, JsonObject.class).get("sub");
            return sub == null || sub.isJsonNull() ? null : Long.parseLong(sub.getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }
    public String tier() { return tier; }
    public synchronized void setToken(String t) { this.token = t; if (t != null && connState == ConnState.SIGNED_OUT) setConn(ConnState.OK); }

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
    public synchronized void clearToken() { this.token = null; this.tier = null; }

    /**
     * Sign out atomically with respect to a rolling-token swap: the token is cleared and the
     * persisted session removed under the same lock maybeSwapToken saves under, so a renewal that
     * races a logout can never resurrect the session.
     */
    public synchronized void signOut(Runnable clearStore) {
        this.token = null; this.tier = null;
        if (clearStore != null) clearStore.run();
    }

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
            if (r.ok) { token = r.token; tier = r.tier; sessionStart = System.currentTimeMillis(); setConn(ConnState.OK); }
            return r;
        } catch (Exception e) {
            LoginResult r = new LoginResult(); // timeout / unreachable (e.g. Render cold start)
            r.reached = false;
            return r;
        }
    }

    public LoginResult register(String user, String pass, String email) {
        JsonObject b = new JsonObject();
        b.addProperty("username", user);
        b.addProperty("password", pass);
        b.addProperty("email", email);
        Request req = new Request.Builder().url(baseUrl + "/auth/register")
            .post(RequestBody.create(JSON, b.toString())).build();
        try (Response res = http.newCall(req).execute()) {
            LoginResult r = (res.body() != null) ? parseLogin(res.body().string()) : new LoginResult();
            r.reached = true;
            r.code = res.code();
            if (r.ok) { token = r.token; tier = r.tier; sessionStart = System.currentTimeMillis(); setConn(ConnState.OK); }
            return r;
        } catch (Exception e) {
            LoginResult r = new LoginResult();
            r.reached = false;
            return r;
        }
    }

    /** POST /auth/forgot. Server always returns 200 with generic copy (anti-enumeration) — the only
     *  thing worth reporting back to the caller is whether we reached it at all. */
    public boolean forgot(String email) {
        JsonObject b = new JsonObject();
        b.addProperty("email", email);
        Request req = new Request.Builder().url(baseUrl + "/auth/forgot")
            .post(RequestBody.create(JSON, b.toString())).build();
        try (Response res = http.newCall(req).execute()) {
            return res.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public ResetResult reset(String token, String newPassword) {
        JsonObject b = new JsonObject();
        b.addProperty("token", token);
        b.addProperty("newPassword", newPassword);
        Request req = new Request.Builder().url(baseUrl + "/auth/reset")
            .post(RequestBody.create(JSON, b.toString())).build();
        ResetResult r = new ResetResult();
        try (Response res = http.newCall(req).execute()) {
            r.reached = true;
            r.code = res.code();
            JsonObject o = res.body() != null ? gson.fromJson(res.body().string(), JsonObject.class) : null;
            r.ok = res.isSuccessful() && o != null && o.has("ok") && o.get("ok").getAsBoolean();
            if (!r.ok && o != null && o.has("error")) r.error = o.get("error").getAsString();
            return r;
        } catch (Exception e) {
            r.reached = false;
            return r;
        }
    }

    public Me me() {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/me")
            .header("Authorization", "Bearer " + token)
            .header(CLIENT_HEADER, CLIENT_PLUGIN).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (!res.isSuccessful() || res.body() == null) return null;
            Me m = parseMe(res.body().string());
            if (m != null) tier = m.tier;
            return m;
        } catch (Exception e) { return null; }
    }

    /** Set the account's recovery email — the onboarding-gate step (no currentPassword: this route
     *  skips re-auth for a first-set account, which is exactly the gate's situation). Returns true
     *  only when the server accepted it (Iris H3: format/collision/throttle all come back false,
     *  never a distinct reason — the caller shows one generic message either way). */
    public boolean setRecoveryEmail(String email) {
        if (token == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("email", email);
        try (Response res = authed("/api/me/email", b.toString()).execute()) {
            if (res.code() == 401) { token = null; return false; }
            if (!res.isSuccessful() || res.body() == null) return false;
            JsonObject o = gson.fromJson(res.body().string(), JsonObject.class);
            return o != null && o.has("ok") && o.get("ok").getAsBoolean();
        } catch (Exception e) { return false; }
    }

    /** Record ToS/Privacy assent — the other onboarding-gate step. The server stamps its own
     *  version; nothing to send. Returns true only when the server accepted it. */
    public boolean acceptTos() {
        if (token == null) return false;
        try (Response res = authed("/api/me/tos", "{}").execute()) {
            if (res.code() == 401) { token = null; return false; }
            if (!res.isSuccessful() || res.body() == null) return false;
            JsonObject o = gson.fromJson(res.body().string(), JsonObject.class);
            return o != null && o.has("ok") && o.get("ok").getAsBoolean();
        } catch (Exception e) { return false; }
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
                r.needsOnboarding = o.has("needsOnboarding") && o.get("needsOnboarding").getAsBoolean();
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
            m.email = o.has("email") ? o.get("email").getAsString() : null;
            m.needsOnboarding = o.has("needsOnboarding") && o.get("needsOnboarding").getAsBoolean();
            m.pluginSeenAt = longField(o, "pluginSeenAt");
            m.offersSeenAt = longField(o, "offersSeenAt");
            m.firstLiveTradeAt = longField(o, "firstLiveTradeAt");
            return m;
        } catch (Exception e) { return null; }
    }

    public SyncResult sync(long bankroll, int slots, String pace, String access,
                           int minVolume, int minMargin, double minRoi, int fillTarget,
                           java.util.List<com.gecopilot.client.trade.OpenOfferDto> offers,
                           boolean offersKnown) {
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
        // false until this session has observed the GE. The server then will not treat an empty
        // list as "the user has no offers" and wipe the saved ones.
        b.addProperty("offersKnown", offersKnown);
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

    /** Submit beta feedback (kind = bug/feature/other). Returns true on 2xx. */
    public boolean feedback(String kind, String message, String contact, String version) {
        if (token == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("kind", kind);
        b.addProperty("message", message);
        if (contact != null && !contact.isEmpty()) b.addProperty("contact", contact);
        if (version != null && !version.isEmpty()) b.addProperty("version", version);
        try (Response res = authed("/api/feedback", b.toString()).execute()) {
            if (res.code() == 401) { token = null; return false; }
            return res.isSuccessful();
        } catch (Exception e) { return false; }
    }

    /** Returns true only when the server accepted the trade, so callers can retry on failure. */
    public boolean postTrade(int itemId, boolean buy, int qty, int price, long ts) {
        return postTrade(itemId, buy, qty, price, ts, null);
    }

    /** account = RS display name that made the fill (nullable → server buckets it as "Unknown"). */
    public boolean postTrade(int itemId, boolean buy, int qty, int price, long ts, String account) {
        return postTrade(itemId, buy, qty, price, ts, account, null);
    }

    /** fillKey = stable identity of the fill (see OfferCapture); the server treats a repeat as a no-op. */
    public boolean postTrade(int itemId, boolean buy, int qty, int price, long ts, String account, String fillKey) {
        if (token == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("itemId", itemId);
        b.addProperty("side", buy ? "BUY" : "SELL");
        b.addProperty("qty", qty);
        b.addProperty("price", price);
        b.addProperty("ts", ts);
        if (account != null && !account.isEmpty()) b.addProperty("account", account);
        if (fillKey != null && !fillKey.isEmpty()) b.addProperty("fillKey", fillKey);
        try (Response res = authed("/api/trades", b.toString()).execute()) {
            if (res.code() == 401) { token = null; return false; }
            return res.isSuccessful();
        } catch (Exception e) { return false; }
    }

    /**
     * Rewrite one of the user's own trades from the offer price to the GE History fill price.
     * The server updates the newest row matching item/side/qty/fromPrice and no other user's row.
     */
    public boolean correctTrade(int itemId, boolean buy, int qty, int fromPrice, int toPrice) {
        if (token == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("itemId", itemId);
        b.addProperty("side", buy ? "BUY" : "SELL");
        b.addProperty("qty", qty);
        b.addProperty("fromPrice", fromPrice);
        b.addProperty("toPrice", toPrice);
        try (Response res = authed("/api/trades/correct", b.toString()).execute()) {
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

    /**
     * The user's recorded raw trades (GET /api/trades/mine), for GE History dedup.
     * Null when the request failed — an empty list means the account really has no trades.
     * Callers must not treat a failure as "nothing recorded", or History would import everything.
     */
    public java.util.List<com.gecopilot.client.trade.GeHistoryReconcile.Recorded> recentTrades() {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/trades/mine")
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (res.code() != 200 || res.body() == null) return null;
            java.util.List<com.gecopilot.client.trade.GeHistoryReconcile.Recorded> out = new ArrayList<>();
            for (JsonElement e : gson.fromJson(res.body().string(), com.google.gson.JsonArray.class)) {
                JsonObject o = e.getAsJsonObject();
                var r = new com.gecopilot.client.trade.GeHistoryReconcile.Recorded();
                r.itemId = o.get("itemId").getAsInt(); r.buy = o.get("buy").getAsBoolean();
                r.qty = o.get("qty").getAsInt(); r.price = o.get("price").getAsInt();
                r.ts = o.has("ts") && !o.get("ts").isJsonNull() ? o.get("ts").getAsLong() : 0L;
                out.add(r);
            }
            return out;
        } catch (Exception ignored) { return null; }
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

    /** In-panel item price chart (Standard+). GET /api/item/{id}?timestep=1h, wiki points only. */
    public SparklineDto sparkline(int id) {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/item/" + id + "?timestep=1h")
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (res.code() != 200 || res.body() == null) return null;
            return parseSparkline(res.body().string());
        } catch (Exception e) { return null; }
    }

    /** id/name + wiki points, dropping zero (high==0 && low==0) points, keeping the last 24. */
    public SparklineDto parseSparkline(String json) {
        try {
            JsonObject o = gson.fromJson(json, JsonObject.class);
            if (o == null || !o.has("wiki")) return null;
            SparklineDto d = new SparklineDto();
            d.id = o.has("id") ? o.get("id").getAsInt() : 0;
            d.name = o.has("name") ? o.get("name").getAsString() : "";
            List<SparklineDto.Point> pts = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("wiki")) {
                JsonObject p = e.getAsJsonObject();
                long high = p.has("high") ? p.get("high").getAsLong() : 0;
                long low = p.has("low") ? p.get("low").getAsLong() : 0;
                if (high == 0 && low == 0) continue;
                SparklineDto.Point pt = new SparklineDto.Point();
                pt.ts = p.has("ts") ? p.get("ts").getAsLong() : 0;
                pt.high = high; pt.low = low;
                pt.mid = (high + low) / 2.0;
                pts.add(pt);
            }
            if (pts.size() > 24) pts = pts.subList(pts.size() - 24, pts.size());
            d.points = pts;
            return d;
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

    /** Plan rows the panel just showed. Server dedupes repeat polls. False when not logged in or the post failed. */
    public boolean postPlanShown(String inputs, java.util.List<PlanRowDto> rows) {
        if (token == null || rows == null || rows.isEmpty()) return false;
        JsonObject b = new JsonObject();
        b.addProperty("inputs", inputs == null ? "" : inputs);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (PlanRowDto r : rows) {
            if (r == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("itemId", r.id);
            o.addProperty("buyPrice", r.buyPrice);
            o.addProperty("sellPrice", r.sellPrice);
            o.addProperty("qty", r.qty);
            o.addProperty("fillHrs", r.fillHrs);
            arr.add(o);
        }
        if (arr.size() == 0) return false;
        b.add("rows", arr);
        return postOk("/api/plan-shown", b.toString());
    }

    /** Placed / cancelled / completed GE offers. False when not logged in or the post failed. */
    public boolean postOfferEvents(java.util.List<com.gecopilot.client.trade.OfferEvent> events) {
        if (token == null || events == null || events.isEmpty()) return false;
        JsonObject b = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (com.gecopilot.client.trade.OfferEvent e : events) {
            if (e == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("kind", e.kind);
            o.addProperty("itemId", e.itemId);
            o.addProperty("side", e.buy ? "buy" : "sell");
            o.addProperty("price", e.price);
            o.addProperty("qty", e.qty);
            o.addProperty("filled", e.filled);
            o.addProperty("slot", e.slot);
            o.addProperty("ts", e.ts);
            arr.add(o);
        }
        if (arr.size() == 0) return false;
        b.add("events", arr);
        return postOk("/api/offer-events", b.toString());
    }

    private boolean postOk(String path, String body) {
        try (Response res = authed(path, body).execute()) {
            if (res.code() == 401) token = null;
            return res.isSuccessful();
        } catch (Exception e) { return false; }
    }

    public void postOutcome(com.gecopilot.client.trade.Outcome o) {
        if (token == null || o == null) return;
        try (Response res = authed("/api/outcomes", outcomeJson(o)).execute()) {
            if (res.code() == 401) token = null;
        } catch (Exception ignored) { }
    }

    /** Placement features for an offer that just opened. Null when the market isn't known yet. */
    public PlacementFeatures placementFeatures(int itemId, boolean buy, int price, int qty) {
        if (token == null || itemId <= 0 || price <= 0 || qty <= 0) return null;
        String url = baseUrl + "/api/fill-features?itemId=" + itemId
            + "&buy=" + buy + "&price=" + price + "&qty=" + qty;
        Request req = new Request.Builder().url(url)
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (res.code() != 200 || res.body() == null) return null;
            return parsePlacementFeatures(res.body().string());
        } catch (Exception e) { return null; }
    }

    public static String outcomeJson(com.gecopilot.client.trade.Outcome o) {
        JsonObject b = new JsonObject();
        b.addProperty("itemId", o.itemId);
        b.addProperty("buy", o.buy);
        b.addProperty("price", o.price);
        b.addProperty("sold", o.sold);
        b.addProperty("total", o.total);
        b.addProperty("waitedMs", o.waitedMs);
        if (o.features != null && o.features.length > 0 && o.featureVersion > 0) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (double x : o.features) arr.add(x);
            b.add("features", arr);
            b.addProperty("fv", o.featureVersion);
        }
        return b.toString();
    }

    public PlacementFeatures parsePlacementFeatures(String json) {
        try {
            PlacementFeatures pf = gson.fromJson(json, PlacementFeatures.class);
            if (pf == null || pf.fv <= 0 || pf.features == null || pf.features.length == 0) return null;
            return pf;
        } catch (Exception e) { return null; }
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

    static final int STATUS_TIMEOUT_SEC = 20;
    static final long STATUS_MAX_BYTES = 8 * 1024;
    private final OkHttpClient statusHttp;

    /**
     * Unauthenticated GET /api/status (DB-free on the server). Null when the server can't be reached:
     * a connection error, a timeout, or a 5xx. A server without the endpoint (a 0.1.29 server answers
     * 401) or an unreadable body is reachable with nothing to say: an empty status.
     */
    public ServiceStatusDto status() {
        Request req = new Request.Builder().url(baseUrl + "/api/status").get().build();
        try (Response res = statusHttp.newCall(req).execute()) {
            if (res.code() >= 500) return null;
            if (!res.isSuccessful() || res.body() == null) return new ServiceStatusDto();
            try {
                // Capped read: a status is a few hundred bytes; anything past the cap fails to parse.
                ServiceStatusDto s = gson.fromJson(res.peekBody(STATUS_MAX_BYTES).string(), ServiceStatusDto.class);
                return s != null ? s : new ServiceStatusDto();
            } catch (RuntimeException parse) {
                return new ServiceStatusDto();
            }
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

    /** GET /api/activity-recap — read-only, does not touch the server's last_seen. */
    public ActivityRecap activityRecap() {
        if (token == null) return null;
        Request req = new Request.Builder().url(baseUrl + "/api/activity-recap")
            .header("Authorization", "Bearer " + token).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (res.code() == 401) { token = null; return null; }
            if (!res.isSuccessful() || res.body() == null) return null;
            return gson.fromJson(res.body().string(), ActivityRecap.class);
        } catch (Exception e) { return null; }
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
            .header(CLIENT_HEADER, CLIENT_PLUGIN)
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
            r.offersSeen = o.has("offersSeen") && !o.get("offersSeen").isJsonNull() && o.get("offersSeen").getAsBoolean();
            r.firstLiveTrade = o.has("firstLiveTrade") && !o.get("firstLiveTrade").isJsonNull() && o.get("firstLiveTrade").getAsBoolean();
            if (o.has("flips")) for (JsonElement e : o.getAsJsonArray("flips")) r.flips.add(gson.fromJson(e, CloudFlip.class));
            if (o.has("plan")) for (JsonElement e : o.getAsJsonArray("plan")) r.plan.add(gson.fromJson(e, PlanRowDto.class));
            if (o.has("positions")) for (JsonElement e : o.getAsJsonArray("positions")) r.positions.add(gson.fromJson(e, PositionDto.class));
            if (o.has("alch")) for (JsonElement e : o.getAsJsonArray("alch")) r.alch.add(gson.fromJson(e, AlchDto.class));
            if (o.has("crash")) for (JsonElement e : o.getAsJsonArray("crash")) r.crash.add(gson.fromJson(e, CrashDto.class));
            if (o.has("watch")) for (JsonElement e : o.getAsJsonArray("watch")) r.watch.add(gson.fromJson(e, WatchDto.class));
        } catch (Exception ignored) { }
        return r;
    }

    private static long longField(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
    }

    public List<LogRowDto> parseLog(String json) {
        List<LogRowDto> out = new ArrayList<>();
        try {
            for (JsonElement e : gson.fromJson(json, com.google.gson.JsonArray.class)) out.add(gson.fromJson(e, LogRowDto.class));
        } catch (Exception ignored) { }
        return out;
    }
}
