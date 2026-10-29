package com.gecopilot.client;

import com.gecopilot.client.api.*;
import com.gecopilot.client.trade.*;
import com.gecopilot.client.ui.CloudPanel;
import com.gecopilot.client.ui.GeOfferOverlay;
import com.gecopilot.client.ui.OfferChartOverlay;
import com.gecopilot.client.ui.SessionGuide;
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
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
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
    @Inject private MouseManager mouseManager;
    @Inject private ItemManager itemManager;
    @Inject private ConfigManager configManager;
    @Inject private net.runelite.client.chat.ChatMessageManager chatMessageManager;

    private CloudPanel panel;
    private NavigationButton navButton;
    private GeOfferOverlay overlay;
    private volatile OfferChartOverlay chartOverlay; // created in startUp(), removed in shutDown()
    private ApiClient api;
    private java.util.concurrent.ScheduledFuture<?> pollFuture; // cancelled in shutDown()
    private volatile OfferCapture capture; // built in startUp(); read from poll + GE-event threads
    private volatile OfferJournal journal;
    // Offer events waiting to be posted. Capped and persisted per RS account, same idea as pendingTrades.
    private final java.util.concurrent.ConcurrentLinkedDeque<OfferEvent> pendingEvents = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int MAX_PENDING_EVENTS = 200;
    private volatile String lastPlanFp = "";
    private final Map<Integer, Long> coachSent = new HashMap<>();
    private final LossNotice lossNotice = new LossNotice();
    private final com.gecopilot.client.auth.TokenStore tokenStore = new com.gecopilot.client.auth.TokenStore();
    // Fills that couldn't be posted yet (not logged in / offline) — retried until accepted, so a
    // buy that fills before login is never lost.
    private final java.util.concurrent.ConcurrentLinkedDeque<CompletedFill> pendingTrades = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile int quotedId = -1;   // last GE item we fetched an overlay quote for
    // Paces the overlay quote fetch: one in flight per item, failures back off (1s doubling to 30s).
    private final QuoteBackoff quoteBackoff = new QuoteBackoff();
    // Poll cadence by panel visibility, and what a full vs light (hidden) refresh does.
    private PollCycle pollCycle;
    // Service-status banner and its /api/status cadence; while degraded it also paces the main poll.
    private StatusMonitor statusMonitor;
    // Cached on the client thread (onGameTick); read from background threads. client.getAccountHash()
    // must not be called off the client thread — doing so throws and aborts poll().
    private volatile long accountHash = -1;
    // RS display name of the logged-in account, cached on the client thread; stamped onto each fill so
    // the server can attribute P&L per account. Only overwritten when non-null (survives brief nulls).
    private volatile String displayName = null;
    private volatile java.util.List<com.gecopilot.client.api.WatchDto> lastWatch = new java.util.ArrayList<>();
    private volatile com.gecopilot.client.api.GoalDto lastGoal = null;

    // First-session next step. Advances open GE → place or view an offer → wait for the fill →
    // first live trade. Hide-for-now is panel-local; empty states still name the next action.
    // Returning accounts that already have a live trade skip the guide.
    private SessionGuide.Stage guideStage = null;
    private boolean guideFinished = false;

    private void maybeOnboard(com.gecopilot.client.api.Me me) {
        boolean offers = me != null && me.offersSeenAt > 0;
        boolean trade = me != null && me.firstLiveTradeAt > 0;
        applyGuide(SessionGuide.stage(geObserved, offers, trade), null, 0);
    }

    private void applyGuide(SessionGuide.Stage next, String flipName, long placeBuy) {
        SessionGuide.Stage advanced = SessionGuide.advance(guideStage, next);
        String toast = SessionGuide.transitionToast(guideStage, advanced);
        guideStage = advanced;
        if (advanced == SessionGuide.Stage.DONE) {
            if (toast != null) panel.showSessionToast(toast);
            finishGuide();
            return;
        }
        panel.showSessionGuide(SessionGuide.banner(advanced, flipName, placeBuy));
        if (toast != null) panel.showSessionToast(toast);
    }

    private void finishGuide() {
        if (guideFinished) return;
        guideFinished = true;
        configManager.setConfiguration("gecopilotcloud", "onboardingSeen", true);
        panel.hideSessionGuide();
        showWhatsNewIfNeeded();
    }
    private void showWhatsNewIfNeeded() {
        if (!config.onboardingSeen()) return;
        String lastVer = configManager.getConfiguration("gecopilotcloud", "lastSeenVersion");
        if (!ServerConfig.VERSION.equals(lastVer)) {
            panel.showWhatsNew(ServerConfig.VERSION, ServerConfig.WHATS_NEW, this::seedVersion);
        }
    }
    private void seedVersion() {
        configManager.setConfiguration("gecopilotcloud", "lastSeenVersion", ServerConfig.VERSION);
    }

    @Provides
    GeCopilotCloudConfig provideConfig(ConfigManager cm) { return cm.getConfig(GeCopilotCloudConfig.class); }

    @Override
    protected void startUp() {
        capture = new OfferCapture(); // empty until the RS account is known (ensureAccountLoaded)
        journal = new OfferJournal();
        panel = new CloudPanel(itemManager);
        pollCycle = new PollCycle(
            new PollPolicy(() -> System.nanoTime() / 1_000_000L, this::clientMinimised), new SyncSteps());
        statusMonitor = new StatusMonitor(pollCycle.policy(), () -> System.nanoTime() / 1_000_000L);
        // RuneLite calls these on the Swing thread when the Coffer tab is selected/deselected or the
        // sidebar is expanded/collapsed. Flip visibility right away; opening refreshes everything now.
        panel.onVisibility(visible -> {
            pollCycle.policy().setPanelVisible(visible);
            if (visible) openRefresh();
        });
        panel.onLogTabSelected(() -> executor.execute(this::loadLog));
        panel.onAccountChange(acct -> {
            api.setAccount(acct);
            executor.execute(() -> { syncAndRender(); loadLog(); });
        });
        panel.onSendFeedback((kind, message, contact) -> executor.execute(() -> {
            boolean ok = api.feedback(kind.toLowerCase(), message, contact, com.gecopilot.client.ServerConfig.VERSION);
            panel.showFeedbackResult(ok);
        }));
        panel.onWatchToggle(id -> executor.execute(() -> {
            if (!ensureLogin()) return;
            boolean currentlyWatched = false;
            for (com.gecopilot.client.api.WatchDto w : lastWatch) if (w.id == id) { currentlyWatched = true; break; }
            api.watch(id, !currentlyWatched);
            poll(); // refresh so the star + Watch tab update
        }));
        panel.onSparklineToggle(id -> executor.execute(() -> {
            if (!ensureLogin()) return;
            com.gecopilot.client.api.SparklineDto dto = api.sparkline(id);
            if (dto == null) panel.showSparklineError(id, "Couldn't load chart");
            else panel.showSparkline(id, dto);
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
        api.onConnState(this::onConnState);
        api.onTokenRenewed(tokenStore::save); // rolling session: persist exactly like a login token
        panel.onLogin((user, pass) -> executor.execute(() -> {
            com.gecopilot.client.api.LoginResult r = api.login(user, pass);
            if (r.ok) {
                tokenStore.save(r.token);
                com.gecopilot.client.api.Me me = api.me();
                if (me != null && me.needsOnboarding) {
                    panel.showGate(me.email);
                } else {
                    panel.showMain(user, r.tier);
                    maybeOnboard(me);
                    poll();
                }
            } else if (!r.reached) {
                panel.showLogin("Couldn't reach server — it may be waking up (~30s). Try again.");
            } else if (r.code == 401) {
                panel.showLogin("Login failed — check username/password");
            } else {
                panel.showLogin("Server error (" + r.code + ") — try again in a moment");
            }
        }));
        panel.onRegister((user, pass, email) -> executor.execute(() -> {
            com.gecopilot.client.api.LoginResult r = api.register(user, pass, email);
            if (r.ok) {
                tokenStore.save(r.token);
                com.gecopilot.client.api.Me me = api.me();
                panel.showMain(user, me != null && me.tier != null ? me.tier : r.tier);
                maybeOnboard(me);
                poll();
            } else if (!r.reached) {
                panel.showLogin("Couldn't reach server — it may be waking up (~30s). Try again.");
            } else if (r.code == 409) {
                panel.showLogin("Username taken — pick another");
            } else if (r.code == 400) {
                panel.showLogin("Check: username 3-20 chars, password 8+, valid email");
            } else if (r.code == 429) {
                panel.showLogin("Too many attempts — wait a few minutes");
            } else {
                panel.showLogin("Sign-up error (" + r.code + ") — try again");
            }
        }));
        panel.onForgot(email -> executor.execute(() -> {
            boolean reached = api.forgot(email);
            if (reached) panel.showForgotSent();
            else panel.showForgotUnreachable();
        }));
        panel.onReset((token, newPassword) -> executor.execute(() -> {
            com.gecopilot.client.api.ResetResult r = api.reset(token, newPassword);
            if (r.ok) {
                panel.showResetDone("Password reset — sign in with your new password.");
            } else if (!r.reached) {
                panel.showResetError("Couldn't reach server — try again.");
            } else if (r.code == 429) {
                panel.showResetError("Too many attempts — wait a few minutes.");
            } else {
                panel.showResetError(r.error != null ? r.error : "Invalid or expired code.");
            }
        }));
        panel.onGateContinue(email -> executor.execute(() -> {
            String genericFail = "Couldn't save that email — try a different address, or reach out below.";
            if (!email.isEmpty() && !api.setRecoveryEmail(email)) {
                panel.showGateError(genericFail);
                return;
            }
            if (!api.acceptTos()) {
                panel.showGateError(genericFail);
                return;
            }
            com.gecopilot.client.api.Me me = api.me();
            if (me != null && !me.needsOnboarding) {
                panel.showMain(me.username, me.tier);
                maybeOnboard(me);
                poll();
            } else {
                panel.showGateError(genericFail);
            }
        }));
        panel.onLogout(() -> {
            // Queued fills/offer events stay queued but tagged with this login, so the next
            // login (if it's someone else) can't post them (QueueOwnership).
            api.signOut(tokenStore::clear); // atomic vs a concurrent rolling-token save
            guideStage = null;
            guideFinished = false;
            panel.resetSessionGuide();
            showPreviewOrLogin();
        });
        panel.onSaveSession((user, pass, email) -> executor.execute(() -> {
            com.gecopilot.client.api.LoginResult r = api.register(user, pass, email);
            if (r.ok) {
                tokenStore.save(r.token);
                com.gecopilot.client.api.Me me = api.me();
                if (me != null && me.needsOnboarding) {
                    panel.showGate(me.email);
                } else {
                    panel.showMain(user, me != null && me.tier != null ? me.tier : r.tier);
                    maybeOnboard(me);
                    poll();
                }
            } else if (!r.reached) {
                panel.showPreviewSaveError("Couldn't reach server — it may be waking up (~30s). Try again.");
            } else if (r.code == 409) {
                panel.showPreviewSaveError("That username is taken — pick another.");
            } else if (r.code == 400) {
                panel.showPreviewSaveError("Use a username of 3–20 characters, a password of 8+, and a valid email.");
            } else if (r.code == 429) {
                panel.showPreviewSaveError("Too many attempts — wait a few minutes.");
            } else {
                panel.showPreviewSaveError("Couldn't save that session (" + r.code + ") — try again.");
            }
        }));
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
                st != null && st.quietEnabled, st != null ? st.quietStart : 0, st != null ? st.quietEnd : 0,
                st != null && st.webhookFailed);
        }));
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(new Color(0x4f, 0xc3, 0xf7));
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        navButton = NavigationButton.builder().tooltip("Coffer").icon(icon).panel(panel).build();
        clientToolbar.addNavigation(navButton);
        overlay = new GeOfferOverlay(client, config::priceWarnings, config::minMargin, config::minRoi);
        overlayManager.add(overlay);
        TimeseriesCache timeseries = new TimeseriesCache(new WikiTimeseries(http, gson), () -> System.nanoTime() / 1_000_000L);
        chartOverlay = new OfferChartOverlay(client, timeseries, config::offerChart);
        overlayManager.add(chartOverlay);
        mouseManager.registerMouseListener(chartOverlay.mouseListener());
        SwingUtilities.invokeLater(this::watchFrame);
        pollFuture = executor.scheduleWithFixedDelay(this::pollTick, 0, PollPolicy.TICK_MS, TimeUnit.MILLISECONDS);
        executor.execute(this::restoreSession);
    }

    private static final int RESTORE_ATTEMPTS = 6;

    /** Any authed 401 (expired/killed session) or outage: never leave stale empty tabs on screen. */
    private void onConnState(com.gecopilot.client.api.ApiClient.ConnState s) {
        switch (s) {
            case SIGNED_OUT:
                api.signOut(tokenStore::clear);
                panel.setConnNotice(null);
                panel.showLogin(SIGNED_OUT_MSG);
                break;
            case UNREACHABLE:
                if (api.hasToken()) panel.setConnNotice(UNREACHABLE_MSG);
                break;
            default:
                panel.setConnNotice(null);
        }
    }
    static final String SIGNED_OUT_MSG = "Signed out — your session expired. Log in again.";
    static final String UNREACHABLE_MSG = "Can't reach Coffer — retrying…";

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
        if (me != null) {
            if (me.needsOnboarding) { panel.showGate(me.email); return; }
            panel.showMain(me.username, me.tier); maybeOnboard(me); return;
        }
        if (!api.hasToken()) { // 401 -> invalid; onConnState already showed "Signed out"
            tokenStore.clear();
            if (api.connState() != com.gecopilot.client.api.ApiClient.ConnState.SIGNED_OUT) showPreviewOrLogin();
            return;
        }
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
        SwingUtilities.invokeLater(this::unwatchFrame);
        clientToolbar.removeNavigation(navButton);
        overlayManager.remove(overlay);
        if (chartOverlay != null) {
            mouseManager.unregisterMouseListener(chartOverlay.mouseListener());
            overlayManager.remove(chartOverlay);
            chartOverlay = null;
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        accountHash = client.getAccountHash(); // client-thread read; ensureAccountLoaded uses the cache
        if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            displayName = client.getLocalPlayer().getName();
        // The setup screen's item, or the selected slot's active offer on Offer status (so the chart's
        // Coffer lines have a quote there too). Still one quote per item, cached in quotedId.
        int id = OfferChartOverlay.activeItemId(client);
        if (id <= 0) { quotedId = -1; quoteBackoff.reset(); return; }
        if (id == quotedId) return;         // already have a quote for this item
        if (!ensureLogin()) return;
        // One request in flight per item; a failure (e.g. Render cold start / 204) retries after a
        // backoff instead of every tick, so the overlay still recovers without flooding the server.
        if (!quoteBackoff.tryStart(id, System.nanoTime() / 1_000_000L)) return;
        executor.execute(() -> {
            com.gecopilot.client.api.QuoteDto q;
            try {
                // null on failure or if the player moved on; the in-flight mark always clears
                q = quoteBackoff.attempt(id, () -> api.quote(id), () -> System.nanoTime() / 1_000_000L);
            } catch (Exception e) { log.debug("[Coffer] quote failed", e); return; }
            if (q == null) return;
            overlay.setQuote(q);
            OfferChartOverlay chart = chartOverlay;
            if (chart != null) chart.setQuote(q);
            quotedId = id;
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
        capture.rebindEpochs(loadSigs(acct, "fillEpoch_"), m -> saveSigs(acct, "fillEpoch_", m));
        if (journal != null) {
            journal.rebind(loadSigs(acct, "offerPlaced_"), m -> saveSigs(acct, "offerPlaced_", m),
                loadSigs(acct, "offerTerm_"), m -> saveSigs(acct, "offerTerm_", m));
        }
        // Open offers persist per account so a buy placed last session still resolves (and keeps
        // its original openedAt) when this session sees it fill or cancel. Silence the saver across
        // the swap so a character switch doesn't write an empty map over the new account's rows.
        capture.setOpenSaver(null);
        if (!firstLoad) capture.clearOpen();
        capture.restoreOpen(loadOpens(acct));
        capture.setOpenSaver(opens -> saveOpens(acct, opens));
        capture.flushOpen();
        if (firstLoad) {
            // Keep fills captured before the account was known (e.g. an offline sell that replayed on
            // login before the first game tick cached accountHash); merge with the account's persisted
            // queue (persisted/older first) instead of discarding them.
            java.util.List<CompletedFill> inMem = new java.util.ArrayList<>(pendingTrades);
            pendingTrades.clear();
            pendingTrades.addAll(loadPending(acct));
            pendingTrades.addAll(inMem);
            if (!inMem.isEmpty()) savePending();
            java.util.List<OfferEvent> inEvents = new java.util.ArrayList<>(pendingEvents);
            pendingEvents.clear();
            pendingEvents.addAll(loadEvents(acct));
            pendingEvents.addAll(inEvents);
            trimPendingEvents();
            if (!inEvents.isEmpty()) saveEvents();
        } else {
            pendingTrades.clear();
            pendingTrades.addAll(loadPending(acct));
            pendingEvents.clear();
            pendingEvents.addAll(loadEvents(acct));
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

    private Map<Integer, String> loadSigs(long acct) { return loadSigs(acct, "fillSigs_"); }
    private void saveSigs(long acct, Map<Integer, String> sigs) { saveSigs(acct, "fillSigs_", sigs); }

    private Map<Integer, String> loadSigs(long acct, String prefix) {
        String json = configManager.getConfiguration("gecopilotcloud", prefix + acct);
        if (isBlank(json)) return new HashMap<>();
        try {
            Map<Integer, String> m = gson.fromJson(json,
                new com.google.gson.reflect.TypeToken<Map<Integer, String>>() {}.getType());
            return m != null ? m : new HashMap<>();
        } catch (Exception e) { return new HashMap<>(); }
    }
    private void saveSigs(long acct, String prefix, Map<Integer, String> sigs) {
        configManager.setConfiguration("gecopilotcloud", prefix + acct, gson.toJson(sigs));
    }

    private Map<Integer, OpenOfferDto> loadOpens(long acct) {
        return OpenOfferPersistence.fromJson(gson, configManager.getConfiguration("gecopilotcloud", "openOffers_" + acct));
    }
    private void saveOpens(long acct, Map<Integer, OpenOfferDto> opens) {
        configManager.setConfiguration("gecopilotcloud", "openOffers_" + acct, OpenOfferPersistence.toJson(gson, opens));
    }

    private boolean ensureLogin() {
        // Auth is panel-driven now: a token is present once the user logs in (or a stored
        // session is restored). Background work simply no-ops until then.
        return api.hasToken();
    }

    /**
     * The 60s timer: every tick while the panel is on screen, every 10 min while it is hidden. The
     * service status is checked on its own slower cadence; while Coffer is degraded the regular poll
     * pauses and runs once per backed-off check instead (see {@link StatusMonitor#tick}).
     */
    private void pollTick() {
        if (watchedFrame == null) SwingUtilities.invokeLater(this::watchFrame); // frame may appear after startUp
        try { ensureAccountLoaded(); } catch (Exception e) { log.warn("[Coffer] ensureAccountLoaded failed", e); }
        try {
            statusMonitor.tick(() -> safeSync(pollCycle::onTick), () -> safeSync(pollCycle::refresh),
                () -> StatusMonitor.withOwnCalls(api.status(), api.connState() == ApiClient.ConnState.UNREACHABLE),
                this::applyStatus);
        } catch (Exception e) { log.warn("[Coffer] status tick failed", e); }
    }

    private void applyStatus(StatusMonitor.Update u) {
        if (u.changed) panel.setServiceStatus(u.banner);
        if (u.chatNotice != null && config.statusChatNotice()) chatNotice(u.banner);
        if (u.recovered) log.debug("[Coffer] service recovered; regular poll resumes");
    }

    /** One chat line per distinct notice per session. */
    private void chatNotice(StatusMonitor.Banner b) {
        chatMessageManager.queue(net.runelite.client.chat.QueuedMessage.builder()
            .type(net.runelite.api.ChatMessageType.CONSOLE)
            .runeLiteFormattedMessage(chatLine(b.text))
            .build());
    }

    /** ChatMessageBuilder.append(String) runs Text.escapeJagex, so a '<' or '>' from the server can't open a tag. */
    static String chatLine(String text) {
        return new net.runelite.client.chat.ChatMessageBuilder()
            .append(net.runelite.client.chat.ChatColorType.HIGHLIGHT).append("Coffer: ")
            .append(net.runelite.client.chat.ChatColorType.NORMAL).append(text)
            .build();
    }

    /** An immediate refresh (user action, login). Full while the panel is on screen, light while hidden. */
    private void poll() {
        try { ensureAccountLoaded(); } catch (Exception e) { log.warn("[Coffer] ensureAccountLoaded failed", e); }
        syncAndRender();
    }

    /** Sync and repaint; the depth follows panel visibility. Shared by user actions and the debounced
     *  offer-change refresh. */
    private void syncAndRender() {
        safeSync(pollCycle::refresh);
    }

    /** Panel came on screen: keep the last data up, cue "Updating…", and refresh everything now. */
    private void openRefresh() {
        if (!ensureLogin()) return; // signed out: the login/preview card is showing, nothing to refresh
        panel.beginRefresh();
        executor.execute(() -> safeSync(pollCycle::refreshFull));
    }

    /** Never let a failed sync escape: an exception would cancel the fixed-delay timer. */
    private void safeSync(Runnable r) {
        try { r.run(); } catch (Exception e) { log.warn("[Coffer] sync failed", e); }
    }

    /** The client window is minimised (iconified). Read off the client thread: a plain frame-state read. */
    private boolean clientMinimised() {
        java.awt.Frame f = clientFrame();
        return f != null && (f.getExtendedState() & java.awt.Frame.ICONIFIED) != 0;
    }

    private java.awt.Frame clientFrame() {
        try {
            java.awt.Canvas canvas = client.getCanvas();
            java.awt.Window w = canvas != null ? SwingUtilities.getWindowAncestor(canvas) : null;
            return w instanceof java.awt.Frame ? (java.awt.Frame) w : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Restoring a minimised client refreshes at once, like opening the panel; minimising needs no
    // hook, since the policy reads the frame state on every tick.
    private volatile java.awt.Frame watchedFrame;
    private final java.awt.event.WindowStateListener restoreListener = e -> {
        boolean minimised = (e.getNewState() & java.awt.Frame.ICONIFIED) != 0;
        if (pollCycle.policy().onWindowState(minimised)) openRefresh();
    };

    private void watchFrame() { // EDT
        if (watchedFrame != null) return;
        java.awt.Frame f = clientFrame();
        if (f == null) return;
        f.addWindowStateListener(restoreListener);
        watchedFrame = f;
    }

    private void unwatchFrame() { // EDT
        java.awt.Frame f = watchedFrame;
        if (f != null) f.removeWindowStateListener(restoreListener);
        watchedFrame = null;
    }

    /** What a refresh does against the server; {@link PollCycle} decides which steps run. */
    private final class SyncSteps implements PollCycle.Steps {
        @Override public boolean ready() {
            if (ensureLogin()) return true;
            log.debug("[Coffer] sync: not logged in (no token) — skipping");
            return false;
        }
        @Override public void beforeSync() {
            // One-shot per JVM session, and MUST run before flushTrades()/api.sync() (POST
            // /api/flips overwrites last_seen via touchLastSeen) — otherwise the recap always reads
            // its own just-updated last_seen and shows zeros.
            if (!recapDone) { recapDone = true; fetchRecap(); }
            if (wantHistoryHint) { wantHistoryHint = false; if (!recapShown) panel.showHistoryHint(); }
        }
        @Override public void flushQueues() {
            flushTrades(); // retry any fills captured while logged out
            flushOfferEvents();
        }
        @Override public SyncResult sync() {
            SyncResult sync = api.sync(config.bankroll(), config.slots(), config.pace(), config.access(),
                config.minVolume(), config.minMargin(), config.minRoi(), config.fillTarget(),
                capture.snapshot(), geObserved);
            if (sync.tier != null) {
                String topName = null;
                long topPrice = 0;
                if (!sync.flips.isEmpty() && sync.flips.get(0) != null) {
                    topName = sync.flips.get(0).name;
                    topPrice = sync.flips.get(0).placeBuy;
                }
                applyGuide(SessionGuide.stage(geObserved, sync.offersSeen, sync.firstLiveTrade), topName, topPrice);
            }
            log.debug("[Coffer] sync: flips={} watch={} positions={} tier={}",
                sync.flips.size(), sync.watch.size(), sync.positions.size(), sync.tier);
            lastWatch = sync.watch;
            return sync;
        }
        @Override public Dashboard dashboard() {
            Dashboard dash = api.dashboard();
            lastGoal = dash != null ? dash.goal : null;
            return dash;
        }
        @Override public void render(SyncResult sync, Dashboard dash) {
            panel.update(sync, dash, guideStage == null ? SessionGuide.Stage.DONE : guideStage);
        }
        @Override public void logPlan(SyncResult sync) { maybeLogPlan(sync); }
        @Override public void nudge(SyncResult sync) { GeCopilotCloudPlugin.this.nudge(sync); }
        @Override public void done(boolean ok) { panel.refreshDone(ok); }
    }

    // Coalesced refresh: GE events can burst (login replay = 8 at once), so debounce to one sync
    // ~1.5s after the last change instead of syncing per event or waiting for the next poll.
    // Follows the same visibility rule as the timer: light (no dashboard / plan-shown) while hidden.
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
            if (LossNotice.applies(p)) {
                // Below cost: once per item + price + advice, and only past the 1% / 50k bar.
                String text = lossNotice.next(p);
                if (text != null) notifier.notify(text);
                continue;
            }
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
    /** One History import at a time. A second click re-plans after the first posts, so it can't insert twice. */
    private final java.util.concurrent.atomic.AtomicBoolean historyImporting = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean wantHistoryHint = false;
    private boolean recapDone = false; // one-shot per JVM session, same idiom as historyHintDone
    // False until this session receives a GE offer event. Empty snapshots before that are "unknown",
    // not "no offers" — sync sends offersKnown=false so the server does not wipe saved offers.
    private volatile boolean geObserved = false;
    private boolean recapShown = false; // this session's recap banner fired — skip the redundant history hint

    /** "Since you last opened" recap — reads the CURRENT (pre-sync) last_seen, so must run before
     *  this session's first api.sync() call (which POSTs /api/flips and bumps last_seen forward). */
    private void fetchRecap() {
        try {
            ActivityRecap r = api.activityRecap();
            if (r == null) return;
            if (r.minutesAway >= 30 && (r.closedFlips > 0 || r.filledWhileAway > 0)) {
                recapShown = true;
                panel.showRecap(r.minutesAway, r.closedFlips, r.realizedGp, r.filledWhileAway);
            }
        } catch (Exception e) { log.warn("[Coffer] activity recap fetch failed", e); }
    }

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
        if (recorded == null) return; // failed fetch must not look like an empty account
        var plan = com.gecopilot.client.trade.GeHistoryReconcile.plan(rows, recorded);
        if (plan.imports.isEmpty() && plan.corrections.isEmpty()) { panel.hideHistoryImport(); return; }
        log.debug("[Coffer] GE History: {} to import, {} prices to correct", plan.imports.size(), plan.corrections.size());
        panel.showHistoryImport(com.gecopilot.client.trade.GeHistoryReconcile.prompt(plan), () -> importHistory(rows));
    }

    private void importHistory(java.util.List<com.gecopilot.client.trade.GeHistoryRow> rows) {
        if (!historyImporting.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                // Re-plan against the server now. A second click, or a widget reload that captured the
                // same rows before the first post landed, sees the updated prices and imports nothing.
                var recorded = api.recentTrades();
                if (recorded == null) {
                    panel.showHistoryImport("Couldn't read recorded trades — try again", () -> importHistory(rows));
                    return;
                }
                var plan = com.gecopilot.client.trade.GeHistoryReconcile.plan(rows, recorded);
                if (plan.imports.isEmpty() && plan.corrections.isEmpty()) {
                    panel.hideHistoryImport();
                    return;
                }
                for (var c : plan.corrections) {
                    if (!api.correctTrade(c.itemId, c.buy, c.qty, c.fromPrice, c.toPrice)) {
                        panel.showHistoryImport(com.gecopilot.client.trade.GeHistoryReconcile.prompt(plan),
                            () -> importHistory(rows));
                        return;
                    }
                }
                // Order buys before sells and stamp ascending timestamps near now, so FIFO P&L matches a
                // buy before its sell and imported trades sort after any already-recorded history.
                java.util.List<com.gecopilot.client.trade.GeHistoryRow> ordered = new java.util.ArrayList<>();
                for (var h : plan.imports) if (h.buy) ordered.add(h);
                for (var h : plan.imports) if (!h.buy) ordered.add(h);
                long ts = System.currentTimeMillis();
                for (var h : ordered) {
                    if (!api.postHistoryTrade(h.itemId, h.buy, h.qty, h.unitPrice, ts)) {
                        panel.showHistoryImport(com.gecopilot.client.trade.GeHistoryReconcile.prompt(plan),
                            () -> importHistory(rows));
                        return;
                    }
                    ts += 1000;
                }
                panel.hideHistoryImport();
                poll(); // refresh P&L / log
            } finally {
                historyImporting.set(false);
            }
        });
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged ev) {
        GrandExchangeOffer o = ev.getOffer();
        if (o == null) return;
        geObserved = true;
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
                capture.resolve(ev.getSlot(), o.getQuantitySold(), o.getTotalQuantity(), now, state.equals("EMPTY"));
            if (outcome != null) executor.execute(() -> { if (ensureLogin()) api.postOutcome(outcome); });
        }
        // GrandExchangeOffer.getPrice() is long: since 30 September 2026 GE trades can exceed
        // 2,147,483,647 gp (platinum tokens, https://oldschool.runescape.wiki/w/Grand_Exchange).
        // Coffer carries gp as long end to end, so no narrowing here.
        com.gecopilot.client.trade.OpenOfferDto placed = capture.noteOpen(ev.getSlot(), state, o.getItemId(),
            o.getQuantitySold(), o.getTotalQuantity(), o.getPrice(), now);
        if (placed != null) schedulePlacementFeatures(ev.getSlot(), placed);
        CompletedFill fill = capture.onOffer(ev.getSlot(), state, o.getItemId(),
            o.getTotalQuantity(), o.getQuantitySold(), o.getSpent(), now);
        if (fill != null) {
            fill.account = displayName; // attribute to the current account (null → server "Unknown")
            fill.ownerId = captureOwner();
            pendingTrades.addLast(fill);
            savePending(); // durable before any post attempt, so a close/crash can't lose it
        }
        if (journal != null) {
            OfferEvent offerEvent = journal.onState(ev.getSlot(), state, o.getItemId(),
                o.getTotalQuantity(), o.getQuantitySold(), o.getPrice(), now);
            if (offerEvent != null) {
                offerEvent.ownerId = captureOwner();
                synchronized (this) {
                    pendingEvents.addLast(offerEvent);
                    trimPendingEvents();
                    saveEvents();
                }
            }
        }
        // Any offer change (new/updated/removed live offer, or a fill) refreshes the positions view
        // promptly instead of waiting for the next 60s poll.
        scheduleRefresh();
    }

    /** Ask the server for market features now, and keep them on the offer until it resolves. */
    private void schedulePlacementFeatures(int slot, com.gecopilot.client.trade.OpenOfferDto placed) {
        final long openedAt = placed.openedAt;
        final int itemId = placed.itemId;
        final boolean buy = placed.buy;
        final long price = placed.price;
        final int qty = placed.total;
        executor.execute(() -> {
            if (!ensureLogin()) return;
            com.gecopilot.client.api.PlacementFeatures fx = api.placementFeatures(itemId, buy, price, qty);
            if (fx != null) capture.attachFeatures(slot, openedAt, fx.features, fx.fv);
        });
    }

    /**
     * Coffer account a fill or offer event captured now belongs to: the current login, or while
     * signed out the last login seen on this RS account (so a session that lapsed mid-trading
     * keeps its fills, and nobody else can claim them). Null if this RS account never had one.
     */
    private Long captureOwner() {
        Long uid = api.userId();
        if (uid != null) return uid;
        long acct = loadedAccount;
        if (acct == Long.MIN_VALUE) return null;
        String s = configManager.getConfiguration("gecopilotcloud", "lastCofferUser_" + acct);
        try { return isBlank(s) ? null : Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private void rememberOwner(long uid) {
        long acct = loadedAccount;
        if (acct == Long.MIN_VALUE) return;
        String v = Long.toString(uid);
        if (!v.equals(configManager.getConfiguration("gecopilotcloud", "lastCofferUser_" + acct)))
            configManager.setConfiguration("gecopilotcloud", "lastCofferUser_" + acct, v);
    }

    /**
     * Post this login's queued fills in order; stop (keep the rest) on the first failure so nothing
     * is lost. Another login's fills stay queued for that login (see QueueOwnership).
     */
    private synchronized void flushTrades() {
        Long uid = api.userId();
        if (uid == null) return;
        rememberOwner(uid);
        boolean changed = com.gecopilot.client.trade.QueueOwnership.drain(pendingTrades, uid, System.currentTimeMillis(),
            f -> api.postTrade(f.itemId, f.buy, f.qty, f.price, f.ts, f.account, f.fillKey),
            () -> uid.equals(api.userId()));
        if (changed) savePending(); // shrink the durable copy as fills are accepted
        panel.setHeldFills(com.gecopilot.client.trade.QueueOwnership.heldForOthers(pendingTrades, uid));
    }

    // Durable pending-fill queue (per RS account): fills captured while logged-out/offline survive
    // a close or crash.
    private void savePending() {
        if (loadedAccount == Long.MIN_VALUE) return; // no account yet — nothing scoped to save under
        configManager.setConfiguration("gecopilotcloud", "pendingTrades_" + loadedAccount,
            gson.toJson(new java.util.ArrayList<>(pendingTrades)));
    }
    /** Post this login's queued offer events. On failure they stay, so a logged-out stretch keeps them. */
    private synchronized void flushOfferEvents() {
        Long uid = api.userId();
        if (uid == null || pendingEvents.isEmpty()) return;
        boolean changed = com.gecopilot.client.trade.QueueOwnership.drainBatch(pendingEvents, uid, System.currentTimeMillis(), 40,
            api::postOfferEvents, () -> uid.equals(api.userId()));
        if (changed) saveEvents();
    }

    private synchronized void trimPendingEvents() {
        // Events held for another Coffer login go first, so they never push out this login's.
        com.gecopilot.client.trade.QueueOwnership.trim(pendingEvents, MAX_PENDING_EVENTS, captureOwner());
    }

    private void maybeLogPlan(SyncResult sync) {
        if (sync == null || sync.plan == null || sync.plan.isEmpty()) return;
        StringBuilder fp = new StringBuilder();
        for (PlanRowDto r : sync.plan) {
            if (r == null) continue;
            fp.append(r.id).append(':').append(r.qty).append(':')
                .append(r.buyPrice).append(':').append(r.sellPrice).append(';');
        }
        String fingerprint = fp.toString();
        if (fingerprint.isEmpty() || fingerprint.equals(lastPlanFp)) return;
        String inputs = "bankroll=" + config.bankroll()
            + ",slots=" + config.slots()
            + ",pace=" + config.pace()
            + ",access=" + config.access()
            + ",minVolume=" + config.minVolume()
            + ",minMargin=" + config.minMargin()
            + ",minRoi=" + config.minRoi()
            + ",fillTarget=" + config.fillTarget();
        if (api.postPlanShown(inputs, sync.plan)) lastPlanFp = fingerprint;
    }

    private synchronized void saveEvents() {
        if (loadedAccount == Long.MIN_VALUE) return;
        configManager.setConfiguration("gecopilotcloud", "pendingOfferEvents_" + loadedAccount,
            gson.toJson(new java.util.ArrayList<>(pendingEvents)));
    }

    private java.util.List<OfferEvent> loadEvents(long acct) {
        java.util.List<OfferEvent> out = new java.util.ArrayList<>();
        String json = configManager.getConfiguration("gecopilotcloud", "pendingOfferEvents_" + acct);
        if (isBlank(json)) return out;
        try {
            java.util.List<OfferEvent> list = gson.fromJson(json,
                new com.google.gson.reflect.TypeToken<java.util.List<OfferEvent>>() {}.getType());
            if (list != null) out.addAll(list);
        } catch (Exception ignored) { }
        return out;
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
