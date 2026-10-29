package com.gecopilot.client;

import com.gecopilot.client.api.ApiClient;
import com.gecopilot.client.trade.BuyLimits;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.http.api.RuneLiteAPI;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.*;

/**
 * Iris MED-1 / LOW-3 / NEW-1 and the two follow-up LOWs against the real plugin event path:
 * buy-limit fills, open offers and the pending queues across a plugin toggle (same account, or a
 * different one next, also while a flush holds the plugin's lock), the first sync after re-enabling
 * on another account, and fills that arrive without an account hash. The RuneLite injector isn't
 * available here, so the test wires the members the GE-event path reads (client, executor, Gson,
 * API client) and backs the plugin's persisted state with a map.
 */
public class GeCopilotCloudPluginToggleTest {
    private static final long ACCT = 42L;
    private static final int WHIP = 4151;

    /** The plugin with its config store in a map. */
    static final class TestPlugin extends GeCopilotCloudPlugin {
        final Map<String, String> store = new HashMap<>();
        @Override String cfg(String key) { return store.get(key); }
        @Override void setCfg(String key, String value) { store.put(key, value); }
    }

    private long hash = ACCT;

    private TestPlugin plugin() throws Exception {
        TestPlugin p = new TestPlugin();
        set(p, "client", Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, m, args) -> m.getName().equals("getAccountHash") ? hash : defaultValue(m.getReturnType())));
        // No-op executor: the event path only schedules network work (features, refresh) on it.
        set(p, "executor", Proxy.newProxyInstance(ScheduledExecutorService.class.getClassLoader(),
            new Class<?>[]{ScheduledExecutorService.class}, (proxy, m, args) -> defaultValue(m.getReturnType())));
        set(p, "gson", RuneLiteAPI.GSON); // what RuneLite injects
        set(p, "api", new ApiClient(null, RuneLiteAPI.GSON, ""));
        return p;
    }

    /** startUp()'s trade-state part. shutDown() leaves that state alone, so a toggle is this, twice. */
    private static void startUp(GeCopilotCloudPlugin p) { p.resetTradeSession(); }

    @Test public void toggleOffAndOnCountsAFillOnceAndKeepsSaving() throws Exception {
        TestPlugin p = plugin();
        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 0, 100));
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 30, 100));
        assertEquals(30, bought(p));
        assertTrue(p.store.get("openOffers_" + ACCT).contains("\"sold\":30"));

        // Plugin toggled off (shutDown) and on (startUp): same instance, fresh trade session.
        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 30, 100)); // replay
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 50, 100)); // a fill
        assertEquals("30 + 20, not the 30 counted again", 50, bought(p));
        assertEquals(50, savedBought(p));
        assertTrue("open offers still save after the toggle: " + p.store.get("openOffers_" + ACCT),
            p.store.get("openOffers_" + ACCT).contains("\"sold\":50"));

        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BOUGHT, 100, 100));
        assertEquals(100, bought(p));
        assertEquals(100, savedBought(p));
    }

    @Test public void fillWithNoAccountHashIsNotChargedToTheLoadedAccount() throws Exception {
        TestPlugin p = plugin();
        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 0, 100));
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 10, 100));
        assertEquals(10, bought(p));

        hash = -1; // e.g. a GE event while logging out / hopping
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 25, 100));
        assertEquals("skipped while an account is loaded but the hash is -1", 10, bought(p));
        assertEquals(10, savedBought(p));

        hash = ACCT;
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 40, 100));
        assertEquals(25, bought(p));
    }

    @Test public void fillBeforeAnyAccountIsLoadedIsKeptForTheFirstAccount() throws Exception {
        TestPlugin p = plugin();
        startUp(p);
        hash = -1;
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 5, 100));
        assertEquals("no account yet: held in memory", 5, bought(p));
        hash = ACCT;
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 8, 100));
        assertEquals(8, bought(p));
        assertEquals(8, savedBought(p));
    }

    @Test public void toggleWithAQueuedFillDoesNotDoubleTheQueue() throws Exception {
        TestPlugin p = plugin();
        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 0, 100));
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BOUGHT, 100, 100));
        int fills = queue(p, "pendingTrades").size(), events = queue(p, "pendingEvents").size();
        assertTrue("a fill queued (not logged in, so not posted)", fills > 0);
        assertTrue("offer events queued", events > 0);

        for (int toggle = 1; toggle <= 3; toggle++) {
            startUp(p);
            p.onGrandExchangeOfferChanged(event(1, 2, GrandExchangeOfferState.EMPTY, 0, 0)); // loads the account
            assertEquals("fills after toggle " + toggle, fills, queue(p, "pendingTrades").size());
            assertEquals("saved fills after toggle " + toggle, fills, saved(p, "pendingTrades_" + ACCT));
            assertEquals("events after toggle " + toggle, events, queue(p, "pendingEvents").size());
            assertEquals("saved events after toggle " + toggle, events, saved(p, "pendingOfferEvents_" + ACCT));
        }
    }

    @Test public void aToggleThenBGetsNoneOfAsLimitsOrQueues() throws Exception {
        final long b = 77L;
        TestPlugin p = plugin();
        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 0, 100));
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BOUGHT, 100, 100));
        assertEquals(100, savedBought(p));
        String limitsA = p.store.get("buyLimits_" + ACCT);
        String fillsA = p.store.get("pendingTrades_" + ACCT);
        String eventsA = p.store.get("pendingOfferEvents_" + ACCT);
        assertTrue(saved(p, "pendingTrades_" + ACCT) > 0);

        startUp(p); // toggle
        hash = -1;  // a fill before B's hash is known: not A's
        p.onGrandExchangeOfferChanged(event(1, 2, GrandExchangeOfferState.BUYING, 5, 50));
        assertEquals("nothing written to A's windows", limitsA, p.store.get("buyLimits_" + ACCT));

        hash = b;
        p.onGrandExchangeOfferChanged(event(1, 2, GrandExchangeOfferState.BUYING, 8, 50));
        assertEquals("B has none of A's whip window", 0, bought(p));
        String limitsB = p.store.get("buyLimits_" + b);
        assertNotNull(limitsB);
        assertFalse("B's saved windows: " + limitsB, limitsB.contains("\"" + WHIP + "\""));
        assertTrue("B has no fills", queue(p, "pendingTrades").isEmpty());
        assertEquals(0, saved(p, "pendingTrades_" + b));
        for (Object e : queue(p, "pendingEvents"))
            assertNotEquals("none of A's offer events", WHIP, ((com.gecopilot.client.trade.OfferEvent) e).itemId);
        assertFalse(String.valueOf(p.store.get("pendingOfferEvents_" + b)).contains("\"itemId\":" + WHIP));
        assertEquals("A's windows untouched", limitsA, p.store.get("buyLimits_" + ACCT));
        assertEquals("A's fills untouched", fillsA, p.store.get("pendingTrades_" + ACCT));
        assertEquals("A's events untouched", eventsA, p.store.get("pendingOfferEvents_" + ACCT));
    }

    @Test public void reEnabledAfterSwitchingAccountsDoesNotSyncOrNudgeTheOldAccountsOffers() throws Exception {
        final long b = 77L;
        TestPlugin p = plugin();
        java.util.List<JsonObject> synced = new java.util.ArrayList<>();
        // A server that coaches (warn) A's whip whenever it is sent as an open offer, so a sync of it would nudge.
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            String out = "{}";
            if (chain.request().url().encodedPath().equals("/api/flips")) {
                okio.Buffer buf = new okio.Buffer();
                chain.request().body().writeTo(buf);
                JsonObject sent = RuneLiteAPI.GSON.fromJson(buf.readUtf8(), JsonObject.class);
                synced.add(sent);
                JsonArray positions = new JsonArray();
                for (JsonElement e : sent.getAsJsonArray("openOffers")) {
                    if (e.getAsJsonObject().get("itemId").getAsInt() != WHIP) continue;
                    JsonObject pos = new JsonObject();
                    pos.addProperty("id", e.getAsJsonObject().get("itemId").getAsInt());
                    pos.addProperty("name", "Item");
                    pos.addProperty("buy", true);
                    pos.addProperty("warn", true);
                    pos.addProperty("advice", "check this offer");
                    positions.add(pos);
                }
                JsonObject res = new JsonObject();
                res.add("flips", new JsonArray());
                res.add("positions", positions);
                out = res.toString();
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(ResponseBody.create(MediaType.parse("application/json"), out)).build();
        }).build();
        ApiClient api = new ApiClient(fake, RuneLiteAPI.GSON, "http://coffer.test");
        api.setToken("t");
        set(p, "api", api);
        set(p, "config", Proxy.newProxyInstance(GeCopilotCloudConfig.class.getClassLoader(), new Class<?>[]{GeCopilotCloudConfig.class},
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "nudgesEnabled": return true;
                    case "nudgeCooldownMin": return 5;
                    case "pace": return "steady";
                    case "access": return "all";
                    default: return defaultValue(m.getReturnType());
                }
            }));

        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 10, 100)); // A's live whip buy
        poll(p, false); // no nudge here: the test has no Notifier
        assertTrue("control: A's own poll sends the whip", synced.get(0).toString().contains("\"itemId\":" + WHIP));

        // Disabled on A, logged into B, re-enabled: no game tick yet, so the plugin hasn't seen B's hash.
        hash = b;
        startUp(p);
        poll(p, true); // the first poll after startUp
        JsonObject first = synced.get(1);
        assertEquals("no open offers sent under B: " + first, 0, first.getAsJsonArray("openOffers").size());
        assertFalse("offers not claimed as known before B's GE is seen", first.get("offersKnown").getAsBoolean());
        assertTrue("no nudge", ((Map<?, ?>) get(p, "coachSent")).isEmpty());

        p.onGrandExchangeOfferChanged(event(0, 2, GrandExchangeOfferState.BUYING, 0, 50)); // B's GE login replay
        poll(p, true);
        JsonObject second = synced.get(2);
        assertTrue(second.get("offersKnown").getAsBoolean());
        assertFalse("B's sync has none of A's offers: " + second, second.toString().contains("\"itemId\":" + WHIP));
        assertFalse(String.valueOf(p.store.get("openOffers_" + b)).contains("\"itemId\":" + WHIP));
        assertFalse("the whip is never nudged under B", ((Map<?, ?>) get(p, "coachSent")).containsKey(WHIP));
    }

    @Test public void startUpDoesNotWaitOnAFlushHoldingTheLockAndBGetsNothingOfA() throws Exception {
        TestPlugin p = plugin();
        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 0, 100));
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BOUGHT, 100, 100));
        assertEquals(1, saved(p, "pendingTrades_" + ACCT));
        String limitsA = p.store.get("buyLimits_" + ACCT);

        // flushTrades mid-HTTP: it holds the plugin's lock, and the server then accepts the fill.
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread flush = new Thread(() -> {
            synchronized (p) {
                held.countDown();
                try {
                    release.await();
                    ((java.util.Collection<?>) get(p, "pendingTrades")).clear();
                    invoke(p, "savePending");
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        flush.start();
        assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Thread edt = new Thread(() -> startUp(p)); // startUp runs on the EDT in RuneLite
        edt.start();
        edt.join(2_000);
        boolean blocked = edt.isAlive();
        release.countDown();
        flush.join(5_000);
        edt.join(5_000);
        assertFalse("startUp waited on the flush's lock", blocked);

        hash = 77L;
        p.onGrandExchangeOfferChanged(event(1, 2, GrandExchangeOfferState.BUYING, 0, 50)); // B's first GE event
        assertTrue("B has none of A's fills", queue(p, "pendingTrades").isEmpty());
        assertEquals("the accepted fill stays gone from A's saved queue", 0, saved(p, "pendingTrades_" + ACCT));
        assertEquals(0, saved(p, "pendingTrades_" + 77L));
        assertEquals("B has none of A's windows", 0, bought(p));
        assertEquals(limitsA, p.store.get("buyLimits_" + ACCT));
        for (Object e : queue(p, "pendingEvents"))
            assertNotEquals(WHIP, ((com.gecopilot.client.trade.OfferEvent) e).itemId);
    }

    @Test public void contendedToggleOnTheSameAccountDoesNotDoubleTheQueue() throws Exception {
        TestPlugin p = plugin();
        startUp(p);
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BUYING, 0, 100));
        p.onGrandExchangeOfferChanged(event(0, WHIP, GrandExchangeOfferState.BOUGHT, 100, 100));
        int fills = queue(p, "pendingTrades").size(), events = queue(p, "pendingEvents").size();

        Thread edt;
        synchronized (p) { // a flush holding the lock (and failing: nothing accepted) across the toggle
            edt = new Thread(() -> startUp(p));
            edt.start();
            edt.join(2_000);
            assertFalse("startUp waited on the flush's lock", edt.isAlive());
        }
        p.onGrandExchangeOfferChanged(event(1, 2, GrandExchangeOfferState.EMPTY, 0, 0));
        assertEquals(fills, queue(p, "pendingTrades").size());
        assertEquals(fills, saved(p, "pendingTrades_" + ACCT));
        assertEquals(events, queue(p, "pendingEvents").size());
    }

    /** What pollTick / poll do: load the account, then the sync steps (and the nudge after a sync). */
    private static void poll(GeCopilotCloudPlugin p, boolean nudge) throws Exception {
        invoke(p, "ensureAccountLoaded");
        Class<?> c = Class.forName(GeCopilotCloudPlugin.class.getName() + "$SyncSteps");
        java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor(GeCopilotCloudPlugin.class);
        k.setAccessible(true);
        Object steps = k.newInstance(p);
        java.lang.reflect.Method sync = c.getDeclaredMethod("sync");
        sync.setAccessible(true);
        Object result = sync.invoke(steps);
        if (nudge) {
            java.lang.reflect.Method n = c.getDeclaredMethod("nudge", com.gecopilot.client.api.SyncResult.class);
            n.setAccessible(true);
            try {
                n.invoke(steps, result); // the test has no Notifier: a nudge that fires throws here
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new AssertionError("a nudge fired", e.getCause());
            }
        }
    }

    private static void invoke(Object o, String name) throws Exception {
        java.lang.reflect.Method m = GeCopilotCloudPlugin.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }

    private static java.util.Collection<?> queue(GeCopilotCloudPlugin p, String field) throws Exception {
        return (java.util.Collection<?>) get(p, field);
    }

    /** Entries in a saved JSON list, 0 if none was saved. */
    private static int saved(TestPlugin p, String key) {
        String json = p.store.get(key);
        if (json == null || json.isEmpty()) return 0;
        return RuneLiteAPI.GSON.fromJson(json, java.util.List.class).size();
    }

    private static int bought(GeCopilotCloudPlugin p) throws Exception {
        BuyLimits b = (BuyLimits) get(p, "buyLimits");
        return b.status(WHIP, 0, 0, System.currentTimeMillis()).used;
    }

    private static int savedBought(TestPlugin p) {
        String json = p.store.get("buyLimits_" + ACCT);
        assertNotNull("buy-limit windows saved for the account", json);
        Map<String, BuyLimits.Window> m = RuneLiteAPI.GSON.fromJson(json,
            new com.google.gson.reflect.TypeToken<Map<String, BuyLimits.Window>>() {}.getType());
        BuyLimits.Window w = m.get(Integer.toString(WHIP));
        return w == null ? 0 : w.bought;
    }

    private static GrandExchangeOfferChanged event(int slot, int itemId, GrandExchangeOfferState state, int sold, int total) {
        GrandExchangeOffer o = (GrandExchangeOffer) Proxy.newProxyInstance(GrandExchangeOffer.class.getClassLoader(),
            new Class<?>[]{GrandExchangeOffer.class}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "getItemId": return itemId;
                    case "getState": return state;
                    case "getQuantitySold": return sold;
                    case "getTotalQuantity": return total;
                    case "getPrice": return 1_500_000L;
                    case "getSpent": return 1_500_000L * sold;
                    default: return defaultValue(m.getReturnType());
                }
            });
        GrandExchangeOfferChanged e = new GrandExchangeOfferChanged();
        e.setOffer(o);
        e.setSlot(slot);
        return e;
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == float.class) return 0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    // Test-only field access: the plugin's members are @Inject-ed by RuneLite's Guice injector.
    private static void set(Object o, String name, Object v) throws Exception {
        Field f = GeCopilotCloudPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static Object get(Object o, String name) throws Exception {
        Field f = GeCopilotCloudPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
