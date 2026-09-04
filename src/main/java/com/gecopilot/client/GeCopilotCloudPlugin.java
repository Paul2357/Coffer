package com.gecopilot.client;

import com.gecopilot.client.api.*;
import com.gecopilot.client.trade.*;
import com.gecopilot.client.ui.CloudPanel;
import com.gecopilot.client.ui.GeOfferOverlay;
import com.google.gson.Gson;
import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(name = "Coffer", description = "Grand Exchange flip finder + P&L tracker (free account, sign up in the panel)")
public class GeCopilotCloudPlugin extends Plugin {
    @Inject private ClientToolbar clientToolbar;
    @Inject private GeCopilotCloudConfig config;
    @Inject private ScheduledExecutorService executor;
    @Inject private OkHttpClient http;
    @Inject private Gson gson;
    @Inject private Notifier notifier;
    @Inject private Client client;
    @Inject private OverlayManager overlayManager;
    @Inject private ItemManager itemManager;
    @Inject private ConfigManager configManager;

    private CloudPanel panel;
    private NavigationButton navButton;
    private GeOfferOverlay overlay;
    private ApiClient api;
    private java.util.concurrent.ScheduledFuture<?> pollFuture; // cancelled in shutDown()
    private volatile OfferCapture capture; // built in startUp(); read from poll + GE-event threads
    private final Map<Integer, Long> coachSent = new HashMap<>();
    private final com.gecopilot.client.auth.TokenStore tokenStore = new com.gecopilot.client.auth.TokenStore();
    // Fills that couldn't be posted yet (not logged in / offline) — retried until accepted, so a
    // buy that fills before login is never lost.
    private final java.util.concurrent.ConcurrentLinkedDeque<CompletedFill> pendingTrades = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile int quotedId = -1;   // last GE item we fetched an overlay quote for
    // Cached on the client thread (onGameTick); read from background threads. client.getAccountHash()
    // must not be called off the client thread — doing so throws and aborts poll().
    private volatile long accountHash = -1;
    // RS display name of the logged-in account, cached on the client thread; stamped onto each fill so
    // the server can attribute P&L per account. Only overwritten when non-null (survives brief nulls).
    private volatile String displayName = null;
    private volatile java.util.List<com.gecopilot.client.api.WatchDto> lastWatch = new java.util.ArrayList<>();
    private volatile com.gecopilot.client.api.GoalDto lastGoal = null;

    // Show the one-time first-run guide until the user dismisses it (per-install flag).
    private void maybeOnboard() {
        if (!config.onboardingSeen()) panel.showOnboarding(() -> {
            configManager.setConfiguration("gecopilotcloud", "onboardingSeen", true);
            panel.hideOnboarding();
        });
    }

    @Provides
    GeCopilotCloudConfig provideConfig(ConfigManager cm) { return cm.getConfig(GeCopilotCloudConfig.class); }

    @Override
    protected void startUp() {
        capture = new OfferCapture(); // empty until the RS account is known (ensureAccountLoaded)
        panel = new CloudPanel(itemManager);
        panel.onLogTabSelected(() -> executor.execute(this::loadLog));
        panel.onAccountChange(acct -> {
            api.setAccount(acct);
            executor.execute(() -> { syncAndRender(); loadLog(); });
        });
        panel.onWatchToggle(id -> executor.execute(() -> {
            if (!ensureLogin()) return;
            boolean currentlyWatched = false;
            for (com.gecopilot.client.api.WatchDto w : lastWatch) if (w.id == id) { currentlyWatched = true; break; }
            api.watch(id, !currentlyWatched);
            poll(); // refresh so the star + Watch tab update
        }));
        panel.onSearch(q -> executor.execute(() -> {
            if (ensureLogin()) panel.showSearchResults(api.find(q));
        }));
        // Render's free tier sleeps and takes ~30-50s to wake; give API calls generous timeouts so
        // a cold-start login/restore doesn't time out and look like a failure.
        OkHttpClient apiHttp = http.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .build();
        api = new ApiClient(apiHttp, gson, com.gecopilot.client.ServerConfig.BASE_URL);
        panel.onLogin((user, pass) -> executor.execute(() -> {
            com.gecopilot.client.api.LoginResult r = api.login(user, pass);
            if (r.ok) {
                tokenStore.save(r.token);
                panel.showMain(user, r.tier);
                maybeOnboard();
                poll();
            } else if (!r.reached) {
                panel.showLogin("Couldn't reach server — it may be waking up (~30s). Try again.");
            } else if (r.code == 401) {
                panel.showLogin("Login failed — check username/password");
            } else {
                panel.showLogin("Server error (" + r.code + ") — try again in a moment");
            }
        }));
        panel.onRegister((user, pass) -> executor.execute(() -> {
            com.gecopilot.client.api.LoginResult r = api.register(user, pass);
            if (r.ok) {
                tokenStore.save(r.token);
                panel.showMain(user, r.tier);
                maybeOnboard();
                poll();
            } else if (!r.reached) {
                panel.showLogin("Couldn't reach server — it may be waking up (~30s). Try again.");
            } else if (r.code == 409) {
                panel.showLogin("Username taken — pick another");
            } else if (r.code == 400) {
                panel.showLogin("Username 3–20 chars; password 8+");
            } else if (r.code == 429) {
                panel.showLogin("Too many attempts — wait a few minutes");
            } else {
                panel.showLogin("Sign-up error (" + r.code + ") — try again");
            }
        }));
        panel.onLogout(() -> {
            tokenStore.clear();
            api.clearToken();
            showPreviewOrLogin();
        });
        panel.onSaveAlerts((webhook, enabled, recover, position, crash, cooldownMin, quietEnabled, quietStart, quietEnd) ->
            executor.execute(() -> api.alertsConfig(webhook, enabled, recover, position, crash, cooldownMin,
                    quietEnabled, quietStart, quietEnd, java.time.ZoneId.systemDefault().getId())));
        panel.onTestAlert(() -> executor.execute(() -> {
            boolean ok = api.testAlert();
            panel.setAlertStatus(ok ? "Test alert sent" : "Test failed — check your webhook");
        }));
        panel.onSaveNudges((enabled, cooldownMin) -> {
            configManager.setConfiguration("gecopilotcloud", "nudgesEnabled", enabled);
            configManager.setConfiguration("gecopilotcloud", "nudgeCooldownMin", cooldownMin);
        });
        panel.onSavePrefs((b, s, p, a, risk, minVol, minMargin, minRoi, fillTarget) -> {
            configManager.setConfiguration("gecopilotcloud", "bankroll", b);
            configManager.setConfiguration("gecopilotcloud", "slots", s);
            configManager.setConfiguration("gecopilotcloud", "pace", p);
            configManager.setConfiguration("gecopilotcloud", "access", a);
            configManager.setConfiguration("gecopilotcloud", "riskTolerance", risk);
            configManager.setConfiguration("gecopilotcloud", "minVolume", minVol);
            configManager.setConfiguration("gecopilotcloud", "minMargin", minMargin);
            configManager.setConfiguration("gecopilotcloud", "minRoi", minRoi);
            configManager.setConfiguration("gecopilotcloud", "fillTarget", fillTarget);
            executor.execute(this::syncAndRender); // re-rank immediately with the new filters
        });
        panel.onSaveGoal((goalGp, deadline) -> executor.execute(() -> { api.setGoal(goalGp, deadline); poll(); }));
        panel.onOpenSettings(() -> executor.execute(() -> {
            if (!ensureLogin()) return;
            com.gecopilot.client.api.AlertsStatus st = api.alertsStatus();
            String ver = api.health();
            com.gecopilot.client.api.GoalDto g = lastGoal;
            panel.showSettings(config.bankroll(), config.slots(), config.pace(), config.access(),
                config.riskTolerance(), config.minVolume(), config.minMargin(), config.minRoi(), config.fillTarget(),
                st != null && st.webhookSet, st != null && st.enabled,
                st == null || st.recover, st == null || st.position, st == null || st.crash,
                st != null ? st.cooldownMin : 30,
                config.nudgesEnabled(), config.nudgeCooldownMin(), ver,
                g != null ? g.goalGp : 0, g != null ? g.deadline : 0,
                st != null && st.quietEnabled, st != null ? st.quietStart : 0, st != null ? st.quietEnd : 0);
        }));
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(new Color(0x4f, 0xc3, 0xf7));
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        navButton = NavigationButton.builder().tooltip("Coffer").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);
        overlay = new GeOfferOverlay(client);
        overlayManager.add(overlay);
        pollFuture = executor.scheduleWithFixedDelay(this::poll, 0, 60, TimeUnit.SECONDS);
        executor.execute(this::restoreSession);
    }

    private static final int RESTORE_ATTEMPTS = 6;

    /** No session: show the free F2P preview if reachable, else fall back to the login form. */
    private void showPreviewOrLogin() {
        executor.execute(() -> {
            java.util.List<com.gecopilot.client.api.PreviewFlip> flips = api.preview();
            if (flips != null && !flips.isEmpty()) panel.showPreview(flips);
            else panel.showLogin(null);
        });
    }

    private void restoreSession() {
        java.util.Optional<String> t = tokenStore.load();
        if (!t.isPresent()) { showPreviewOrLogin(); return; }
        api.setToken(t.get());
        tryRestore(0);
    }

    /**
     * One session-restore attempt, rescheduled (not slept) through a cold server. Render's free tier
     * sleeps ~30s; me() clears the token ONLY on a real 401, so we can tell an invalid token from an
     * unreachable server. Rescheduling on the injected executor avoids a blocking sleep (Hub policy).
     */
    private void tryRestore(int attempt) {
        com.gecopilot.client.api.Me me = api.me();
        if (me != null) { panel.showMain(me.username, me.tier); maybeOnboard(); return; }
        if (!api.hasToken()) { tokenStore.clear(); showPreviewOrLogin(); return; } // 401 -> invalid
        if (attempt + 1 < RESTORE_ATTEMPTS) {
            executor.schedule(() -> tryRestore(attempt + 1), 5, TimeUnit.SECONDS);
        } else {
            // Server never answered — keep the token (still valid), show login as a fallback.
            tokenStore.load().ifPresent(api::setToken);
            panel.showLogin("Couldn't reach server — try again");
        }
    }

    @Override
    protected void shutDown() {
        // Cancel our scheduled work (the injected executor is shared — we must not shut it down,
        // only stop our own tasks) so nothing keeps firing after the plugin is disabled.
        if (pollFuture != null) pollFuture.cancel(false);
        if (refreshFuture != null) refreshFuture.cancel(false);
        clientToolbar.removeNavigation(navButton);
        overlayManager.remove(overlay);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        accountHash = client.getAccountHash(); // client-thread read; ensureAccountLoaded uses the cache
        if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            displayName = client.getLocalPlayer().getName();
        int id = client.getVarpValue(1151); // CURRENT_GE_ITEM
        if (id <= 0) { quotedId = -1; return; }
        if (id == quotedId) return;         // already have a quote for this item
        executor.execute(() -> {
            if (!ensureLogin()) return;
            com.gecopilot.client.api.QuoteDto q = api.quote(id);
            overlay.setQuote(q);
            // Only cache on success, so a transient failure (e.g. Render cold start / 204)
            // retries next tick instead of leaving the overlay stuck hidden forever.
            if (q != null) quotedId = id;
        });
    }

    // Fill state is persisted per RS account (keyed by accountHash) so a main and an alt never
    // cross-contaminate signatures or the pending queue. Reloaded on character switch.
    private volatile long loadedAccount = Long.MIN_VALUE;

    // Called from both the poll (executor) thread and GE events (client thread) — synchronized so a
    // concurrent account load can't interleave the rebind + pending-queue swap.
    private synchronized void ensureAccountLoaded() {
        long acct = accountHash; // cached from the client thread; never call getAccountHash() here
        if (acct == -1 || acct == loadedAccount) return;
        boolean firstLoad = (loadedAccount == Long.MIN_VALUE);
        loadedAccount = acct; // previous account's state was already persisted incrementally
        migrateLegacy(acct);
        // Rebind persistence. On first load, keep the live open-offers already tracked this session
        // (GE events fire once per login — dropping them would blank Pos). On a real character
        // switch, clear them so the previous character's offers don't leak into the new one.
        capture.rebind(loadSigs(acct), sigs -> saveSigs(acct, sigs));
        if (firstLoad) {
            // Keep fills captured before the account was known (e.g. an offline sell that replayed on
            // login before the first game tick cached accountHash); merge with the account's persisted
            // queue (persisted/older first) instead of discarding them.
            java.util.List<CompletedFill> inMem = new java.util.ArrayList<>(pendingTrades);
            pendingTrades.clear();
            pendingTrades.addAll(loadPending(acct));
            pendingTrades.addAll(inMem);
            if (!inMem.isEmpty()) savePending();
        } else {
            capture.clearOpen(); // real switch: drop the previous character's live offers
            pendingTrades.clear();
            pendingTrades.addAll(loadPending(acct));
        }
    }

    /** One-time: adopt pre-scoping global keys into the first account seen, then clear them. */
    private void migrateLegacy(long acct) {
        String sigs = configManager.getConfiguration("gecopilotcloud", "fillSigs");
        String pend = configManager.getConfiguration("gecopilotcloud", "pendingTrades");
        if (sigs != null && !sigs.isEmpty()
                && isBlank(configManager.getConfiguration("gecopilotcloud", "fillSigs_" + acct))) {
            configManager.setConfiguration("gecopilotcloud", "fillSigs_" + acct, sigs);
        }
        if (pend != null && !pend.isEmpty()
                && isBlank(configManager.getConfiguration("gecopilotcloud", "pendingTrades_" + acct))) {
            configManager.setConfiguration("gecopilotcloud", "pendingTrades_" + acct, pend);
        }
        configManager.setConfiguration("gecopilotcloud", "fillSigs", "");
        configManager.setConfiguration("gecopilotcloud", "pendingTrades", "");
    }
    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }

    private Map<Integer, String> loadSigs(long acct) {
        String json = configManager.getConfiguration("gecopilotcloud", "fillSigs_" + acct);
        if (isBlank(json)) return new HashMap<>();
        try {
            Map<Integer, String> m = gson.fromJson(json,
                new com.google.gson.reflect.TypeToken<Map<Integer, String>>() {}.getType());
            return m != null ? m : new HashMap<>();
        } catch (Exception e) { return new HashMap<>(); }
    }
    private void saveSigs(long acct, Map<Integer, String> sigs) {
        configManager.setConfiguration("gecopilotcloud", "fillSigs_" + acct, gson.toJson(sigs));
    }

    private boolean ensureLogin() {
        // Auth is panel-driven now: a token is present once the user logs in (or a stored
        // session is restored). Background work simply no-ops until then.
        return api.hasToken();
    }

    private void poll() {
        try { ensureAccountLoaded(); } catch (Exception e) { log.warn("[Coffer] ensureAccountLoaded failed", e); }
        syncAndRender();
    }

    /** Pull a fresh sync + dashboard and repaint. Shared by the 60s poll and the debounced
     *  offer-change refresh. */
    private void syncAndRender() {
        try {
            if (!ensureLogin()) { log.debug("[Coffer] sync: not logged in (no token) — skipping"); return; }
            if (wantHistoryHint) { wantHistoryHint = false; panel.showHistoryHint(); }
            flushTrades(); // retry any fills captured while logged out
            SyncResult sync = api.sync(config.bankroll(), config.slots(), config.pace(), config.access(),
                config.minVolume(), config.minMargin(), config.minRoi(), config.fillTarget(), capture.snapshot());
            log.debug("[Coffer] sync: flips={} watch={} positions={} tier={}",
                sync.flips.size(), sync.watch.size(), sync.positions.size(), sync.tier);
            lastWatch = sync.watch;
            Dashboard dash = api.dashboard();
            lastGoal = dash != null ? dash.goal : null;
            panel.update(sync, dash);
            nudge(sync);
        } catch (Exception e) { log.warn("[Coffer] sync failed", e); }
    }

    // Coalesced refresh: GE events can burst (login replay = 8 at once), so debounce to one sync
    // ~1.5s after the last change instead of syncing per event or waiting for the 60s poll.
    private volatile java.util.concurrent.ScheduledFuture<?> refreshFuture;
    private synchronized void scheduleRefresh() {
        if (refreshFuture != null) refreshFuture.cancel(false);
        refreshFuture = executor.schedule(this::syncAndRender, 1500, TimeUnit.MILLISECONDS);
    }

    private void loadLog() {
        try { if (ensureLogin()) panel.updateLog(api.log()); } catch (Exception ignored) { }
    }

    private void nudge(SyncResult sync) {
        if (!config.nudgesEnabled()) return;
        long window = Math.max(1, config.nudgeCooldownMin()) * 60_000L;
        long now = System.currentTimeMillis();
        for (PositionDto p : sync.positions) {
            if (!p.warn) continue;
            Long last = coachSent.get(p.id);
            if (last == null || now - last > window) {
                coachSent.put(p.id, now);
                String msg;
                if (p.target != null) {
                    String lbl = (p.targetLabel != null && !p.targetLabel.isEmpty()) ? p.targetLabel : "Adjust to";
                    msg = lbl + " " + String.format("%,d", p.target);
                } else {
                    msg = p.advice != null ? p.advice : "check this offer"; // e.g. "Not worth chasing — cancel"
                }
                notifier.notify("Coffer: " + p.name + " — " + msg);
            }
        }
    }

    @Inject private net.runelite.client.callback.ClientThread clientThread;
    private boolean historyHintDone = false;
    private volatile boolean wantHistoryHint = false;

    // First login of the session (client was closed, so offline fills are possible) — nudge the user
    // to open GE History. Fires once per session; deferred to the next poll so it only shows once
    // Coffer is logged in. (Auto-login skips LOGIN_SCREEN, so we key off the first LOGGED_IN, not a
    // specific prior state.)
    @Subscribe
    public void onGameStateChanged(GameStateChanged e) {
        if (e.getGameState() == GameState.LOGGED_IN && !historyHintDone) {
            historyHintDone = true;
            wantHistoryHint = true;
            log.debug("[Coffer] first login — will nudge to open GE History");
            scheduleRefresh(); // show it promptly (once Coffer is logged in) rather than next 60s poll
        }
    }

    // GE History (group 383) opened — read it, reconcile against recorded trades, and (this pass) log
    // what WOULD import. Rows populate via scripts just after load, so read after a short delay.
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e) {
        if (e.getGroupId() != 383) return;
        executor.schedule(() -> clientThread.invoke(() -> {
            java.util.List<com.gecopilot.client.trade.GeHistoryRow> rows =
                com.gecopilot.client.trade.GeHistoryReader.read(client); // client thread
            executor.execute(() -> reconcileHistory(rows));              // background for the API call
        }), 600, TimeUnit.MILLISECONDS);
    }
    private void reconcileHistory(java.util.List<com.gecopilot.client.trade.GeHistoryRow> rows) {
        if (!ensureLogin() || rows.isEmpty()) return;
        var recorded = api.recentTrades();
        var missing = com.gecopilot.client.trade.GeHistoryReconcile.missing(rows, recorded);
        if (missing.isEmpty()) { panel.hideHistoryImport(); return; } // clears any login hint too
        log.debug("[Coffer] GE History: {} rows, {} recorded, {} to import", rows.size(), recorded.size(), missing.size());
        panel.showHistoryImport(missing.size(), () -> importHistory(missing));
    }

    private void importHistory(java.util.List<com.gecopilot.client.trade.GeHistoryRow> missing) {
        executor.execute(() -> {
            // Order buys before sells and stamp ascending timestamps near now, so FIFO P&L matches a
            // buy before its sell and imported trades sort after any already-recorded history.
            java.util.List<com.gecopilot.client.trade.GeHistoryRow> ordered = new java.util.ArrayList<>();
            for (var h : missing) if (h.buy) ordered.add(h);
            for (var h : missing) if (!h.buy) ordered.add(h);
            long ts = System.currentTimeMillis();
            for (var h : ordered) { api.postHistoryTrade(h.itemId, h.buy, h.qty, h.unitPrice, ts); ts += 1000; }
            panel.hideHistoryImport();
            poll(); // refresh P&L / log
        });
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged ev) {
        GrandExchangeOffer o = ev.getOffer();
        if (o == null) return;
        accountHash = client.getAccountHash(); // client thread here — refresh before loading, so an
        if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            displayName = client.getLocalPlayer().getName();
        ensureAccountLoaded();                 // offline fill replayed on login lands in the right account

        long now = System.currentTimeMillis();
        String state = o.getState().name();
        // Emit an outcome (fill or not) BEFORE noteOpen clears the slot on terminal/EMPTY states.
        boolean resolved = state.equals("EMPTY") || state.equals("BOUGHT") || state.equals("SOLD") || state.startsWith("CANCELLED");
        if (resolved) {
            final com.gecopilot.client.trade.Outcome outcome =
                capture.resolve(ev.getSlot(), o.getQuantitySold(), o.getTotalQuantity(), now);
            if (outcome != null) executor.execute(() -> { if (ensureLogin()) api.postOutcome(outcome); });
        }
        capture.noteOpen(ev.getSlot(), state, o.getItemId(), o.getQuantitySold(), o.getTotalQuantity(), o.getPrice(), now);
        CompletedFill fill = capture.onOffer(ev.getSlot(), state, o.getItemId(),
            o.getTotalQuantity(), o.getQuantitySold(), o.getSpent(), now);
        if (fill != null) {
            fill.account = displayName; // attribute to the current account (null → server "Unknown")
            pendingTrades.addLast(fill);
            savePending(); // durable before any post attempt, so a close/crash can't lose it
        }
        // Any offer change (new/updated/removed live offer, or a fill) refreshes the positions view
        // promptly instead of waiting for the next 60s poll.
        scheduleRefresh();
    }

    /** Post queued fills in order; stop (keep the rest) on the first failure so nothing is lost. */
    private synchronized void flushTrades() {
        if (!api.hasToken()) return;
        CompletedFill f;
        boolean changed = false;
        while ((f = pendingTrades.peekFirst()) != null) {
            if (!api.postTrade(f.itemId, f.buy, f.qty, f.price, f.ts, f.account)) break;
            pendingTrades.pollFirst();
            changed = true;
        }
        if (changed) savePending(); // shrink the durable copy as fills are accepted
    }

    // Durable pending-fill queue (per RS account): fills captured while logged-out/offline survive
    // a close or crash.
    private void savePending() {
        if (loadedAccount == Long.MIN_VALUE) return; // no account yet — nothing scoped to save under
        configManager.setConfiguration("gecopilotcloud", "pendingTrades_" + loadedAccount,
            gson.toJson(new java.util.ArrayList<>(pendingTrades)));
    }
    private java.util.List<CompletedFill> loadPending(long acct) {
        java.util.List<CompletedFill> out = new java.util.ArrayList<>();
        String json = configManager.getConfiguration("gecopilotcloud", "pendingTrades_" + acct);
        if (isBlank(json)) return out;
        try {
            java.util.List<CompletedFill> list = gson.fromJson(json,
                new com.google.gson.reflect.TypeToken<java.util.List<CompletedFill>>() {}.getType());
            if (list != null) out.addAll(list);
        } catch (Exception ignored) { }
        return out;
    }
}
