package com.gecopilot.client.ui;

import com.gecopilot.client.api.*;
import com.gecopilot.client.trade.BuyLimits;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

public class CloudPanel extends PluginPanel {
    private static final NumberFormat GP = NumberFormat.getIntegerInstance();
    private static final Color BLUE = new Color(0x4f, 0xc3, 0xf7);
    // Advice colours by meaning (PR #43 review): a loss you are locking in, a cost we only partly
    // know, and everything else. #FF7B72 keeps >= 4.5:1 contrast on the dark card.
    static final Color WARN = new Color(0xFF, 0x7B, 0x72);
    static final Color PARTIAL = new Color(0xFF, 0xB0, 0x20);
    static final Color NEUTRAL = new Color(0xA8, 0xA8, 0xA8);
    static final Color NEUTRAL_VALUE = new Color(0xF2, 0xEF, 0xE6);
    private static final int HERD_WARN = 3; // others-buying count that flips the herd line amber

    private final ItemManager itemManager;
    private final JLabel pnl = new JLabel("0 gp");
    private final JLabel flipsValue = new JLabel("0");
    // Time-window selector for the headline P&L (Session / 24h / 7d / All): compact segmented buttons
    // inline with the account dropdown. Defaults to All (old lifetime behaviour).
    private static final String[] PERIODS = {"Session", "24h", "7d", "All"};
    private final JButton[] periodBtns = {periodBtn("Session"), periodBtn("24h"), periodBtn("7d"), periodBtn("All")};
    private String pnlWindowSel = "All";
    private Dashboard lastDash;
    // RS-account selector for P&L; only shown once the user has traded under 2+ accounts.
    private final JComboBox<String> accountBox = new JComboBox<>(new String[]{"All accounts"});
    private boolean populatingAccounts = false;
    private java.util.function.Consumer<String> onAccountChange = a -> {};
    // Panel on screen (tab selected and sidebar expanded). RuneLite drives it via onActivate/onDeactivate.
    private volatile boolean active = false;
    // Header cue for the refresh that runs when the panel reopens. Swing state: EDT only.
    private final JLabel refreshStatus = new JLabel(" ");
    private RefreshStatus.State refreshState = RefreshStatus.State.IDLE;
    private long lastRefreshOkMs = 0;
    private boolean figuresDimmed; // headline figures drawn at DIM_ALPHA while a refresh runs. EDT only.
    private java.util.function.Consumer<Boolean> onVisibility = v -> {};
    private final JLabel fillRateValue = new JLabel("-");
    private final JLabel goalLine = new JLabel("Set a profit goal in Settings");

    private final CardLayout cards = new CardLayout();
    // Size to the visible card only, so short tabs don't leave dead scroll space.
    private final JPanel content = new JPanel(cards) {
        @Override public Dimension getPreferredSize() {
            for (Component c : getComponents()) if (c.isVisible()) return c.getPreferredSize();
            return super.getPreferredSize();
        }
    };

    private final CardLayout finderCards = new CardLayout();
    private final JPanel finderContent = new JPanel(finderCards) {
        @Override public Dimension getPreferredSize() {
            for (Component c : getComponents()) if (c.isVisible()) return c.getPreferredSize();
            return super.getPreferredSize();
        }
    };
    private final JComboBox<String> finderMode = new JComboBox<>(new String[]{"Flips", "Plan", "Alch", "Crash", "Search"});
    private final JTextField searchField = new JTextField();

    private final JPanel flipsList = column();
    private final JPanel previewList = column();
    private final PreviewSavePrompt previewSave = new PreviewSavePrompt();
    private final JPanel previewSaveCard = new JPanel();
    private final JPanel previewSaveAsk = new JPanel();
    private final JPanel previewSaveFields = new JPanel();
    private final JTextField saveUserField = new JTextField();
    private final JPasswordField savePassField = new JPasswordField();
    private final JTextField saveEmailField = new JTextField();
    private final JTextArea saveError = new JTextArea(" ");
    private java.util.List<PreviewFlip> lastPreview = new java.util.ArrayList<>();
    private final JPanel planList = column();
    private final JPanel alchList = column();
    private final JPanel crashList = column();
    private final JPanel searchList = column();
    private final JPanel posList = column();
    private final JPanel watchList = column();
    private final JPanel logList = column();
    private final JLabel milestoneLine = new JLabel(" "); // "MILESTONES · x/6 · NEXT: ..." caption atop the Log tab

    private final JButton tabFinder = tab("Finder");
    private final JButton tabPos = tab("Pos");
    private final JButton tabWatch = tab("Watch");
    private final JButton tabLog = tab("Log");

    private final Set<Integer> watched = new HashSet<>();
    private Runnable onLogSelected = () -> {};
    private IntConsumer onWatchToggle = id -> {};
    private Consumer<String> onSearch = q -> {};

    // Sparkline (item price chart) expand state — kept at panel level, not inside the card
    // components, because fill() fully rebuilds every card from its DTO on each 60s poll.
    private final Set<Integer> sparklineOpen = new HashSet<>();
    private final Map<Integer, SparklineDto> sparklineCache = new HashMap<>();
    private final Set<Integer> sparklineLoading = new HashSet<>();
    private final Map<Integer, String> sparklineError = new HashMap<>();
    private IntConsumer onSparklineToggle = id -> {};
    private List<PlanRowDto> lastPlan = new ArrayList<>();
    private List<PositionDto> lastPositions = new ArrayList<>();
    private static final int SPARKLINE_BLOCK_HEIGHT = 134;

    // login/main gate
    private final CardLayout rootCards = new CardLayout();
    private final JTextField userField = new JTextField();
    private final JPasswordField passField = new JPasswordField();
    private final JTextField emailField = new JTextField();
    private final JLabel loginError = new JLabel(" ");
    // In-panel password reset (step 1: email -> code; step 2: code + new password) — replaces the
    // browser-link affordance removed in 5530c12 (LinkBrowser.open is a restricted Hub API).
    private final JTextField forgotEmailField = new JTextField();
    private final JLabel forgotStatus = new JLabel(" ");
    private final JTextField resetCodeField = new JTextField();
    private final JPasswordField newPassField = new JPasswordField();
    private final JPasswordField confirmPassField = new JPasswordField();
    private final JLabel resetError = new JLabel(" ");
    private JPanel resetStep2;
    // Mandatory account-setup gate (Iris design, Lex copy): blocks the product until the account
    // has a recovery email + recorded ToS assent. The email field (and its "why" sub-line) is
    // hidden when the account already has one (e.g. a legacy account only missing the ToS half) —
    // the headline label itself always stays visible so the card never reads as empty/broken.
    private final JTextField gateEmailField = new JTextField();
    private final JLabel gateEmailLabel = new JLabel("<html>Add a recovery email to keep<br>using Coffer.</html>");
    private final JLabel gateSubLabel = new JLabel("<html>One-time step — it's what lets us verify it's you<br>and get you back in if you're ever locked out.</html>");
    // JTextArea (not JLabel) so a long server-supplied error message word-wraps instead of
    // clipping off the edge of the ~225px panel — same technique as the assent block below.
    private final JTextArea gateError = new JTextArea(" ");
    private final JPanel accountBar = new JPanel(new BorderLayout());
    private final JLabel connNotice = new JLabel();
    private final JPanel recapBanner = new JPanel(); // "since you last opened" session recap (hidden until shown)
    private final JPanel historyBanner = new JPanel(); // GE History import prompt (hidden until offered)
    private final JPanel onboardingBanner = new JPanel(); // one-time first-run guide (hidden until shown)
    private final JPanel whatsNewBanner = new JPanel(); // shown once after a version update
    private final JPanel settingsCard = new JPanel();
    private final JPasswordField webhookField = new JPasswordField();
    private final JCheckBox alertsCheck = new JCheckBox("Off-client alerts");
    private final JCheckBox recoverCheck = new JCheckBox("Price recovered");
    private final JCheckBox positionCheck = new JCheckBox("Position warning");
    private final JCheckBox crashCheck = new JCheckBox("Price crash");
    private final JComboBox<Integer> alertCadence = new JComboBox<>(new Integer[]{5, 15, 30, 60});
    private final JButton saveAlerts = new JButton("Save");
    private final JLabel alertsStatusLbl = new JLabel(" ");
    // Warning shown when the server auto-disabled alerts after the Discord webhook kept failing.
    private final JLabel alertsBrokenLbl = new JLabel("<html>Your Discord webhook stopped working,<br>so alerts are paused. Re-add your<br>webhook below to turn them back on.</html>");
    private final JCheckBox quietCheck = new JCheckBox("Quiet hours (no off-client alerts in this window)");
    private final JTextField quietStartField = new JTextField();
    private final JTextField quietEndField = new JTextField();
    // in-client nudge inputs (Settings card)
    private final JCheckBox nudgesCheck = new JCheckBox("In-client nudges");
    private final JComboBox<Integer> nudgeCadence = new JComboBox<>(new Integer[]{5, 15, 30, 60});
    // flip-preference inputs (Settings card)
    private final JTextField prefBankroll = new JTextField();
    private final JTextField prefSlots = new JTextField();
    private final JComboBox<String> prefPace = new JComboBox<>(new String[]{"active", "steady", "patient"});
    private final JComboBox<String> prefAccess = new JComboBox<>(new String[]{"all", "members", "f2p"});
    private final JComboBox<String> prefRisk = new JComboBox<>(new String[]{"conservative", "balanced", "aggressive", "custom"});
    private final JTextField prefMinVol = new JTextField();
    private final JTextField prefMinMargin = new JTextField();
    private final JTextField prefMinRoi = new JTextField();
    private final JTextField prefFillTarget = new JTextField();
    // risk preset -> {minVolume, minMargin, minRoi(%*10 to keep int-ish? no, double), fillTarget}
    private static final java.util.Map<String, double[]> RISK_PRESETS = new java.util.HashMap<String, double[]>() {{
        put("conservative", new double[]{1000, 50, 1.0, 85});
        put("balanced",     new double[]{200, 10, 0.3, 75});
        put("aggressive",   new double[]{50, 1, 0.0, 60});
    }};
    private final JTextField prefGoal = new JTextField();
    private final JTextField prefGoalDate = new JTextField();
    // Beta feedback form (Settings).
    private final JComboBox<String> feedbackKind = new JComboBox<>(new String[]{"Bug", "Feature", "Other"});
    private final JTextArea feedbackMsg = new JTextArea(4, 16);
    private final JTextField feedbackContact = new JTextField();
    private final JLabel feedbackStatus = new JLabel(" ");
    private String currentTier = "free";
    private final JLabel sessionToast = new JLabel(" ");
    // Quiet note: fills captured under another Coffer login wait here (dropped after 7 days).
    private final JLabel heldNote = new JLabel(" ");
    private Timer sessionToastTimer;
    private String sessionGuideText;
    private boolean sessionGuideDismissed;
    private SessionGuide.Stage sessionStage = SessionGuide.Stage.DONE;
    private String planEmpty = "Nothing to place right now.";
    private String posEmpty = "No open offers.";
    private String accountName = "";
    public interface PrefsSaver { void save(int bankroll, int slots, String pace, String access, String risk, int minVolume, int minMargin, double minRoi, int fillTarget); }
    public interface GoalSaver { void save(long goalGp, long deadline); }
    public interface AlertSaver { void save(String webhook, boolean enabled, boolean recover, boolean position, boolean crash, int cooldownMin, boolean quietEnabled, int quietStart, int quietEnd); }
    public interface NudgeSaver { void save(boolean enabled, int cooldownMin); }
    public interface FeedbackSender { void send(String kind, String message, String contact); }
    public interface RegisterSubmitter { void submit(String user, String pass, String email); }
    public interface ForgotSubmitter { void submit(String email); }
    public interface ResetSubmitter { void submit(String token, String newPassword); }
    /** email is "" when the account already has one — the mandatory gate only needs the ToS ack. */
    public interface GateSubmitter { void submit(String email); }
    private FeedbackSender onSendFeedback = (k, m, c) -> {};
    private BiConsumer<String, String> onLogin = (u, p) -> {};
    private RegisterSubmitter onRegister = (u, p, em) -> {};
    private RegisterSubmitter onSaveSession = (u, p, em) -> {};
    private ForgotSubmitter onForgot = email -> {};
    private ResetSubmitter onReset = (token, pass) -> {};
    private GateSubmitter onGateContinue = email -> {};
    private Runnable onLogout = () -> {};
    private AlertSaver onSaveAlerts = (w, e, r, p, c, cd, qe, qs, qn) -> {};
    private Runnable onTestAlert = () -> {};
    private NudgeSaver onSaveNudges = (e, cd) -> {};
    private Runnable onOpenSettings = () -> {};
    private PrefsSaver onSavePrefs = (b, s, p, a, r, mv, mm, mr, ft) -> {};
    private GoalSaver onSaveGoal = (g, d) -> {};

    // rootCards is a CardLayout, whose preferred size is the max over ALL cards; size to the
    // visible card instead so a short tab (e.g. login) doesn't leave dead scroll space under a
    // tall one (settings), and vice-versa.
    @Override public Dimension getPreferredSize() {
        Dimension base = super.getPreferredSize(); // keep PluginPanel's fixed width
        for (Component c : getComponents())
            if (c.isVisible()) return new Dimension(base.width, c.getPreferredSize().height);
        return base;
    }

    /** Buy-limit windows this client saw; the plugin swaps in its tracker. Read when cards are built. */
    private volatile BuyLimits buyLimits = new BuyLimits();

    public void setBuyLimits(BuyLimits limits) { if (limits != null) buyLimits = limits; }

    public CloudPanel(ItemManager itemManager) {
        this.itemManager = itemManager;
        setLayout(rootCards);
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel header = new JPanel(new GridBagLayout());
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnl.setFont(FontManager.getRunescapeFont());
        pnl.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        flipsValue.setFont(FontManager.getRunescapeSmallFont());
        flipsValue.setForeground(Color.WHITE);
        fillRateValue.setFont(FontManager.getRunescapeSmallFont());
        fillRateValue.setForeground(Color.WHITE);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0; gc.anchor = GridBagConstraints.WEST; gc.weightx = 1;
        header.add(caption("REALIZED P&L"), gc); // selectors live in the filters block above — never on this row
        gc.gridx = 1; gc.anchor = GridBagConstraints.EAST; gc.weightx = 0;
        header.add(pnl, gc);
        statRow(header, 1, "FLIPS DONE", flipsValue);
        statRow(header, 2, "FILL RATE", fillRateValue);
        goalLine.setFont(FontManager.getRunescapeSmallFont());
        goalLine.setForeground(BLUE);
        gc.gridx = 0; gc.gridy = 3; gc.gridwidth = 2; gc.weightx = 1; gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(6, 0, 0, 0);
        header.add(goalLine, gc);
        JLabel ver = caption("COFFER v" + com.gecopilot.client.ServerConfig.VERSION);
        gc.gridy = 4; gc.insets = new Insets(8, 0, 0, 0);
        header.add(ver, gc);
        // Refresh cue: same muted caption style, on its own row under the version (sharing the version
        // row would truncate it in the narrow sidebar). The row is always there and blank until the
        // first refresh, so switching states never moves anything.
        refreshStatus.setFont(FontManager.getRunescapeSmallFont());
        refreshStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gc.gridy = 5; gc.insets = new Insets(2, 0, 0, 0);
        header.add(refreshStatus, gc);

        // Scope filters above the stat card, one row: account dropdown (left, shown only with 2+
        // accounts) + compact segmented period buttons. Kept OUT of the P&L number's grid row so a
        // wide value can't clip them. Account combo width-capped so long names don't push the
        // buttons off; the row wraps gracefully if the panel is very narrow.
        accountBox.setFont(FontManager.getRunescapeSmallFont());
        accountBox.setVisible(false); // shown once a 2nd account appears
        accountBox.setPrototypeDisplayValue("Omega636"); // cap combo width (RuneLite L&F ignores setPreferredSize)
        accountBox.addActionListener(e -> {
            if (populatingAccounts) return;
            onAccountChange.accept((String) accountBox.getSelectedItem());
        });
        JPanel segments = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        segments.setBackground(ColorScheme.DARK_GRAY_COLOR);
        for (int i = 0; i < periodBtns.length; i++) {
            final String p = PERIODS[i];
            periodBtns[i].addActionListener(e -> selectPeriod(p));
            segments.add(periodBtns[i]);
        }
        selectPeriod("All"); // initial styling + selection

        // BorderLayout keeps account (WEST) + period buttons (CENTER) on ONE line — it compresses
        // rather than wrapping like FlowLayout did.
        JPanel filters = new JPanel(new BorderLayout(4, 0));
        filters.setBackground(ColorScheme.DARK_GRAY_COLOR);
        filters.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 2));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        filters.add(accountBox, BorderLayout.WEST);
        filters.add(segments, BorderLayout.CENTER);
        filters.setMaximumSize(new Dimension(Integer.MAX_VALUE, accountBox.getPreferredSize().height + 8));

        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setBackground(ColorScheme.DARK_GRAY_COLOR);
        accountBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel tabsWrap = tabBar();
        tabsWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        recapBanner.setLayout(new BoxLayout(recapBanner, BoxLayout.Y_AXIS));
        recapBanner.setBackground(new Color(0x1a, 0x22, 0x2e));
        recapBanner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        recapBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        recapBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
        recapBanner.setVisible(false);
        historyBanner.setLayout(new BoxLayout(historyBanner, BoxLayout.Y_AXIS));
        historyBanner.setBackground(new Color(0x3a, 0x33, 0x1a));
        historyBanner.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        historyBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE)); // fill panel width
        historyBanner.setVisible(false);
        onboardingBanner.setLayout(new BoxLayout(onboardingBanner, BoxLayout.Y_AXIS));
        onboardingBanner.setBackground(new Color(0x1a, 0x2a, 0x22));
        onboardingBanner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        onboardingBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        onboardingBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
        onboardingBanner.setVisible(false);
        whatsNewBanner.setLayout(new BoxLayout(whatsNewBanner, BoxLayout.Y_AXIS));
        whatsNewBanner.setBackground(new Color(0x1a, 0x22, 0x2e));
        whatsNewBanner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        whatsNewBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        whatsNewBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
        whatsNewBanner.setVisible(false);
        connNotice.setOpaque(true);
        connNotice.setBackground(new Color(0x4a, 0x2a, 0x10));
        connNotice.setForeground(Color.WHITE);
        connNotice.setFont(FontManager.getRunescapeSmallFont());
        connNotice.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        connNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
        connNotice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        connNotice.setVisible(false);
        northStack.add(connNotice);
        northStack.add(accountBar);
        northStack.add(filters);
        northStack.add(header);
        northStack.add(tabsWrap);
        northStack.add(recapBanner);
        northStack.add(historyBanner);
        sessionToast.setFont(FontManager.getRunescapeSmallFont());
        sessionToast.setForeground(ColorScheme.BRAND_ORANGE);
        sessionToast.setAlignmentX(Component.LEFT_ALIGNMENT);
        sessionToast.setVisible(false);
        sessionToast.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        northStack.add(sessionToast);
        heldNote.setFont(FontManager.getRunescapeSmallFont());
        heldNote.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        heldNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        heldNote.setVisible(false);
        heldNote.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));
        northStack.add(heldNote);
        northStack.add(onboardingBanner);
        northStack.add(whatsNewBanner);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(ColorScheme.DARK_GRAY_COLOR);
        main.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        main.add(northStack, BorderLayout.NORTH);
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);
        content.add(finderPanel(), "finder");
        content.add(wrap(posList), "positions");
        content.add(wrap(watchList), "watch");
        milestoneLine.setFont(FontManager.getRunescapeSmallFont());
        milestoneLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        milestoneLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        milestoneLine.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        JPanel logTab = new JPanel(new BorderLayout());
        logTab.setBackground(ColorScheme.DARK_GRAY_COLOR);
        logTab.add(milestoneLine, BorderLayout.NORTH);
        logTab.add(wrap(logList), BorderLayout.CENTER);
        content.add(logTab, "log");
        main.add(content, BorderLayout.CENTER);
        selectTab("finder");

        settingsCard.setLayout(new BoxLayout(settingsCard, BoxLayout.Y_AXIS));
        settingsCard.setBackground(ColorScheme.DARK_GRAY_COLOR);
        settingsCard.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(withStatus(buildLogin()), "login");
        add(withStatus(main), "main");
        add(settingsCard, "settings");
        add(withStatus(buildPreview()), "preview");
        add(withStatus(buildReset()), "reset");
        add(withStatus(buildGate()), "gate");
        rootCards.show(this, "login");
    }

    // Service-status banner: one per card (a component can sit in only one card), all showing the
    // same state. Settings has none; it is a short detour from the main card.
    private final List<StatusBanner> statusBanners = new ArrayList<>();
    private boolean statusShowing = false; // EDT only
    private String connNoticeMsg = null;   // EDT only

    private JPanel withStatus(JPanel card) {
        StatusBanner b = new StatusBanner();
        statusBanners.add(b);
        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(ColorScheme.DARK_GRAY_COLOR);
        north.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        north.add(b, BorderLayout.CENTER);
        north.setVisible(false); // shown with the banner (setServiceStatus), so no gap while clear
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrap.add(north, BorderLayout.NORTH);
        wrap.add(card, BorderLayout.CENTER);
        return wrap;
    }

    /**
     * Show or clear the service-status banner on every card. While it shows, the smaller
     * "Can't reach Coffer" notice stays hidden: the banner already says so.
     */
    public void setServiceStatus(StatusMonitor.Banner b) {
        SwingUtilities.invokeLater(() -> {
            for (StatusBanner sb : statusBanners) {
                sb.show(b);
                sb.getParent().setVisible(b != null);
            }
            statusShowing = b != null;
            applyConnNotice();
            revalidate();
            repaint();
        });
    }

    /** Test hook: the banner text, or null when hidden. */
    String serviceStatusText() { return statusBanners.isEmpty() ? null : statusBanners.get(0).shownText(); }

    private JPanel buildLogin() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createEmptyBorder(16, 12, 12, 12));
        JLabel title = new JLabel("Coffer");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(BLUE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        userField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        passField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        emailField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        userField.setAlignmentX(Component.LEFT_ALIGNMENT);
        passField.setAlignmentX(Component.LEFT_ALIGNMENT);
        emailField.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton login = new JButton("Log in");
        login.setAlignmentX(Component.LEFT_ALIGNMENT);
        login.addActionListener(e -> onLogin.accept(userField.getText().trim(), new String(passField.getPassword())));
        passField.addActionListener(e -> login.doClick());
        JButton create = new JButton("Create account");
        create.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Email is only required to create an account, not to log in — the login button above
        // never reads emailField. Client-side check is UX only; the server re-validates.
        create.addActionListener(e -> {
            String email = emailField.getText().trim();
            if (!isValidEmail(email)) {
                loginError.setText("Enter a valid email to create an account");
                return;
            }
            onRegister.submit(userField.getText().trim(), new String(passField.getPassword()), email);
        });
        JLabel tosNotice = new JLabel("<html>By creating an account, you agree<br>to Coffer's Terms of Service<br>"
                + "(meetcoffer.com/terms) and Privacy<br>Policy (meetcoffer.com/privacy).</html>");
        tosNotice.setFont(FontManager.getRunescapeSmallFont());
        tosNotice.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        tosNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginError.setFont(FontManager.getRunescapeSmallFont());
        loginError.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        loginError.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel forgot = new JLabel("Forgot password?");
        forgot.setFont(FontManager.getRunescapeSmallFont());
        forgot.setForeground(BLUE);
        forgot.setAlignmentX(Component.LEFT_ALIGNMENT);
        forgot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        forgot.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                resetForgotView();
                rootCards.show(CloudPanel.this, "reset");
            }
        });
        p.add(title); p.add(gap());
        p.add(smallLabel("Username")); p.add(userField); p.add(gap());
        p.add(smallLabel("Password")); p.add(passField); p.add(gap());
        p.add(forgot); p.add(gap());
        p.add(smallLabel("Email (required to create an account)")); p.add(emailField); p.add(gap());
        p.add(login); p.add(gap()); p.add(tosNotice); p.add(gap()); p.add(create); p.add(gap()); p.add(loginError);
        return p;
    }

    /** Mandatory account-setup gate — shown instead of "main" when the account is missing a
     *  recovery email or ToS assent. */
    private JPanel buildGate() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createEmptyBorder(16, 12, 12, 12));
        JLabel title = new JLabel("Coffer");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(BLUE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Headline stays visible either way (email-needed or ToS-only) so the card always states
        // what's being asked — text is swapped in showGate(). Bolded so it reads as the one thing
        // to act on, not another muted status line.
        gateEmailLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        gateEmailLabel.setForeground(Color.WHITE);
        gateEmailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        gateSubLabel.setFont(FontManager.getRunescapeSmallFont());
        gateSubLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        gateSubLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        gateEmailField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        gateEmailField.setAlignmentX(Component.LEFT_ALIGNMENT);
        // LinkBrowser.open is a restricted Hub API — name the URLs as plain, non-clickable text.
        JTextArea assent = new JTextArea("By continuing, you agree to Coffer's current Terms of "
            + "Service (meetcoffer.com/terms) and Privacy Policy (meetcoffer.com/privacy).");
        assent.setEditable(false); assent.setFocusable(false);
        assent.setLineWrap(true); assent.setWrapStyleWord(true); assent.setOpaque(false); assent.setBorder(null);
        assent.setFont(FontManager.getRunescapeSmallFont());
        assent.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        assent.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton cont = new JButton("Continue");
        cont.setAlignmentX(Component.LEFT_ALIGNMENT);
        cont.addActionListener(e -> {
            String email = "";
            if (gateEmailField.isVisible()) {
                email = gateEmailField.getText().trim();
                if (!isValidEmail(email)) { gateError.setText("Enter a valid email."); return; }
            }
            gateError.setText(" ");
            onGateContinue.submit(email);
        });
        gateError.setEditable(false); gateError.setFocusable(false);
        gateError.setLineWrap(true); gateError.setWrapStyleWord(true); gateError.setOpaque(false); gateError.setBorder(null);
        gateError.setFont(FontManager.getRunescapeSmallFont());
        gateError.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        gateError.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel support = new JLabel("<html>Need help? Contact support:<br>admin@meetcoffer.com</html>");
        support.setFont(FontManager.getRunescapeSmallFont());
        support.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        support.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(title); p.add(gap());
        p.add(gateEmailLabel); p.add(gap());
        p.add(gateSubLabel); p.add(gap());
        p.add(gateEmailField); p.add(gap());
        p.add(assent); p.add(gap());
        p.add(cont); p.add(gap());
        p.add(gateError); p.add(gap());
        p.add(support);
        return p;
    }

    /** Show the onboarding gate instead of "main". {@code email} is the account's CURRENT recovery
     *  email (possibly empty) — when non-empty, only the ToS half is missing, so the email field
     *  (and its "why" sub-line) is hidden, the headline swaps to a Terms-only prompt, and Continue
     *  only needs to record assent. Caller (the plugin) tracks tier/username and calls showMain()
     *  itself once the gate reports needsOnboarding == false. */
    public void showGate(String email) {
        SwingUtilities.invokeLater(() -> {
            boolean needsEmail = email == null || email.isEmpty();
            // Wrapped as <html> — plain JLabel text doesn't wrap, and at the panel's ~225px width
            // both headlines are too long for one line.
            gateEmailLabel.setText(needsEmail
                ? "<html>Add a recovery email to keep<br>using Coffer.</html>"
                : "<html>Confirm Coffer's current Terms<br>to continue.</html>");
            gateSubLabel.setVisible(needsEmail);
            gateEmailField.setVisible(needsEmail);
            gateEmailField.setText("");
            gateError.setText(" ");
            rootCards.show(this, "gate");
        });
    }

    public void showGateError(String message) {
        SwingUtilities.invokeLater(() -> gateError.setText(message != null ? message : " "));
    }

    public void onGateContinue(GateSubmitter cb) { this.onGateContinue = cb != null ? cb : email -> {}; }

    /** Simple non-verifying format check: exactly one '@' (not first char), a '.' inside the
     *  domain part. UX gate only — JDK 11, no regex dependency; the server is the real gate. */
    private static boolean isValidEmail(String email) {
        if (email == null) return false;
        String e = email.trim();
        if (e.isEmpty() || e.indexOf(' ') >= 0) return false;
        int at = e.indexOf('@');
        if (at <= 0 || e.indexOf('@', at + 1) >= 0) return false;
        String domain = e.substring(at + 1);
        int dot = domain.indexOf('.');
        return dot > 0 && dot < domain.length() - 1;
    }

    /** Plugin Hub disallows programmatically opening a browser (LinkBrowser.open / Desktop.open
     *  are both restricted APIs). Copy the URL to the clipboard instead and flash a confirmation
     *  on the label so the user knows where to paste it. */
    static void copyLinkToClipboard(String url, JLabel label, String originalText) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
        label.setText("Link copied — paste in your browser");
        Timer revert = new Timer(1800, e -> label.setText(originalText));
        revert.setRepeats(false);
        revert.start();
    }

    /** In-panel, 2-step password reset (replaces the browser-link affordance removed in 5530c12).
     *  Step 1 emails a reset code; step 2 takes that code + a new password. Both hit the same
     *  /auth/forgot and /auth/reset endpoints the web dashboard uses. */
    private JPanel buildReset() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createEmptyBorder(16, 12, 12, 12));
        JLabel title = new JLabel("Reset password");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(BLUE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        forgotEmailField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        forgotEmailField.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton sendCode = new JButton("Email me a reset code");
        sendCode.setAlignmentX(Component.LEFT_ALIGNMENT);
        sendCode.addActionListener(e -> onForgot.submit(forgotEmailField.getText().trim()));
        forgotStatus.setFont(FontManager.getRunescapeSmallFont());
        forgotStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        forgotStatus.setAlignmentX(Component.LEFT_ALIGNMENT);

        resetCodeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        newPassField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        confirmPassField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        resetCodeField.setAlignmentX(Component.LEFT_ALIGNMENT);
        newPassField.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmPassField.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetError.setFont(FontManager.getRunescapeSmallFont());
        resetError.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        resetError.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton doReset = new JButton("Reset password");
        doReset.setAlignmentX(Component.LEFT_ALIGNMENT);
        doReset.addActionListener(e -> {
            String code = resetCodeField.getText().trim();
            String pass = new String(newPassField.getPassword());
            String confirm = new String(confirmPassField.getPassword());
            if (pass.length() < 8) { resetError.setText("Password must be 8+ characters"); return; }
            if (!pass.equals(confirm)) { resetError.setText("Passwords don't match"); return; }
            resetError.setText(" ");
            onReset.submit(code, pass);
        });

        resetStep2 = new JPanel();
        resetStep2.setLayout(new BoxLayout(resetStep2, BoxLayout.Y_AXIS));
        resetStep2.setBackground(ColorScheme.DARK_GRAY_COLOR);
        resetStep2.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetStep2.add(smallLabel("Reset code (from email)")); resetStep2.add(resetCodeField); resetStep2.add(gap());
        resetStep2.add(smallLabel("New password")); resetStep2.add(newPassField); resetStep2.add(gap());
        resetStep2.add(smallLabel("Confirm new password")); resetStep2.add(confirmPassField); resetStep2.add(gap());
        resetStep2.add(doReset); resetStep2.add(gap()); resetStep2.add(resetError);
        resetStep2.setVisible(false);

        JLabel back = new JLabel("Back to sign in");
        back.setFont(FontManager.getRunescapeSmallFont());
        back.setForeground(BLUE);
        back.setAlignmentX(Component.LEFT_ALIGNMENT);
        back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        back.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { rootCards.show(CloudPanel.this, "login"); }
        });

        p.add(title); p.add(gap());
        p.add(smallLabel("Email")); p.add(forgotEmailField); p.add(gap());
        p.add(sendCode); p.add(gap()); p.add(forgotStatus); p.add(gap());
        p.add(resetStep2); p.add(gap());
        p.add(back);
        return p;
    }

    /** Clear the reset view back to its step-1-only state (called each time it's opened). */
    private void resetForgotView() {
        forgotEmailField.setText("");
        forgotStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        forgotStatus.setText(" ");
        resetCodeField.setText("");
        newPassField.setText("");
        confirmPassField.setText("");
        resetError.setText(" ");
        if (resetStep2 != null) resetStep2.setVisible(false);
    }

    private JLabel smallLabel(String t) {
        JLabel l = new JLabel(t);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    // No-account preview. Signup stays hidden until the player taps a flip (see PreviewSavePrompt).
    private JPanel buildPreview() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel banner = new JPanel();
        banner.setLayout(new BoxLayout(banner, BoxLayout.Y_AXIS));
        banner.setBackground(new Color(0x1a, 0x2a, 0x22));
        banner.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        banner.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel t = new JLabel("Free preview");
        t.setFont(FontManager.getRunescapeBoldFont());
        t.setForeground(BLUE);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea sub = new JTextArea("Live F2P flips. Tap one when you want to keep it.");
        sub.setEditable(false); sub.setLineWrap(true); sub.setWrapStyleWord(true); sub.setOpaque(false); sub.setBorder(null);
        sub.setFont(FontManager.getRunescapeSmallFont());
        sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton login = new JButton("Log in");
        login.setAlignmentX(Component.LEFT_ALIGNMENT);
        login.addActionListener(e -> rootCards.show(this, "login"));
        banner.add(t); banner.add(gap()); banner.add(sub); banner.add(gap()); banner.add(login);

        previewSaveCard.setLayout(new BoxLayout(previewSaveCard, BoxLayout.Y_AXIS));
        previewSaveCard.setBackground(new Color(0x1a, 0x2a, 0x22));
        previewSaveCard.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        previewSaveCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewSaveCard.setVisible(false);
        JLabel saveTitle = new JLabel("Save this session");
        saveTitle.setFont(FontManager.getRunescapeBoldFont());
        saveTitle.setForeground(BLUE);
        saveTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea saveBody = new JTextArea("Keep your watches and offers so they\u2019re here next time you open Coffer.");
        saveBody.setEditable(false); saveBody.setLineWrap(true); saveBody.setWrapStyleWord(true);
        saveBody.setOpaque(false); saveBody.setBorder(null);
        saveBody.setFont(FontManager.getRunescapeSmallFont());
        saveBody.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        saveBody.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewSaveAsk.setLayout(new BoxLayout(previewSaveAsk, BoxLayout.Y_AXIS));
        previewSaveAsk.setOpaque(false);
        previewSaveAsk.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton save = new JButton("Save session");
        save.setAlignmentX(Component.LEFT_ALIGNMENT);
        save.addActionListener(e -> { previewSave.saveClicked(); refreshPreviewSave(); });
        JButton skip = new JButton("Continue without saving");
        skip.setAlignmentX(Component.LEFT_ALIGNMENT);
        skip.addActionListener(e -> dismissPreviewSave());
        previewSaveAsk.add(save); previewSaveAsk.add(gap()); previewSaveAsk.add(skip);

        previewSaveFields.setLayout(new BoxLayout(previewSaveFields, BoxLayout.Y_AXIS));
        previewSaveFields.setOpaque(false);
        previewSaveFields.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewSaveFields.setVisible(false);
        for (JTextField field : new JTextField[]{saveUserField, saveEmailField}) {
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        savePassField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        savePassField.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea assent = new JTextArea("By continuing, you agree to Coffer's current Terms of "
            + "Service (meetcoffer.com/terms) and Privacy Policy (meetcoffer.com/privacy).");
        assent.setEditable(false); assent.setFocusable(false);
        assent.setLineWrap(true); assent.setWrapStyleWord(true); assent.setOpaque(false); assent.setBorder(null);
        assent.setFont(FontManager.getRunescapeSmallFont());
        assent.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        assent.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton saveSubmit = new JButton("Save session");
        saveSubmit.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveSubmit.addActionListener(e -> submitPreviewSave());
        JButton skipFields = new JButton("Continue without saving");
        skipFields.setAlignmentX(Component.LEFT_ALIGNMENT);
        skipFields.addActionListener(e -> dismissPreviewSave());
        saveError.setEditable(false); saveError.setFocusable(false);
        saveError.setLineWrap(true); saveError.setWrapStyleWord(true); saveError.setOpaque(false); saveError.setBorder(null);
        saveError.setFont(FontManager.getRunescapeSmallFont());
        saveError.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        saveError.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewSaveFields.add(smallLabel("Username")); previewSaveFields.add(saveUserField); previewSaveFields.add(gap());
        previewSaveFields.add(smallLabel("Password")); previewSaveFields.add(savePassField); previewSaveFields.add(gap());
        previewSaveFields.add(smallLabel("Email")); previewSaveFields.add(saveEmailField); previewSaveFields.add(gap());
        previewSaveFields.add(assent); previewSaveFields.add(gap());
        previewSaveFields.add(saveSubmit); previewSaveFields.add(gap());
        previewSaveFields.add(skipFields); previewSaveFields.add(gap());
        previewSaveFields.add(saveError);

        previewSaveCard.add(saveTitle); previewSaveCard.add(gap()); previewSaveCard.add(saveBody); previewSaveCard.add(gap());
        previewSaveCard.add(previewSaveAsk); previewSaveCard.add(previewSaveFields);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        north.add(banner);
        north.add(previewSaveCard);
        p.add(north, BorderLayout.NORTH);
        p.add(wrap(previewList), BorderLayout.CENTER);
        return p;
    }

    public void showPreview(java.util.List<PreviewFlip> flips) {
        SwingUtilities.invokeLater(() -> {
            previewSave.reset();
            saveUserField.setText("");
            savePassField.setText("");
            saveEmailField.setText("");
            saveError.setText(" ");
            refreshPreviewSave();
            lastPreview = flips == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(flips);
            paintPreview();
            rootCards.show(this, "preview");
        });
    }

    public void onSaveSession(RegisterSubmitter cb) { this.onSaveSession = cb != null ? cb : (u, p, em) -> {}; }

    public void showPreviewSaveError(String message) {
        SwingUtilities.invokeLater(() -> saveError.setText(message != null ? message : " "));
    }

    private void paintPreview() {
        fill(previewList, lastPreview, this::previewCard, "No F2P flips right now — check back soon.");
    }

    private void refreshPreviewSave() {
        PreviewSavePrompt.Phase phase = previewSave.phase();
        previewSaveCard.setVisible(phase == PreviewSavePrompt.Phase.ASK || phase == PreviewSavePrompt.Phase.FIELDS);
        previewSaveAsk.setVisible(phase == PreviewSavePrompt.Phase.ASK);
        previewSaveFields.setVisible(phase == PreviewSavePrompt.Phase.FIELDS);
        previewSaveCard.revalidate();
        previewSaveCard.repaint();
    }

    private void dismissPreviewSave() {
        previewSave.continueWithoutSaving();
        savePassField.setText("");
        refreshPreviewSave();
        paintPreview();
    }

    private void submitPreviewSave() {
        String email = saveEmailField.getText().trim();
        if (!isValidEmail(email)) { saveError.setText("Enter a valid email."); return; }
        saveError.setText(" ");
        onSaveSession.submit(saveUserField.getText().trim(), new String(savePassField.getPassword()), email);
    }

    private void onPreviewFlipChosen(int itemId) {
        if (!previewSave.onFlipChosen(itemId)) return;
        refreshPreviewSave();
    }

    private JPanel previewCard(PreviewFlip f) {
        JPanel card = baseCard(marginStripe(f.marginPct));
        card.add(titleRow(f.id, f.name, null));
        card.add(gap());
        card.add(row("Margin", "+" + GP.format(f.margin) + " gp  (" + String.format("%.1f%%", f.marginPct) + ")", marginStripe(f.marginPct)));
        if (f.risk != null) card.add(riskLine(f.risk, null));
        card.add(gap());
        String lockText = previewSave.phase() == PreviewSavePrompt.Phase.DISMISSED
            ? "Log in to see prices + plan" : "Tap to keep this flip";
        JLabel lock = centered(lockText);
        lock.setForeground(ColorScheme.BRAND_ORANGE);
        card.add(lock);
        listenTree(card, () -> onPreviewFlipChosen(f.id));
        return card;
    }

    private void listenTree(Component c, Runnable action) {
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { action.run(); }
        });
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) listenTree(child, action);
        }
    }

    public void onLogin(BiConsumer<String, String> cb) { this.onLogin = cb != null ? cb : (u, p) -> {}; }
    public void onRegister(RegisterSubmitter cb) { this.onRegister = cb != null ? cb : (u, p, em) -> {}; }
    public void onForgot(ForgotSubmitter cb) { this.onForgot = cb != null ? cb : email -> {}; }
    public void onReset(ResetSubmitter cb) { this.onReset = cb != null ? cb : (t, pw) -> {}; }

    /** Step 1 succeeded (server reached — always true regardless of whether the email exists, per
     *  anti-enumeration). Reveal step 2 so the user can paste the code. */
    public void showForgotSent() {
        SwingUtilities.invokeLater(() -> {
            forgotStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            forgotStatus.setText("<html>If that email is registered, a reset code is<br>on its way — paste it below.</html>");
            resetStep2.setVisible(true);
        });
    }
    public void showForgotUnreachable() {
        SwingUtilities.invokeLater(() -> {
            forgotStatus.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
            forgotStatus.setText("Couldn't reach server — try again.");
        });
    }
    public void showResetError(String message) {
        SwingUtilities.invokeLater(() -> resetError.setText(message != null ? message : " "));
    }
    /** Reset succeeded — hand back to the sign-in view with a success note. */
    public void showResetDone(String message) {
        SwingUtilities.invokeLater(() -> {
            loginError.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            loginError.setText(message);
            passField.setText("");
            rootCards.show(this, "login");
        });
    }
    public void onLogout(Runnable cb) { this.onLogout = cb != null ? cb : () -> {}; }
    /**
     * A full refresh started (panel reopened or client restored). The last dashboard stays on screen;
     * the header says "Updating…" and the figures dim to ~55% until the refresh finishes.
     */
    public void beginRefresh() {
        SwingUtilities.invokeLater(() -> {
            refreshState = RefreshStatus.State.UPDATING;
            dimFigures(true);
            showRefreshStatus();
        });
    }

    /** A refresh finished. {@code ok}: the server answered; otherwise the header says how old the data is. */
    public void refreshDone(boolean ok) {
        long now = System.currentTimeMillis();
        SwingUtilities.invokeLater(() -> {
            if (ok) lastRefreshOkMs = now;
            refreshState = ok ? RefreshStatus.State.OK : RefreshStatus.State.FAILED;
            dimFigures(false);
            showRefreshStatus();
        });
    }

    String refreshStatusText() { return refreshStatus.getText(); }

    private void showRefreshStatus() {
        refreshStatus.setText(RefreshStatus.text(refreshState, lastRefreshOkMs, System.currentTimeMillis()));
    }

    /** Alpha for the headline figures while a refresh runs: ~55%, readable but clearly stale. */
    static final int DIM_ALPHA = 140;

    private JLabel[] figures() { return new JLabel[]{pnl, flipsValue, fillRateValue, goalLine}; }

    /** Dims or restores every headline figure, keeping each one's current colour. */
    private void dimFigures(boolean dim) {
        if (dim == figuresDimmed) return;
        figuresDimmed = dim;
        for (JLabel f : figures()) setFigureColor(f, f.getForeground());
    }

    /**
     * Colour a headline figure. While dimmed the colour gets DIM_ALPHA, so a figure recoloured
     * mid-refresh (P&L turning red, the goal line going orange) stays dimmed with the rest.
     */
    private void setFigureColor(JLabel f, Color c) {
        f.setForeground(new Color(c.getRed(), c.getGreen(), c.getBlue(), figuresDimmed ? DIM_ALPHA : 255));
    }

    /** Test hook: the alpha each headline figure is drawn with. */
    int[] figureAlphas() {
        JLabel[] f = figures();
        int[] out = new int[f.length];
        for (int i = 0; i < f.length; i++) out[i] = f[i].getForeground().getAlpha();
        return out;
    }

    /** Called with true when the panel comes on screen and false when it leaves (Swing thread). */
    public void onVisibility(java.util.function.Consumer<Boolean> cb) { this.onVisibility = cb != null ? cb : v -> {}; }
    public boolean isActive() { return active; }

    @Override
    public void onActivate() {
        super.onActivate();
        active = true;
        onVisibility.accept(true);
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        active = false;
        onVisibility.accept(false);
    }
    public void onSaveAlerts(AlertSaver cb) { this.onSaveAlerts = cb != null ? cb : (w, e, r, p, c, cd, qe, qs, qn) -> {}; }
    public void onTestAlert(Runnable cb) { this.onTestAlert = cb != null ? cb : () -> {}; }
    public void setAlertStatus(String text) {
        SwingUtilities.invokeLater(() -> alertsStatusLbl.setText(text != null ? text : " "));
    }
    public void onSaveNudges(NudgeSaver cb) { this.onSaveNudges = cb != null ? cb : (e, cd) -> {}; }
    public void onOpenSettings(Runnable cb) { this.onOpenSettings = cb != null ? cb : () -> {}; }
    public void onAccountChange(java.util.function.Consumer<String> cb) { this.onAccountChange = cb != null ? cb : a -> {}; }
    public void onSendFeedback(FeedbackSender cb) { this.onSendFeedback = cb != null ? cb : (k, m, c) -> {}; }

    /** Update the feedback form after a submit attempt (clears the message on success). */
    public void showFeedbackResult(boolean ok) {
        SwingUtilities.invokeLater(() -> {
            feedbackStatus.setText(ok ? "Thanks — sent!" : "Couldn't send — try again.");
            feedbackStatus.setForeground(ok ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
            if (ok) { feedbackMsg.setText(""); feedbackContact.setText(""); }
        });
    }
    public void onSavePrefs(PrefsSaver cb) { this.onSavePrefs = cb != null ? cb : (b, s, p, a, r, mv, mm, mr, ft) -> {}; }
    public void onSaveGoal(GoalSaver cb) { this.onSaveGoal = cb != null ? cb : (g, d) -> {}; }

    /** Visible outage banner above the tabs; null hides it. */
    public void setConnNotice(String msg) {
        SwingUtilities.invokeLater(() -> {
            connNoticeMsg = msg;
            applyConnNotice();
        });
    }

    private void applyConnNotice() {
        connNotice.setText(connNoticeMsg == null ? "" : connNoticeMsg);
        connNotice.setVisible(connNoticeMsg != null && !statusShowing);
    }
    String connNoticeText() { return connNotice.isVisible() ? connNotice.getText() : null; }

    /** Quiet note for fills queued under another Coffer login; 0 hides it. */
    public void setHeldFills(int n) {
        SwingUtilities.invokeLater(() -> {
            heldNote.setText(heldFillsText(n));
            heldNote.setToolTipText(n > 0 ? "Sign in to that Coffer account within 7 days to keep them." : null);
            heldNote.setVisible(n > 0);
        });
    }
    static String heldFillsText(int n) {
        return n <= 0 ? " " : n + (n == 1 ? " fill" : " fills") + " held for another Coffer login";
    }
    String heldNoteText() { return heldNote.isVisible() ? heldNote.getText() : null; }

    public void showLogin(String error) {
        SwingUtilities.invokeLater(() -> {
            loginError.setForeground(ColorScheme.PROGRESS_ERROR_COLOR); // showResetDone() may have left this green
            loginError.setText(error != null ? error : " ");
            passField.setText("");
            rootCards.show(this, "login");
        });
    }

    public void showMain(String username, String tier) {
        accountName = username == null ? "" : username;
        SwingUtilities.invokeLater(() -> {
            currentTier = tier;
            buildAccountBar(accountName, tier);
            rootCards.show(this, "main");
        });
    }

    private void buildAccountBar(String username, String tier) {
        accountBar.removeAll();
        accountBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        accountBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        accountBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        // Name only (tier moves to a pill). CENTER bounds it to the gap before the right cluster, so a
        // long name ellipsizes instead of overlapping — full name on hover.
        JLabel who = new JLabel(username);
        who.setFont(FontManager.getRunescapeSmallFont());
        who.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        who.setHorizontalAlignment(SwingConstants.LEFT);
        who.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        who.setToolTipText(username + " (" + tier + ")");
        // Icon buttons reclaim the width "Log out" text ate, so the tier pill fits inline.
        JButton gear = iconButton("⚙", "Settings");
        gear.addActionListener(e -> onOpenSettings.run());
        JButton out = iconButton("⏻", "Log out");
        out.addActionListener(e -> onLogout.run());
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        east.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        east.add(new Pill(tier));
        east.add(gear);
        east.add(out);
        accountBar.add(who, BorderLayout.CENTER);
        accountBar.add(east, BorderLayout.EAST);
        accountBar.revalidate();
        accountBar.repaint();
    }

    /**
     * Build + show the Settings card. Called by the plugin after it gathers the current prefs +
     * fetches alerts status + server version (so the card seeds without the panel holding config).
     */
    public void showSettings(int bankroll, int slots, String pace, String access,
                             String risk, int minVolume, int minMargin, double minRoi, int fillTarget,
                             boolean webhookSet, boolean alertsEnabled, boolean recover,
                             boolean position, boolean crash, int alertCooldownMin,
                             boolean nudgesEnabled, int nudgeCooldownMin, String serverVersion,
                             long goalGp, long deadline,
                             boolean quietEnabled, int quietStart, int quietEnd, boolean webhookFailed) {
        SwingUtilities.invokeLater(() -> {
            boolean premium = "premium".equals(currentTier); // gates the web-dashboard link only now
            settingsCard.removeAll();

            JPanel top = new JPanel(new BorderLayout());
            top.setBackground(ColorScheme.DARK_GRAY_COLOR);
            top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            top.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton back = new JButton("Back");
            back.setFont(FontManager.getRunescapeSmallFont());
            back.addActionListener(e -> rootCards.show(this, "main"));
            JLabel h = new JLabel("Settings");
            h.setFont(FontManager.getRunescapeBoldFont());
            h.setForeground(BLUE);
            top.add(back, BorderLayout.WEST);
            top.add(h, BorderLayout.EAST);
            settingsCard.add(top);
            settingsCard.add(gap());

            JLabel inviteHint = new JLabel("<html>A friend can open your link to<br>preview Coffer and create an account.</html>");
            inviteHint.setFont(FontManager.getRunescapeSmallFont());
            inviteHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            inviteHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton copyInvite = new JButton("Copy invite link");
            copyInvite.setFont(FontManager.getRunescapeSmallFont());
            copyInvite.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel inviteStatus = new JLabel(" ");
            inviteStatus.setFont(FontManager.getRunescapeSmallFont());
            inviteStatus.setForeground(ColorScheme.BRAND_ORANGE);
            inviteStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
            copyInvite.addActionListener(e -> {
                String url = InviteLink.forUsername(accountName);
                if (url == null) { inviteStatus.setText("Couldn't build a link."); return; }
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
                inviteStatus.setText("Invite link copied.");
                Timer revert = new Timer(2500, ev -> inviteStatus.setText(" "));
                revert.setRepeats(false);
                revert.start();
            });
            settingsCard.add(inviteHint);
            settingsCard.add(gap());
            settingsCard.add(copyInvite);
            settingsCard.add(inviteStatus);
            settingsCard.add(gap());

            // ---- Preferences (open by default) ----
            JPanel prefs = sectionBody();
            prefBankroll.setText(String.valueOf(bankroll));
            prefSlots.setText(String.valueOf(slots));
            prefPace.setSelectedItem(pace);
            prefAccess.setSelectedItem(access);
            prefs.add(labeledField("Bankroll (gp)", prefBankroll));
            prefs.add(labeledField("GE slots", prefSlots));
            prefs.add(labeledField("Pace", prefPace));
            prefs.add(labeledField("Access", prefAccess));
            // Risk tolerance — a quick-fill preset over the four raw filters below.
            prefMinVol.setText(String.valueOf(minVolume));
            prefMinMargin.setText(String.valueOf(minMargin));
            prefMinRoi.setText(String.valueOf(minRoi));
            prefFillTarget.setText(String.valueOf(fillTarget));
            prefRisk.setSelectedItem(RISK_PRESETS.containsKey(risk) ? risk : "custom");
            prefs.add(labeledField("Risk tolerance", prefRisk));
            // Advanced (raw filters). Collapsed behind a toggle so the default view stays simple.
            JPanel advanced = new JPanel();
            advanced.setLayout(new javax.swing.BoxLayout(advanced, javax.swing.BoxLayout.Y_AXIS));
            advanced.setBackground(ColorScheme.DARK_GRAY_COLOR);
            advanced.setAlignmentX(Component.LEFT_ALIGNMENT);
            advanced.add(labeledField("Min volume/hr", prefMinVol));
            advanced.add(labeledField("Min margin (gp)", prefMinMargin));
            advanced.add(labeledField("Min ROI (%)", prefMinRoi));
            advanced.add(labeledField("Fill target (%)", prefFillTarget));
            advanced.setVisible(false);
            JButton advToggle = new JButton("Advanced +");
            advToggle.setFont(FontManager.getRunescapeSmallFont());
            advToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
            advToggle.addActionListener(e -> {
                advanced.setVisible(!advanced.isVisible());
                advToggle.setText(advanced.isVisible() ? "Advanced -" : "Advanced +");
                settingsCard.revalidate();
            });
            prefRisk.addActionListener(e -> {
                double[] pre = RISK_PRESETS.get((String) prefRisk.getSelectedItem());
                if (pre != null) {
                    prefMinVol.setText(String.valueOf((int) pre[0]));
                    prefMinMargin.setText(String.valueOf((int) pre[1]));
                    prefMinRoi.setText(String.valueOf(pre[2]));
                    prefFillTarget.setText(String.valueOf((int) pre[3]));
                }
            });
            java.awt.event.KeyAdapter toCustom = new java.awt.event.KeyAdapter() {
                public void keyReleased(java.awt.event.KeyEvent ev) { prefRisk.setSelectedItem("custom"); }
            };
            for (JTextField f : new JTextField[]{prefMinVol, prefMinMargin, prefMinRoi, prefFillTarget}) f.addKeyListener(toCustom);
            prefs.add(advToggle);
            prefs.add(advanced);
            JButton savePrefs = new JButton("Save preferences");
            savePrefs.setFont(FontManager.getRunescapeSmallFont());
            savePrefs.setAlignmentX(Component.LEFT_ALIGNMENT);
            savePrefs.addActionListener(e -> {
                int b = parseInt(prefBankroll.getText(), bankroll);
                int s = parseInt(prefSlots.getText(), slots);
                double roi; try { roi = Double.parseDouble(prefMinRoi.getText().trim()); } catch (Exception ex) { roi = minRoi; }
                onSavePrefs.save(b, s, (String) prefPace.getSelectedItem(), (String) prefAccess.getSelectedItem(),
                    (String) prefRisk.getSelectedItem(),
                    parseInt(prefMinVol.getText(), minVolume), parseInt(prefMinMargin.getText(), minMargin),
                    roi, parseInt(prefFillTarget.getText(), fillTarget));
            });
            prefs.add(gap()); prefs.add(savePrefs);
            settingsCard.add(section("Preferences", true, prefs));

            // ---- Profit goal ----
            JPanel goalBody = sectionBody();
            prefGoal.setText(goalGp > 0 ? String.valueOf(goalGp) : "");
            prefGoalDate.setText(deadline > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(deadline)) : "");
            goalBody.add(labeledField("Goal (gp)", prefGoal));
            goalBody.add(labeledField("Target date (yyyy-mm-dd)", prefGoalDate));
            JButton saveGoal = new JButton("Save goal");
            saveGoal.setFont(FontManager.getRunescapeSmallFont());
            saveGoal.setAlignmentX(Component.LEFT_ALIGNMENT);
            saveGoal.addActionListener(e -> {
                long gp = parseLong(prefGoal.getText());
                long dl = parseDate(prefGoalDate.getText());
                onSaveGoal.save(gp, dl);
            });
            goalBody.add(gap()); goalBody.add(saveGoal);
            settingsCard.add(section("Profit goal", false, goalBody));

            // ---- In-client nudges (all tiers) ----
            JPanel nudges = sectionBody();
            styleCheck(nudgesCheck);
            nudgesCheck.setSelected(nudgesEnabled);
            nudgeCadence.setSelectedItem(nearestCadence(nudgeCooldownMin));
            nudgeCadence.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            nudges.add(nudgesCheck); nudges.add(gap());
            nudges.add(labeledField("Min between nudges", nudgeCadence));
            JButton saveNudges = new JButton("Save nudges");
            saveNudges.setFont(FontManager.getRunescapeSmallFont());
            saveNudges.setAlignmentX(Component.LEFT_ALIGNMENT);
            saveNudges.addActionListener(e ->
                onSaveNudges.save(nudgesCheck.isSelected(), (Integer) nudgeCadence.getSelectedItem()));
            nudges.add(gap()); nudges.add(saveNudges);
            settingsCard.add(section("In-client nudges", false, nudges));

            // ---- Send feedback (beta) ----
            JPanel fb = sectionBody();
            feedbackKind.setFont(FontManager.getRunescapeSmallFont());
            fb.add(labeledField("Type", feedbackKind));
            feedbackMsg.setLineWrap(true);
            feedbackMsg.setWrapStyleWord(true);
            feedbackMsg.setFont(FontManager.getRunescapeSmallFont());
            JScrollPane msgScroll = new JScrollPane(feedbackMsg);
            msgScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            msgScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
            fb.add(sectionLabel("MESSAGE", ColorScheme.LIGHT_GRAY_COLOR));
            fb.add(Box.createVerticalStrut(3));
            fb.add(msgScroll);
            fb.add(Box.createVerticalStrut(10));
            fb.add(labeledField("Contact (optional)", feedbackContact));
            JButton sendFb = new JButton("Send feedback");
            sendFb.setFont(FontManager.getRunescapeSmallFont());
            sendFb.setAlignmentX(Component.LEFT_ALIGNMENT);
            sendFb.addActionListener(e -> {
                String msg = feedbackMsg.getText().trim();
                if (msg.isEmpty()) { feedbackStatus.setText("Add a message first."); feedbackStatus.setForeground(BLUE); return; }
                feedbackStatus.setText("Sending…"); feedbackStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                onSendFeedback.send((String) feedbackKind.getSelectedItem(), msg, feedbackContact.getText().trim());
            });
            feedbackStatus.setFont(FontManager.getRunescapeSmallFont());
            feedbackStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            feedbackStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
            fb.add(sendFb);
            fb.add(gap());
            fb.add(feedbackStatus);
            settingsCard.add(section("Send feedback", false, fb));

            // ---- Off-client alerts (all tiers) ----
            JPanel alerts = sectionBody();
            alertsBrokenLbl.setFont(FontManager.getRunescapeSmallFont());
            alertsBrokenLbl.setForeground(ColorScheme.BRAND_ORANGE); // warn, not error red — auto-paused, easy fix below
            alertsBrokenLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            alertsBrokenLbl.setVisible(webhookFailed);
            alerts.add(alertsBrokenLbl); alerts.add(gap());
            alertsStatusLbl.setText(webhookSet ? "Alerts: configured (Discord)" : "Alerts: not set");
            alertsStatusLbl.setFont(FontManager.getRunescapeSmallFont());
            alertsStatusLbl.setForeground(webhookSet ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
            alertsStatusLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            alerts.add(alertsStatusLbl); alerts.add(gap());
            webhookField.setText("");
            webhookField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
            webhookField.setToolTipText("Discord webhook URL");
            webhookField.setAlignmentX(Component.LEFT_ALIGNMENT);
            styleCheck(alertsCheck); alertsCheck.setSelected(alertsEnabled);
            styleCheck(recoverCheck); recoverCheck.setSelected(recover);
            styleCheck(positionCheck); positionCheck.setSelected(position);
            styleCheck(crashCheck); crashCheck.setSelected(crash);
            alertCadence.setSelectedItem(nearestCadence(alertCooldownMin));
            alertCadence.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            saveAlerts.setFont(FontManager.getRunescapeSmallFont());
            saveAlerts.setAlignmentX(Component.LEFT_ALIGNMENT);
            webhookField.setEnabled(true);
            for (JComponent c : new JComponent[]{alertsCheck, recoverCheck, positionCheck,
                    crashCheck, alertCadence, saveAlerts}) c.setEnabled(true);
            for (var l : saveAlerts.getActionListeners()) saveAlerts.removeActionListener(l);
            saveAlerts.addActionListener(e -> {
                int qs = hhmmToMin(quietStartField.getText()); int qn = hhmmToMin(quietEndField.getText());
                onSaveAlerts.save(new String(webhookField.getPassword()), alertsCheck.isSelected(),
                    recoverCheck.isSelected(), positionCheck.isSelected(), crashCheck.isSelected(),
                    (Integer) alertCadence.getSelectedItem(),
                    quietCheck.isSelected(), qs < 0 ? 0 : qs, qn < 0 ? 0 : qn);
                webhookField.setText(""); // never keep the key in the field
                alertsStatusLbl.setText(alertsCheck.isSelected() ? "Alerts: configured (Discord)" : "Alerts: saved (disabled)");
                alertsStatusLbl.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
                alertsBrokenLbl.setVisible(false); // saving (re-adds a webhook) clears the broken marker server-side
            });
            alerts.add(webhookField); alerts.add(gap());
            alerts.add(alertsCheck); alerts.add(gap());
            alerts.add(recoverCheck); alerts.add(positionCheck); alerts.add(crashCheck);
            alerts.add(gap());
            alerts.add(labeledField("Min between alerts", alertCadence));
            alerts.add(gap()); alerts.add(saveAlerts);
            quietCheck.setSelected(quietEnabled);
            quietCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            quietCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
            quietCheck.setEnabled(true);
            quietStartField.setEnabled(true);
            quietEndField.setEnabled(true);
            quietStartField.setText(minToHhmm(quietStart));
            quietEndField.setText(minToHhmm(quietEnd));
            alerts.add(gap()); alerts.add(quietCheck);
            alerts.add(labeledField("Quiet start (HH:MM)", quietStartField));
            alerts.add(labeledField("Quiet end (HH:MM)", quietEndField));
            JButton testBtn = new JButton("Send test alert");
            testBtn.setFont(FontManager.getRunescapeSmallFont());
            testBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
            testBtn.setEnabled(true);
            testBtn.addActionListener(e -> onTestAlert.run());
            alerts.add(gap()); alerts.add(testBtn);
            settingsCard.add(section("Off-client alerts", false, alerts));

            // ---- Community ----
            JPanel community = sectionBody();
            JLabel communityBlurb = new JLabel("<html>Join the Coffer Discord for help, bug reports,<br>and flipping chat.</html>");
            communityBlurb.setFont(FontManager.getRunescapeSmallFont());
            communityBlurb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            communityBlurb.setAlignmentX(Component.LEFT_ALIGNMENT);
            community.add(communityBlurb);
            community.add(gap());
            community.add(wrapped(com.gecopilot.client.ServerConfig.DISCORD_URL, BLUE));
            settingsCard.add(section("Community", false, community));

            // ---- Web dashboard (Premium) ----
            if (premium) {
                JPanel web = sectionBody();
                web.add(wrapped(com.gecopilot.client.ServerConfig.BASE_URL + "/dashboard", BLUE));
                settingsCard.add(section("Web dashboard", false, web));
            }

            // ---- About (not collapsible) ----
            settingsCard.add(gap());
            settingsCard.add(sectionLabel("ABOUT", ColorScheme.LIGHT_GRAY_COLOR));
            settingsCard.add(wrapped("Coffer v" + com.gecopilot.client.ServerConfig.VERSION, ColorScheme.LIGHT_GRAY_COLOR));
            settingsCard.add(wrapped(serverVersion == null ? "Server: unreachable" : "Server: connected",
                serverVersion == null ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR));
            settingsCard.add(gap()); settingsCard.add(gap()); // trailing room so the last line isn't clipped at the panel bottom

            settingsCard.revalidate();
            settingsCard.repaint();
            rootCards.show(this, "settings");
        });
    }

    private void styleCheck(JCheckBox c) {
        c.setFont(FontManager.getRunescapeSmallFont());
        c.setBackground(ColorScheme.DARK_GRAY_COLOR);
        c.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private static Integer nearestCadence(int m) {
        int[] presets = {5, 15, 30, 60};
        int best = 30, bestD = Integer.MAX_VALUE;
        for (int p : presets) { int d = Math.abs(p - m); if (d < bestD) { bestD = d; best = p; } }
        return best;
    }

    private JComponent wrapped(String text, Color color) {
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setOpaque(false);
        ta.setBorder(null);
        ta.setFont(FontManager.getRunescapeSmallFont());
        ta.setForeground(color);
        ta.setAlignmentX(Component.LEFT_ALIGNMENT);
        ta.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        return ta;
    }

    private JLabel sectionLabel(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);            // keep BoxLayout alignment consistent
        l.setHorizontalAlignment(SwingConstants.CENTER);      // center the text within full width
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    private JPanel labeledField(String label, JComponent field) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        p.add(l);
        p.add(Box.createVerticalStrut(3));
        p.add(field);
        p.add(Box.createVerticalStrut(10)); // breathing room below each field
        return p;
    }

    /** A BoxLayout-Y panel the caller fills with one section's controls; wrapped by section(). */
    private JPanel sectionBody() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 8, 8));
        return p;
    }

    /** Collapsible section: a clickable header toggles the body's visibility (toggle "-"/"+"). */
    private JPanel section(String title, boolean openByDefault, JComponent body) {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 6, 0));
        JButton head = new JButton((openByDefault ? "-  " : "+  ") + title);
        head.setFont(FontManager.getRunescapeBoldFont());
        head.setForeground(BLUE);
        head.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        head.setFocusPainted(false);
        head.setHorizontalAlignment(SwingConstants.LEFT);
        head.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
        body.setVisible(openByDefault);
        head.addActionListener(e -> {
            boolean vis = !body.isVisible();
            body.setVisible(vis);
            head.setText((vis ? "-  " : "+  ") + title);
            settingsCard.revalidate();
            settingsCard.repaint();
        });
        wrap.add(head);
        wrap.add(body);
        return wrap;
    }

    private JButton iconButton(Icon icon, String tip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setMargin(new Insets(2, 5, 2, 5));
        b.setFocusPainted(false);
        return b;
    }

    // Gear/power use dingbat glyphs on the logical SansSerif font (renders cleanly on macOS/most Linux;
    // may tofu on some Windows setups without a symbol fallback font — revisit with bundled PNGs if that
    // surfaces). Paul preferred these to the drawn-icon versions.
    private JButton iconButton(String glyph, String tip) {
        JButton b = new JButton(glyph);
        b.setFont(new Font("SansSerif", Font.PLAIN, 14));
        b.setToolTipText(tip);
        b.setMargin(new Insets(2, 5, 2, 5));
        b.setFocusPainted(false);
        return b;
    }

    private static Icon starIcon(boolean filled, Color color) {
        return new Icon() {
            @Override public int getIconWidth() { return 14; }
            @Override public int getIconHeight() { return 14; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                Polygon p = starPolygon(x + 7, y + 7, 6, 3);
                if (filled) g2.fillPolygon(p); else g2.drawPolygon(p);
                g2.dispose();
            }
        };
    }

    private static Polygon starPolygon(int cx, int cy, int outerR, int innerR) {
        Polygon p = new Polygon();
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(i * 36 - 90);
            int r = (i % 2 == 0) ? outerR : innerR;
            p.addPoint(cx + (int) Math.round(Math.cos(a) * r), cy + (int) Math.round(Math.sin(a) * r));
        }
        return p;
    }

    private static Color tierColor(String tier) {
        switch (tier == null ? "" : tier.toLowerCase()) {
            case "premium": return new Color(0xF0B429); // gold
            case "standard": return new Color(0x5AA9E6); // blue
            default: return new Color(0x9AA0A8);          // grey (free)
        }
    }

    /** A small rounded-outline tier badge, matching the web dashboard's pill. */
    private static final class Pill extends JLabel {
        private final Color line;
        Pill(String tier) {
            super(tier == null ? "" : tier.toUpperCase());
            this.line = tierColor(tier);
            setForeground(line);
            setFont(FontManager.getRunescapeSmallFont());
            setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(line);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight() - 1, getHeight() - 1);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim().replaceAll("[^0-9]", "")); } catch (Exception e) { return fallback; }
    }

    private static int hhmmToMin(String s) {
        try { String[] p = s.trim().split(":"); int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1; return h * 60 + m; } catch (Exception e) { return -1; }
    }
    private static String minToHhmm(int min) { return String.format("%02d:%02d", (min / 60) % 24, min % 60); }

    private static long parseLong(String s) {
        try { String d = s.trim().replaceAll("[^0-9]", ""); return d.isEmpty() ? 0 : Long.parseLong(d); } catch (Exception e) { return 0; }
    }

    private static long parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd").parse(s.trim()).getTime(); } catch (Exception e) { return 0; }
    }

    private JPanel finderPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel controls = new JPanel(new BorderLayout(0, 4));
        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        finderMode.setFont(FontManager.getRunescapeSmallFont());
        finderMode.addActionListener(e -> {
            String m = (String) finderMode.getSelectedItem();
            finderCards.show(finderContent, m);
            searchField.setVisible("Search".equals(m));
        });
        searchField.setFont(FontManager.getRunescapeSmallFont());
        searchField.setToolTipText("Type an item name, press Enter");
        searchField.setVisible(false);
        searchField.addActionListener(e -> onSearch.accept(searchField.getText()));
        controls.add(finderMode, BorderLayout.NORTH);
        controls.add(searchField, BorderLayout.SOUTH);

        finderContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
        finderContent.add(wrap(flipsList), "Flips");
        finderContent.add(wrap(planList), "Plan");
        finderContent.add(wrap(alchList), "Alch");
        finderContent.add(wrap(crashList), "Crash");
        finderContent.add(wrap(searchList), "Search");

        p.add(controls, BorderLayout.NORTH);
        p.add(finderContent, BorderLayout.CENTER);
        return p;
    }

    private JPanel tabBar() {
        JPanel bar = new JPanel(new GridLayout(1, 4, 2, 0));
        bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        tabFinder.addActionListener(e -> selectTab("finder"));
        tabPos.addActionListener(e -> selectTab("positions"));
        tabWatch.addActionListener(e -> selectTab("watch"));
        tabLog.addActionListener(e -> { selectTab("log"); onLogSelected.run(); });
        bar.add(tabFinder); bar.add(tabPos); bar.add(tabWatch); bar.add(tabLog);
        return bar;
    }

    public void onLogTabSelected(Runnable r) { this.onLogSelected = r != null ? r : () -> {}; }
    public void onWatchToggle(IntConsumer c) { this.onWatchToggle = c != null ? c : id -> {}; }
    public void onSearch(Consumer<String> c) { this.onSearch = c != null ? c : q -> {}; }

    private void selectTab(String key) {
        cards.show(content, key);
        style(tabFinder, key.equals("finder"));
        style(tabPos, key.equals("positions"));
        style(tabWatch, key.equals("watch"));
        style(tabLog, key.equals("log"));
    }

    public void showLoginNeeded(String msg) { showLogin(msg); }

    public void update(SyncResult sync, Dashboard dash, SessionGuide.Stage stage) {
        SwingUtilities.invokeLater(() -> {
            sessionStage = stage == null ? SessionGuide.Stage.DONE : stage;
            planEmpty = SessionGuide.emptyPlan(sessionStage);
            posEmpty = SessionGuide.emptyPositions(sessionStage);
            if (dash != null) {
                lastDash = dash;
                renderPnl();
                populateAccounts(dash.accounts);
                fillRateValue.setText(dash.fillRate != null ? dash.fillRate : "-");
                GoalDto g = dash.goal;
                goalLine.setText(goalText(g));
                setFigureColor(goalLine, g != null && g.goalGp > 0 && g.onTrack != null
                    ? (g.onTrack ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE) : BLUE);
                updateMilestoneLine(dash);
            }
            watched.clear();
            for (WatchDto w : sync.watch) watched.add(w.id);
            fill(flipsList, sync.flips, this::flipCard, SessionGuide.emptyFlips(sessionStage));
            lastPlan = sync.plan;
            fill(planList, sync.plan, this::planCard, planEmpty);
            fill(alchList, sync.alch, this::alchCard, "No alch plays right now.");
            fill(crashList, sync.crash, this::crashCard, "No crashes right now.");
            lastPositions = sync.positions;
            fill(posList, sync.positions, this::positionCard, posEmpty);
            fill(watchList, sync.watch, this::watchCard, "Star items to watch them.");
        });
    }

    public void onSparklineToggle(IntConsumer c) { this.onSparklineToggle = c != null ? c : id -> {}; }

    /** Cache the fetched chart + re-render so the expanded block updates without waiting for the next poll. */
    public void showSparkline(int id, SparklineDto dto) {
        SwingUtilities.invokeLater(() -> {
            sparklineLoading.remove(id);
            sparklineError.remove(id);
            sparklineCache.put(id, dto);
            refreshSparklineLists();
        });
    }

    public void showSparklineError(int id, String message) {
        SwingUtilities.invokeLater(() -> {
            sparklineLoading.remove(id);
            sparklineError.put(id, message);
            refreshSparklineLists();
        });
    }

    /** Re-run fill() on the lists that can show a sparkline, from the last-known DTOs (no network wait). */
    private void refreshSparklineLists() {
        fill(planList, lastPlan, this::planCard, planEmpty);
        fill(posList, lastPositions, this::positionCard, posEmpty);
    }

    /** Paint the headline P&L + flip count from the cached dashboard, for the selected time window. */
    private void renderPnl() {
        Dashboard d = lastDash;
        if (d == null) return;
        Dashboard.Win w = null;
        if (d.windows != null) {
            if ("Session".equals(pnlWindowSel)) w = d.windows.session;
            else if ("24h".equals(pnlWindowSel)) w = d.windows.d1;
            else if ("7d".equals(pnlWindowSel)) w = d.windows.d7;
            else w = d.windows.all;
        }
        long gp = (w != null) ? w.gp : d.realized;      // fall back to lifetime if the server is old
        int flips = (w != null) ? w.flips : d.flips;
        pnl.setText((gp >= 0 ? "+" : "") + GP.format(gp) + " gp");
        setFigureColor(pnl, gp >= 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
        flipsValue.setText(String.valueOf(flips));
    }

    /** Compact tab-styled toggle for the period segmented control. */
    private JButton periodBtn(String text) {
        JButton b = new JButton(text);
        b.setFont(FontManager.getRunescapeSmallFont());
        b.setFocusPainted(false);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setBorder(BorderFactory.createEmptyBorder(4, 3, 4, 3));
        return b;
    }

    /** Select a P&L time window from the segmented control and repaint. */
    private void selectPeriod(String sel) {
        pnlWindowSel = sel;
        for (int i = 0; i < periodBtns.length; i++) style(periodBtns[i], PERIODS[i].equals(sel));
        renderPnl();
    }

    /** Sync the account combo to the server's account list; only visible with 2+ accounts. */
    private void populateAccounts(List<String> accts) {
        int n = (accts == null) ? 0 : accts.size();
        accountBox.setVisible(n >= 2);
        if (n < 2) return;
        List<String> want = new ArrayList<>();
        want.add("All accounts");
        want.addAll(accts);
        if (want.size() == accountBox.getItemCount()) {
            boolean same = true;
            for (int i = 0; i < want.size(); i++) if (!want.get(i).equals(accountBox.getItemAt(i))) { same = false; break; }
            if (same) return; // unchanged — don't disturb the current selection
        }
        String sel = (String) accountBox.getSelectedItem();
        populatingAccounts = true;
        accountBox.removeAllItems();
        for (String a : want) accountBox.addItem(a);
        if (sel != null && want.contains(sel)) accountBox.setSelectedItem(sel);
        populatingAccounts = false;
    }

    public void updateLog(List<LogRowDto> log) {
        SwingUtilities.invokeLater(() -> fill(logList, log, this::logCard,
            "<html>No completed flips yet.<br>A flip logs after you <b>sell</b> something<br>"
            + "Coffer recorded you <b>buy</b>. Place a suggested<br>buy to start tracking.</html>"));
    }

    /** Offer to import trades found in the in-game GE History that Coffer never recorded. */
    public void showHistoryImport(int n, Runnable onImport) {
        showHistoryImport(n + " GE History trade" + (n == 1 ? "" : "s") + " not recorded", onImport);
    }

    /** Same banner with caller-supplied copy (missed trades, or recorded prices History disagrees with). */
    public void showHistoryImport(String message, Runnable onImport) {
        SwingUtilities.invokeLater(() -> {
            historyBanner.removeAll();
            JLabel l = new JLabel(message);
            l.setFont(FontManager.getRunescapeSmallFont());
            l.setForeground(ColorScheme.BRAND_ORANGE);
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
            JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 6, 2));
            row.setBackground(historyBanner.getBackground());
            row.setAlignmentX(Component.CENTER_ALIGNMENT);
            JButton imp = new JButton("Import"); imp.setFont(FontManager.getRunescapeSmallFont());
            JButton dis = new JButton("Dismiss"); dis.setFont(FontManager.getRunescapeSmallFont());
            imp.addActionListener(e -> { imp.setEnabled(false); imp.setText("Importing…"); onImport.run(); });
            dis.addActionListener(e -> hideHistoryImport());
            row.add(imp); row.add(dis);
            historyBanner.add(l); historyBanner.add(row);
            historyBanner.setVisible(true);
            historyBanner.revalidate(); historyBanner.repaint();
        });
    }
    public void hideHistoryImport() {
        SwingUtilities.invokeLater(() -> { historyBanner.setVisible(false); historyBanner.removeAll();
            historyBanner.revalidate(); historyBanner.repaint(); });
    }

    /** "Since you last opened" session recap — once per JVM session, before the "welcome back" history hint. */
    public void showRecap(long minutesAway, int closedFlips, long realizedGp, int filledWhileAway) {
        SwingUtilities.invokeLater(() -> {
            recapBanner.removeAll();
            String title = minutesAway >= 1440 ? "Back after " + (minutesAway / 1440) + "d" : "Since you last opened";
            JLabel titleLbl = new JLabel(title);
            titleLbl.setFont(FontManager.getRunescapeBoldFont());
            titleLbl.setForeground(BLUE);
            titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            recapBanner.add(titleLbl);
            JLabel line1 = new JLabel((realizedGp >= 0 ? "+" : "") + fmtK(realizedGp) + " gp · " + closedFlips + " flips closed");
            line1.setFont(FontManager.getRunescapeSmallFont());
            line1.setForeground(realizedGp >= 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
            line1.setAlignmentX(Component.LEFT_ALIGNMENT);
            recapBanner.add(line1);
            JLabel line2 = new JLabel(filledWhileAway + " offer" + (filledWhileAway == 1 ? "" : "s") + " filled while away");
            line2.setFont(FontManager.getRunescapeSmallFont());
            line2.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            line2.setAlignmentX(Component.LEFT_ALIGNMENT);
            recapBanner.add(line2);
            JButton dis = new JButton("Dismiss");
            dis.setFont(FontManager.getRunescapeSmallFont());
            dis.setAlignmentX(Component.LEFT_ALIGNMENT);
            dis.addActionListener(e -> hideRecap());
            recapBanner.add(dis);
            recapBanner.setVisible(true);
            recapBanner.revalidate(); recapBanner.repaint();
        });
    }
    public void hideRecap() {
        SwingUtilities.invokeLater(() -> { recapBanner.setVisible(false); recapBanner.removeAll();
            recapBanner.revalidate(); recapBanner.repaint(); });
    }

    /** One-time banner after a version update. {@code notes} is an HTML fragment (use &lt;br&gt;). */
    public void showWhatsNew(String version, String notes, Runnable onDismiss) {
        SwingUtilities.invokeLater(() -> {
            whatsNewBanner.removeAll();
            JLabel title = new JLabel("What's new in v" + version);
            title.setFont(FontManager.getRunescapeBoldFont());
            title.setForeground(BLUE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            whatsNewBanner.add(title);
            JLabel body = new JLabel("<html>" + notes + "</html>");
            body.setFont(FontManager.getRunescapeSmallFont());
            body.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            whatsNewBanner.add(body);
            JButton ok = new JButton("Dismiss");
            ok.setFont(FontManager.getRunescapeSmallFont());
            ok.setAlignmentX(Component.LEFT_ALIGNMENT);
            ok.addActionListener(e -> { whatsNewBanner.setVisible(false); onDismiss.run(); });
            whatsNewBanner.add(ok);
            whatsNewBanner.setVisible(true);
            whatsNewBanner.revalidate(); whatsNewBanner.repaint();
        });
    }

    public void showSessionToast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            sessionToast.setText(msg);
            sessionToast.setVisible(true);
            if (sessionToastTimer != null) sessionToastTimer.stop();
            sessionToastTimer = new Timer(6500, e -> sessionToast.setVisible(false));
            sessionToastTimer.setRepeats(false);
            sessionToastTimer.start();
        });
    }

    /** Persistent next-step banner. Ignored after "Hide for now" until {@link #resetSessionGuide}. */
    public void showSessionGuide(String text) {
        SwingUtilities.invokeLater(() -> {
            if (sessionGuideDismissed) return;
            if (text == null || text.isEmpty()) { hideSessionGuide(); return; }
            if (text.equals(sessionGuideText) && onboardingBanner.isVisible()) return;
            sessionGuideText = text;
            onboardingBanner.removeAll();
            JLabel title = new JLabel("Next step");
            title.setFont(FontManager.getRunescapeBoldFont());
            title.setForeground(BLUE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            onboardingBanner.add(title);
            JLabel steps = new JLabel("<html>" + htmlEsc(text) + "</html>");
            steps.setFont(FontManager.getRunescapeSmallFont());
            steps.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            steps.setAlignmentX(Component.LEFT_ALIGNMENT);
            onboardingBanner.add(steps);
            JButton ok = new JButton("Hide for now");
            ok.setFont(FontManager.getRunescapeSmallFont());
            ok.setAlignmentX(Component.LEFT_ALIGNMENT);
            ok.addActionListener(e -> {
                sessionGuideDismissed = true;
                hideSessionGuide();
            });
            onboardingBanner.add(ok);
            onboardingBanner.setVisible(true);
            onboardingBanner.revalidate(); onboardingBanner.repaint();
        });
    }

    public void hideSessionGuide() {
        SwingUtilities.invokeLater(() -> {
            sessionGuideText = null;
            onboardingBanner.setVisible(false);
            onboardingBanner.removeAll();
            onboardingBanner.revalidate(); onboardingBanner.repaint();
        });
    }

    public void resetSessionGuide() {
        SwingUtilities.invokeLater(() -> {
            sessionGuideDismissed = false;
            sessionGuideText = null;
            sessionStage = SessionGuide.Stage.DONE;
            planEmpty = SessionGuide.emptyPlan(sessionStage);
            posEmpty = SessionGuide.emptyPositions(sessionStage);
            if (sessionToastTimer != null) sessionToastTimer.stop();
            sessionToast.setVisible(false);
            onboardingBanner.setVisible(false);
            onboardingBanner.removeAll();
        });
    }

    private static String htmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Gentle login reminder to open the in-game GE History so any offline fills can be imported. */
    public void showHistoryHint() {
        SwingUtilities.invokeLater(() -> {
            historyBanner.removeAll();
            for (String s : new String[]{"Back after a break?", "Open GE History to", "import missed trades"}) {
                JLabel l = new JLabel(s);
                l.setFont(FontManager.getRunescapeSmallFont());
                l.setForeground(ColorScheme.BRAND_ORANGE);
                l.setAlignmentX(Component.CENTER_ALIGNMENT);
                historyBanner.add(l);
            }
            JButton dis = new JButton("Dismiss"); dis.setFont(FontManager.getRunescapeSmallFont());
            dis.setAlignmentX(Component.CENTER_ALIGNMENT);
            dis.addActionListener(e -> hideHistoryImport());
            historyBanner.add(dis);
            historyBanner.setVisible(true);
            historyBanner.revalidate(); historyBanner.repaint();
        });
    }

    public void showSearchResults(List<CloudFlip> results) {
        SwingUtilities.invokeLater(() -> fill(searchList, results, this::flipCard, "No matches."));
    }

    private <T> void fill(JPanel list, List<T> items, Function<T, JPanel> card, String emptyMsg) {
        list.removeAll();
        int shown = 0;
        for (T it : items) {
            if (shown++ >= 30) break;
            list.add(card.apply(it));
            list.add(gap());
        }
        if (items.isEmpty()) {
            JLabel l = new JLabel(emptyMsg);
            l.setFont(FontManager.getRunescapeSmallFont());
            l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            list.add(l);
        }
        list.revalidate(); list.repaint();
    }

    // ---- cards ----

    private JPanel flipCard(CloudFlip f) {
        JPanel card = baseCard(marginStripe(f.marginPct));
        card.add(titleRow(f.id, f.name, star(f.id)));
        card.add(gap());
        card.add(row("Buy", GP.format(f.buyPrice), BLUE));
        card.add(row("Sell", GP.format(f.sellPrice), Color.WHITE));
        card.add(row("Margin", "+" + GP.format(f.margin) + " gp  (" + String.format("%.1f%%", f.marginPct) + ")", marginStripe(f.marginPct)));
        if (f.risk != null) card.add(riskLine(f.risk, f.riskReason));
        card.add(gap());
        card.add(white(centered("Buy " + GP.format(f.canBuy) + " @ " + GP.format(f.placeBuy) + " -> " + GP.format(f.placeSell))));
        card.add(gray(centered("+" + fmtK(f.cycleProfit) + " · fill " + Math.round(f.fillProb * 100) + "%")));
        if (f.confidence != null) {
            JLabel conf = centered("conf " + f.confidence + "%");
            conf.setForeground(f.confidence >= 70 ? ColorScheme.PROGRESS_COMPLETE_COLOR
                : f.confidence >= 40 ? ColorScheme.BRAND_ORANGE
                : ColorScheme.LIGHT_GRAY_COLOR);
            card.add(conf);
        }
        if (f.crowdFillPct != null) {
            JLabel crowd = centered("crowd " + f.crowdFillPct + "%"
                + (f.crowdSamples != null ? " (n=" + f.crowdSamples + ")" : ""));
            crowd.setForeground(f.crowdFillPct >= 70 ? ColorScheme.PROGRESS_COMPLETE_COLOR
                : f.crowdFillPct >= 40 ? ColorScheme.BRAND_ORANGE
                : ColorScheme.PROGRESS_ERROR_COLOR);
            card.add(crowd);
        }
        if (f.crowdCount != null && f.crowdCount >= 1) {
            JLabel herd = centered(f.crowdCount + (f.crowdCount == 1 ? " other buying" : " others buying")
                + (f.crowdCount >= HERD_WARN ? " — margin may compress" : ""));
            herd.setForeground(f.crowdCount >= HERD_WARN ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
            card.add(herd);
        }
        JLabel lim = limitLine(f.id, f.limit, f.limitLeft);
        if (lim != null) card.add(lim);
        return card;
    }

    private JPanel planCard(PlanRowDto r) {
        JPanel card = baseCard(BLUE);
        card.add(titleRow(r.id, r.name, sparklineToggle(r.id)));
        card.add(gap());
        card.add(white(centered("Buy " + GP.format(r.qty) + " @ " + GP.format(r.buyPrice) + " -> " + GP.format(r.sellPrice))));
        JLabel exp = centered("+" + fmtK(r.expProfit) + " gp · ~" + fmtTime(r.fillHrs));
        exp.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        card.add(exp);
        if (sparklineOpen.contains(r.id)) card.add(sparklineBlock(r.id));
        JLabel lim = limitLine(r.id, r.limit, r.limitLeft);
        if (lim != null) card.add(lim);
        return card;
    }

    private JPanel alchCard(AlchDto a) {
        JPanel card = baseCard(ColorScheme.PROGRESS_COMPLETE_COLOR);
        card.add(titleRow(a.id, a.name, null));
        card.add(gap());
        card.add(row("Buy", GP.format(a.buy), BLUE));
        card.add(row("High alch", GP.format(a.highalch), Color.WHITE));
        card.add(row("Alch profit", "+" + GP.format(a.profit) + " (" + String.format("%.1f%%", a.roi) + ")", ColorScheme.PROGRESS_COMPLETE_COLOR));
        card.add(row("Vol/hr", GP.format(a.volume), ColorScheme.LIGHT_GRAY_COLOR));
        return card;
    }

    private JPanel crashCard(CrashDto cr) {
        JPanel card = baseCard(ColorScheme.PROGRESS_ERROR_COLOR);
        card.add(titleRow(cr.id, cr.name, null));
        card.add(gap());
        card.add(row("Now", GP.format(cr.current) + " gp", Color.WHITE));
        card.add(row("24h avg", GP.format(cr.dayAvg) + " gp", Color.WHITE));
        card.add(row("Crash", String.format("%.1f%%", cr.pctChange), ColorScheme.PROGRESS_ERROR_COLOR));
        card.add(row("Recovery", "+" + GP.format((long) cr.dayAvg - cr.current) + " gp", ColorScheme.PROGRESS_COMPLETE_COLOR));
        card.add(row("Vol/hr", GP.format(cr.volume), ColorScheme.LIGHT_GRAY_COLOR));
        return card;
    }

    JPanel positionCard(PositionDto p) {
        Color side = p.buy ? BLUE : ColorScheme.BRAND_ORANGE;
        JPanel card = baseCard(side);
        JLabel tag = new JLabel(p.buy ? "BUYING" : "SELLING");
        tag.setFont(FontManager.getRunescapeSmallFont());
        tag.setForeground(side);
        card.add(titleBlock(p.id, p.name, tag, sparklineToggle(p.id), star(p.id)));
        card.add(gap());
        card.add(row("Progress", p.sold + " / " + p.total, Color.WHITE));
        card.add(row("Price", GP.format(p.price) + " gp", Color.WHITE));
        // Buy: "Market margin" at today's fillable sell. Sell: "Profit" against your cost, sent only
        // when your cost covers every unit. Otherwise a loss the known units already lock in, or
        // "unknown": never a gain on just the tracked part.
        if (p.edge != null) {
            card.add(row(p.buy ? "Market margin" : "Profit", signedGp(p.edge), edgeColor(p.edge)));
        } else if (!p.buy && p.knownLoss != null && p.knownQty != null) {
            // Red only when the server warns (past the partial floor); a small known loss stays amber.
            card.add(row("Loss (" + p.knownQty + " known)", signedGp(p.knownLoss), p.warn ? WARN : PARTIAL));
        } else if (!p.buy) {
            card.add(row("Profit", "unknown", NEUTRAL));
        }
        if (p.alchProfit != null)
            card.add(row("Alch profit", "+" + GP.format(p.alchProfit) + " gp", ColorScheme.PROGRESS_COMPLETE_COLOR));
        card.add(row("Open", p.openMin + "m", p.openMin >= 15 ? PARTIAL : ColorScheme.LIGHT_GRAY_COLOR));
        if (p.target != null) {
            String lbl = (p.targetLabel != null && !p.targetLabel.isEmpty()) ? p.targetLabel : (p.buy ? "Raise buy to" : "Drop sell to");
            card.add(row(lbl, GP.format(p.target) + " gp", PARTIAL));
        }
        if (sparklineOpen.contains(p.id)) card.add(sparklineBlock(p.id));
        if (p.avgDownQty != null && p.avgDownPrice != null && p.avgDownBreakeven != null) {
            // Own multi-line label so the narrow panel can't truncate it (the single-row label did).
            JLabel ad = new JLabel("<html><b>Average down</b><br>buy " + p.avgDownQty + " @ " + GP.format(p.avgDownPrice)
                + "<br>-&gt; break-even " + GP.format(p.avgDownBreakeven) + "</html>");
            ad.setFont(FontManager.getRunescapeSmallFont());
            ad.setForeground(BLUE);
            ad.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(gap());
            card.add(ad);
        }
        card.add(gap());
        JPanel verdictRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        verdictRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        verdictRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        String v = p.verdict == null ? "HOLD" : p.verdict;
        Color badge = verdictColor(v);
        JLabel verdictLabel = new JLabel(v + (p.confidence != null && !p.confidence.equals("LOW") ? " · " + p.confidence : ""));
        verdictLabel.setFont(FontManager.getRunescapeSmallFont());
        verdictLabel.setForeground(badge);
        verdictRow.add(verdictLabel);
        if (p.advice != null && !p.advice.isEmpty()) {
            JLabel adv = centered(p.advice);
            adv.setForeground(adviceColor(p));
            verdictRow.add(adv);
        }
        card.add(verdictRow);
        return card;
    }

    JPanel watchCard(WatchDto w) {
        Color stripe = w.sellableNow ? ColorScheme.PROGRESS_COMPLETE_COLOR
            : (w.margin > 0 ? BLUE : ColorScheme.LIGHT_GRAY_COLOR);
        JPanel card = baseCard(stripe);
        card.add(titleRow(w.id, w.name, star(w.id)));
        card.add(gap());
        card.add(row("Buy", GP.format(w.buy), BLUE));
        card.add(row("Sell", GP.format(w.sell), Color.WHITE));
        // Market margin is what the market pays, not your profit: neutral when your cost is partial.
        Color mc = w.partialCost ? NEUTRAL_VALUE : edgeColor(w.margin);
        card.add(row("Market margin", signedGp(w.margin), mc));
        long known = knownCost(w);
        if (w.partialCost && known > 0) {
            card.add(row(costLabel(w), GP.format(known) + " gp", ColorScheme.LIGHT_GRAY_COLOR));
            JLabel st = centered(costKnownLine(w));
            st.setForeground(PARTIAL);
            card.add(st);
            JLabel none = centered("No profit or recovery shown");
            none.setForeground(NEUTRAL);
            card.add(none);
        } else if (w.buyCost > 0) {
            card.add(row(costLabel(w), GP.format(w.buyCost) + " gp", ColorScheme.LIGHT_GRAY_COLOR));
            JLabel st = centered(w.sellableNow ? "Sellable now" : "Recovers at " + GP.format(w.breakeven));
            st.setForeground(w.sellableNow ? ColorScheme.PROGRESS_COMPLETE_COLOR : PARTIAL);
            card.add(st);
        }
        return card;
    }

    /** Average over the known units (servers before 0.1.32 sent it as buyCost). */
    static long knownCost(WatchDto w) {
        return w.knownCost > 0 ? w.knownCost : w.buyCost;
    }

    /** "Avg cost (42 known)": always says how many units the average covers ("Your cost" if the server didn't say). */
    static String costLabel(WatchDto w) {
        return w.trackedQty > 0 ? "Avg cost (" + w.trackedQty + " known)" : "Your cost";
    }

    /** True minus (U+2212) when the RuneLite panel fonts have the glyph, else a hyphen (no tofu). */
    static final String MINUS = minusGlyph();

    private static String minusGlyph() {
        try {
            return FontManager.getRunescapeSmallFont().canDisplay('\u2212')
                && FontManager.getRunescapeFont().canDisplay('\u2212') ? "\u2212" : "-";
        } catch (RuntimeException | Error e) {
            return "-";
        }
    }

    /** "+4,948 gp", "0 gp", "−890,318 gp". */
    static String signedGp(long n) {
        if (n == 0) return "0 gp";
        return (n > 0 ? "+" : MINUS) + GP.format(Math.abs(n)) + " gp";
    }

    /** Green gain, neutral zero, warn loss. */
    static Color edgeColor(long n) {
        return n > 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : n == 0 ? NEUTRAL : WARN;
    }

    /** "Cost known for 22 of 42". */
    static String costKnownLine(WatchDto w) {
        return "Cost known for " + w.trackedQty + " of " + w.heldQty;
    }

    /** Advice colour by meaning: a warning, a partly-known cost, or neutral. */
    static Color adviceColor(PositionDto p) {
        if (p.warn) return WARN;
        if (p.partialCost || "CHECK".equals(p.verdict)) return PARTIAL;
        return NEUTRAL;
    }

    static Color verdictColor(String v) {
        switch (v) {
            case "CUT": return WARN;
            case "REPRICE": return PARTIAL;
            case "CHECK": return NEUTRAL;
            default: return new Color(0x3C, 0x8C, 0x3C);
        }
    }

    private JPanel logCard(LogRowDto l) {
        Color stripe = l.unmatched ? ColorScheme.LIGHT_GRAY_COLOR
            : (l.profit >= 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
        JPanel card = baseCard(stripe);
        card.add(titleRow(l.id, l.name, null));
        card.add(gap());
        card.add(row("Qty", GP.format(l.qty), Color.WHITE));
        if (l.unmatched) {
            card.add(row("Buy avg", "unknown", ColorScheme.LIGHT_GRAY_COLOR));
            card.add(row("Sell avg", GP.format(l.sellAvg), Color.WHITE));
            card.add(row("Profit", "unknown cost", ColorScheme.LIGHT_GRAY_COLOR));
        } else {
            card.add(row("Buy avg", GP.format(l.buyAvg), BLUE));
            card.add(row("Sell avg", GP.format(l.sellAvg), Color.WHITE));
            card.add(row("Profit", (l.profit >= 0 ? "+" : "") + GP.format(l.profit) + " gp", stripe));
        }
        return card;
    }

    // ---- helpers ----

    private JPanel titleRow(int id, String name, JComponent east) {
        return east == null ? titleBlock(id, name, null) : titleBlock(id, name, null, east);
    }

    /** Width the item name may use beside the icon: 187px card interior minus the 24px icon and gaps. */
    static final int TITLE_W = 157;
    /** Where the item name starts in a card: after the 24px icon and the name's 4px gap. */
    static final int TITLE_X = 28;

    /**
     * Icon + the full item name wrapped onto up to 2 lines (tooltip always has the full name), and,
     * when there is a tag or controls, a row underneath: tag on the left, controls on the right.
     */
    private JPanel titleBlock(int id, String name, JLabel tag, JComponent... controls) {
        JPanel block = new JPanel();
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        block.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel icon = iconLabel(id);
        icon.setVerticalAlignment(SwingConstants.TOP);
        top.add(icon, BorderLayout.WEST);
        JLabel nm = new JLabel();
        nm.setFont(FontManager.getRunescapeBoldFont());
        nm.setForeground(Color.WHITE);
        nm.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        nm.setVerticalAlignment(SwingConstants.TOP);
        String[] lines = wrapTitle(name == null ? "" : name, nm.getFontMetrics(nm.getFont()), TITLE_W);
        nm.setText(lines.length == 1 ? lines[0]
            : "<html>" + escapeHtml(lines[0]) + "<br>" + escapeHtml(lines[1]) + "</html>");
        nm.setToolTipText(name);
        top.add(nm, BorderLayout.CENTER);
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, top.getPreferredSize().height));
        block.add(top);

        if (tag != null || (controls != null && controls.length > 0)) {
            JPanel under = new JPanel(new BorderLayout());
            under.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            under.setAlignmentX(Component.LEFT_ALIGNMENT);
            under.setBorder(BorderFactory.createEmptyBorder(2, TITLE_X, 0, 0));
            if (tag != null) under.add(tag, BorderLayout.WEST);
            if (controls != null && controls.length > 0) {
                JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                east.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                for (JComponent c : controls) if (c != null) east.add(c);
                under.add(east, BorderLayout.EAST);
            }
            under.setMaximumSize(new Dimension(Integer.MAX_VALUE, under.getPreferredSize().height));
            block.add(under);
        }
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height));
        return block;
    }

    /** Greedy word wrap to at most 2 lines of {@code width}px; the 2nd line ends in "..." if it overflows. */
    static String[] wrapTitle(String name, FontMetrics fm, int width) {
        if (fm.stringWidth(name) <= width) return new String[]{name};
        String[] words = name.split(" ");
        StringBuilder first = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String next = first.length() == 0 ? words[i] : first + " " + words[i];
            if (fm.stringWidth(next) > width && first.length() > 0) break;
            first.setLength(0); first.append(next);
            if (fm.stringWidth(next) > width) { i++; break; } // a single word wider than the line
        }
        StringBuilder rest = new StringBuilder();
        for (int j = i; j < words.length; j++) { if (rest.length() > 0) rest.append(' '); rest.append(words[j]); }
        String second = rest.toString();
        if (fm.stringWidth(second) > width) {
            String dots = "...";
            while (second.length() > 0 && fm.stringWidth(second + dots) > width) second = second.substring(0, second.length() - 1);
            second = second.trim() + dots;
        }
        String one = first.toString();
        if (fm.stringWidth(one) > width) { // one long word: cut it too
            while (one.length() > 1 && fm.stringWidth(one + "...") > width) one = one.substring(0, one.length() - 1);
            one = one + "...";
        }
        return second.isEmpty() ? new String[]{one} : new String[]{one, second};
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JLabel iconLabel(int id) {
        JLabel l = new JLabel();
        l.setPreferredSize(new Dimension(24, 24));
        AsyncBufferedImage img = itemManager == null ? null : itemManager.getImage(id);
        if (img != null) {
            img.onLoaded(() -> { l.setIcon(new ImageIcon(img)); l.revalidate(); l.repaint(); });
            l.setIcon(new ImageIcon(img));
        }
        return l;
    }

    private JLabel star(int id) {
        boolean on = watched.contains(id);
        JLabel s = new JLabel(starIcon(on, on ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR));
        s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        s.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                // Optimistic: flip the star + local set immediately so any list (incl. search)
                // shows feedback without waiting for the next sync poll.
                boolean nowOn = !watched.contains(id);
                if (nowOn) watched.add(id); else watched.remove(id);
                s.setIcon(starIcon(nowOn, nowOn ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR));
                onWatchToggle.accept(id);
            }
        });
        return s;
    }

    private boolean tierAtLeastStandard() { return "standard".equals(currentTier) || "premium".equals(currentTier); }

    /** Caret that expands/collapses the sparkline block; sibling to star(), same click idiom. */
    private JLabel sparklineToggle(int id) {
        boolean open = sparklineOpen.contains(id);
        boolean locked = !tierAtLeastStandard();
        JLabel s = new JLabel(open ? "-" : "+"); // open/closed toggle, same idiom as section()
        s.setFont(new Font("SansSerif", Font.PLAIN, 14));
        s.setForeground(open && !locked ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
        s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (locked) s.setToolTipText("Price charts are a Standard feature");
        s.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                boolean nowOpen = !sparklineOpen.contains(id);
                if (nowOpen) sparklineOpen.add(id); else sparklineOpen.remove(id);
                if (nowOpen && tierAtLeastStandard() && !sparklineCache.containsKey(id)) {
                    sparklineLoading.add(id);
                    onSparklineToggle.accept(id);
                }
                refreshSparklineLists();
            }
        });
        return s;
    }

    /** Fixed-height wrapper so loading/error/empty/locked states don't jump the card height
     *  relative to the loaded-chart state. */
    private JPanel fixedHeightMessage(JComponent... rows) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setPreferredSize(new Dimension(190, SPARKLINE_BLOCK_HEIGHT));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, SPARKLINE_BLOCK_HEIGHT));
        p.add(Box.createVerticalGlue());
        for (JComponent r : rows) { r.setAlignmentX(Component.CENTER_ALIGNMENT); p.add(r); }
        p.add(Box.createVerticalGlue());
        return p;
    }

    /** The expanded inline chart block for an item — rendered only while sparklineOpen contains id. */
    private JPanel sparklineBlock(int id) {
        JPanel block = new JPanel();
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        block.setAlignmentX(Component.LEFT_ALIGNMENT);

        JSeparator hairline = new JSeparator();
        hairline.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        hairline.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        hairline.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        hairline.setAlignmentX(Component.LEFT_ALIGNMENT);
        block.add(gap());
        block.add(hairline);
        block.add(gap());

        if (!tierAtLeastStandard()) {
            JLabel locked = centered("Price charts are a Standard feature");
            locked.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            JLabel upgrade = centered("Upgrade ->");
            upgrade.setForeground(BLUE);
            upgrade.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            upgrade.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    copyLinkToClipboard(com.gecopilot.client.ServerConfig.BASE_URL, upgrade, "Upgrade ->");
                }
            });
            block.add(fixedHeightMessage(locked, upgrade));
            return block;
        }

        if (sparklineLoading.contains(id)) {
            block.add(fixedHeightMessage(centered("Loading chart…")));
            return block;
        }
        if (sparklineError.containsKey(id)) {
            JLabel err = centered("Couldn't load chart — tap to retry");
            err.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
            err.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            err.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    sparklineError.remove(id);
                    sparklineLoading.add(id);
                    onSparklineToggle.accept(id);
                    refreshSparklineLists();
                }
            });
            block.add(fixedHeightMessage(err));
            return block;
        }
        SparklineDto dto = sparklineCache.get(id);
        List<SparklineDto.Point> pts = dto == null ? null : dto.points;
        if (pts == null || pts.isEmpty()) {
            block.add(fixedHeightMessage(centered("No price data for this item")));
            return block;
        }

        double first = pts.get(0).mid, last = pts.get(pts.size() - 1).mid;
        double pct = first != 0 ? (last - first) / first * 100.0 : 0;
        boolean up = last >= first;
        double lo = pts.get(0).mid, hi = pts.get(0).mid;
        for (SparklineDto.Point p : pts) { lo = Math.min(lo, p.mid); hi = Math.max(hi, p.mid); }

        JPanel capTop = new JPanel(new BorderLayout());
        capTop.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        capTop.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel cur = new JLabel(fmtK((long) last));
        cur.setFont(FontManager.getRunescapeSmallFont());
        cur.setForeground(Color.WHITE);
        JLabel chg = new JLabel((up ? "^ " : "v ") + String.format("%.1f%%", Math.abs(pct)) + " · 24h");
        chg.setFont(FontManager.getRunescapeSmallFont());
        chg.setForeground(up ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
        capTop.add(cur, BorderLayout.WEST);
        capTop.add(chg, BorderLayout.EAST);
        block.add(capTop);
        block.add(gap());

        SparklineChart chart = new SparklineChart(pts, up);
        chart.setPreferredSize(new Dimension(190, 64));
        chart.setMaximumSize(new Dimension(190, 64));
        chart.setAlignmentX(Component.LEFT_ALIGNMENT);
        block.add(chart);
        block.add(gap());

        JLabel capBottom = centered("lo " + fmtK((long) lo) + "  …  hi " + fmtK((long) hi));
        capBottom.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        block.add(capBottom);
        block.add(gap());

        JPanel expandRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        expandRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        expandRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel openFull = new JLabel("Open full chart ->");
        openFull.setFont(FontManager.getRunescapeSmallFont());
        openFull.setForeground(BLUE);
        openFull.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        openFull.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                copyLinkToClipboard(com.gecopilot.client.ServerConfig.BASE_URL + "/dashboard#item=" + id,
                    openFull, "Open full chart ->");
            }
        });
        expandRow.add(openFull);
        block.add(expandRow);
        return block;
    }

    /** ~15 lines of Graphics2D: an antialiased polyline of the mids, no charting dependency. */
    private static class SparklineChart extends JComponent {
        private final List<SparklineDto.Point> points;
        private final boolean up;
        SparklineChart(List<SparklineDto.Point> points, boolean up) {
            this.points = points; this.up = up;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (points == null || points.size() < 2) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            double min = points.get(0).mid, max = points.get(0).mid;
            for (SparklineDto.Point p : points) { min = Math.min(min, p.mid); max = Math.max(max, p.mid); }
            double range = Math.max(max - min, 1);
            int n = points.size();
            int[] xs = new int[n], ys = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = (int) Math.round(i * (w - 1) / (double) (n - 1));
                ys[i] = (int) Math.round((h - 1) - (points.get(i).mid - min) / range * (h - 1));
            }
            g2.setColor(up ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawPolyline(xs, ys, n);
            g2.fillOval(xs[n - 1] - 2, ys[n - 1] - 2, 4, 4);
            g2.dispose();
        }
    }

    private JPanel baseCard(Color stripe) {
        JPanel card = new JPanel() {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 2, 0, 0, stripe),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    private JPanel row(String label, String value, Color valueColor) {
        JPanel r = new JPanel(new BorderLayout());
        r.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        JLabel l = new JLabel(label);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        JLabel v = new JLabel(value);
        v.setFont(FontManager.getRunescapeSmallFont());
        v.setForeground(valueColor);
        v.setHorizontalAlignment(SwingConstants.RIGHT);
        r.add(l, BorderLayout.WEST);
        r.add(v, BorderLayout.CENTER);
        return r;
    }

    /** The server's limit and limit left, plus the 4h window this client saw (reset time, local buys). */
    private JLabel limitLine(int id, int limit, int limitLeft) {
        long now = System.currentTimeMillis();
        int serverUsed = limit > 0 ? Math.max(0, limit - limitLeft) : 0;
        return limitLine(buyLimits.status(id, limit, serverUsed, now), now, java.time.ZoneId.systemDefault());
    }

    static final Color LIMIT_TEXT = new Color(0xf2, 0xef, 0xe6);
    static final Color LIMIT_LOW = new Color(0xff, 0xb0, 0x20);
    static final Color LIMIT_REACHED = new Color(0xff, 0x7b, 0x72);
    static final Color RESET_UNKNOWN = new Color(0x8a, 0x8a, 0x8a);
    private static final java.time.format.DateTimeFormatter CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    /**
     * "1,200 of 5,000 left" over a dimmer "resets 15:42 (in 2h 13m)"; null while nothing is used.
     * Left-aligned with the item name. The clock time is fixed; the "in ..." part is as fresh as the
     * last panel refresh.
     */
    static JLabel limitLine(BuyLimits.Status s, long nowMs, java.time.ZoneId zone) {
        String text = limitText(s);
        if (text == null) return null;
        Color main = limitColor(s);
        String reset = resetText(s, nowMs, zone);
        if (reset == null) {
            JLabel l = titleAligned(text);
            l.setForeground(main);
            return l;
        }
        // Two lines: one line with the reset is wider than the sidebar card.
        Color dim = s.resetAtMs > 0 ? ColorScheme.LIGHT_GRAY_COLOR : RESET_UNKNOWN;
        JLabel l = titleAligned("<html><font color='" + hex(main) + "'>" + text + "</font><br><font color='"
            + hex(dim) + "'>" + reset + "</font></html>");
        l.setForeground(main);
        return l;
    }

    /** A small-font line that starts where the card's item name does. */
    private static JLabel titleAligned(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setBorder(BorderFactory.createEmptyBorder(0, TITLE_X, 0, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    static String limitText(BuyLimits.Status s) {
        if (s == null || s.used <= 0) return null;
        if (!s.known()) return "Bought " + GP.format(s.used) + " (limit unknown)";
        if (s.reached()) return "Limit reached";
        return GP.format(s.remaining()) + " of " + GP.format(s.limit) + " left";
    }

    /** "resets 15:42 (in 2h 13m)" from this client's window; "reset time unknown" for a server-only count. */
    static String resetText(BuyLimits.Status s, long nowMs, java.time.ZoneId zone) {
        if (s == null || s.used <= 0) return null;
        if (s.resetAtMs <= 0) return s.known() ? "reset time unknown" : null;
        String at = CLOCK.format(java.time.Instant.ofEpochMilli(s.resetAtMs).atZone(zone));
        return "resets " + at + " (in " + ShortDuration.format(Math.max(0, s.resetAtMs - nowMs)) + ")";
    }

    static Color limitColor(BuyLimits.Status s) {
        if (s == null || !s.known()) return ColorScheme.LIGHT_GRAY_COLOR;
        if (s.reached()) return LIMIT_REACHED;
        return GeOfferOverlay.lowLimit(s) ? LIMIT_LOW : LIMIT_TEXT;
    }

    private static JLabel centered(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    private String goalText(GoalDto g) {
        if (g == null || g.goalGp <= 0) return "Set a profit goal in Settings";
        String base = "Goal: " + g.progressPct + "%";
        if (g.realized >= g.goalGp) return base + " · reached!";
        if (g.onTrack != null) return base + (g.onTrack ? " · on track" : " · behind");
        if (g.etaDays != null) return base + " · ~" + g.etaDays + " days";
        return base + " · add flips to estimate";
    }

    private JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FontManager.getRunescapeSmallFont());
        l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        return l;
    }

    private void statRow(JPanel header, int row, String label, JLabel value) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = row; gc.anchor = GridBagConstraints.WEST; gc.weightx = 1;
        gc.insets = new Insets(4, 0, 0, 0);
        header.add(caption(label), gc);
        gc.gridx = 1; gc.anchor = GridBagConstraints.EAST; gc.weightx = 0;
        header.add(value, gc);
    }

    private JLabel white(JLabel l) { l.setForeground(Color.WHITE); return l; }
    private JLabel gray(JLabel l) { l.setForeground(ColorScheme.LIGHT_GRAY_COLOR); return l; }

    private JLabel riskLine(String risk, String reason) {
        Color c = "LOW".equals(risk) ? ColorScheme.PROGRESS_COMPLETE_COLOR
            : "MED".equals(risk) ? ColorScheme.BRAND_ORANGE
            : ColorScheme.PROGRESS_ERROR_COLOR;
        JLabel l = centered("Risk: " + risk);
        l.setForeground(c);
        if (reason != null) l.setToolTipText(reason);
        return l;
    }

    private Color marginStripe(double marginPct) {
        return marginPct >= 3 ? ColorScheme.PROGRESS_COMPLETE_COLOR
            : marginPct >= 1 ? ColorScheme.BRAND_ORANGE
            : ColorScheme.PROGRESS_ERROR_COLOR;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        return p;
    }

    private static JPanel wrap(JPanel list) {
        JPanel w = new JPanel(new BorderLayout());
        w.setBackground(ColorScheme.DARK_GRAY_COLOR);
        w.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        w.add(list, BorderLayout.NORTH);
        return w;
    }

    private JButton tab(String text) {
        JButton b = new JButton(text);
        b.setFont(FontManager.getRunescapeSmallFont());
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        return b;
    }

    private void style(JButton b, boolean on) {
        b.setBackground(on ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
        b.setForeground(on ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
    }

    private static Component gap() {
        Box.Filler f = (Box.Filler) Box.createRigidArea(new Dimension(0, 6));
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        return f;
    }

    // {kind ("flips"/"profit"), target, label}, in display/"next" order. Mirrors dashboard.html's
    // MILESTONES minus "First members flip" — Dashboard (the plugin's sync DTO) carries no members
    // flag, so that badge can't be evaluated here; it's web-only for now.
    private static final Object[][] MILESTONES = {
        {"flips", 1L, "First flip"}, {"flips", 10L, "10 flips"}, {"flips", 100L, "100 flips"},
        {"profit", 1_000_000L, "1M profit"}, {"profit", 10_000_000L, "10M profit"}, {"profit", 100_000_000L, "100M profit"},
    };

    private void updateMilestoneLine(Dashboard dash) {
        long flips = dash != null ? dash.flips : 0;
        long profit = dash != null ? dash.realized : 0;
        int earned = 0; String nextLabel = null; int nextPct = 0;
        for (Object[] m : MILESTONES) {
            long target = (Long) m[1];
            long cur = "flips".equals(m[0]) ? flips : profit;
            if (cur >= target) earned++;
            else if (nextLabel == null) {
                nextLabel = ((String) m[2]).toUpperCase();
                nextPct = (int) Math.max(0, Math.min(100, Math.round(100.0 * cur / target)));
            }
        }
        String html = "<html>MILESTONES · " + earned + "/" + MILESTONES.length;
        if (nextLabel != null) {
            html += " · NEXT: <span style='color:" + hex(ColorScheme.BRAND_ORANGE) + "'>" + nextLabel + " (" + nextPct + "%)</span>";
        }
        milestoneLine.setText(html + "</html>");
    }

    private static String hex(Color c) { return String.format("#%06x", c.getRGB() & 0xFFFFFF); }

    private static String fmtK(long n) {
        long a = Math.abs(n);
        if (a >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (a >= 1_000) return String.format("%.0fk", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String fmtTime(double hrs) {
        if (hrs < 0) return "?";
        if (hrs < 1) return Math.round(hrs * 60) + "m";
        return String.format("%.1fh", hrs);
    }
}
