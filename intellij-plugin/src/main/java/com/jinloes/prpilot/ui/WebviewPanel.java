package com.jinloes.prpilot.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static com.jinloes.prpilot.ui.WebviewPrSupport.bridgePrKey;
import static com.jinloes.prpilot.ui.WebviewPrSupport.isSamePr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.Alarm;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.services.DraftRecoveryStore;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.services.PendingReviewIndexNotifications;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.ui.DeepReviewController.DeepInvocation;
import com.jinloes.prpilot.ui.DeepReviewController.DeepPending;
import com.jinloes.prpilot.ui.DeepReviewController.DeepReviewIo;
import com.jinloes.prpilot.ui.DeepReviewController.FreshDeepPr;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.GeneratedReview;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrListMessage;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrListStatus;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrWorktree;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ProviderReadinessDto;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.SetupRequiredMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.WebviewPr;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.apache.commons.lang3.StringUtils;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JCEF browser panel that loads the React webview and wires the Java↔JS bridge.
 *
 * <p>Resources are served from an embedded localhost HTTP server so that Chromium treats every
 * request as same-origin — avoiding the null-origin CORS failures that occur when loading ES
 * modules from {@code file://} URLs.
 *
 * <p>Bridge protocol (matches webview/src/bridge/types.ts):
 *
 * <ul>
 *   <li>Java→JS: {@code window.__handleMessage(json)} — pushed after page ready
 *   <li>JS→Java: {@code window.cefQuery({request: json})} — injected via JBCefJSQuery
 * </ul>
 */
public class WebviewPanel implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(WebviewPanel.class);

    /** Maximum PRs shown in the list. The search over-fetches by one to detect truncation. */
    static final int PR_SEARCH_LIMIT = 50;

    static final int LAYOUT_REPAINT_DELAY_MS = 50;

    record LifecycleTransition(
            long selectionRevision,
            IntellijClaudeService reviewService,
            IntellijClaudeService chatService,
            ReviewProvider reviewProvider,
            ReviewProvider chatProvider,
            String reviewOperationId,
            String chatOperationId,
            PrWorktree worktree) {}

    enum PendingReviewLoadStatus {
        LOADED,
        NONE,
        FAILED
    }

    record PendingReviewLoad(
            PendingReviewLoadStatus status,
            IntellijGitHubService.PendingReview review,
            Exception failure) {
        static PendingReviewLoad loaded(IntellijGitHubService.PendingReview review) {
            return new PendingReviewLoad(PendingReviewLoadStatus.LOADED, review, null);
        }

        static PendingReviewLoad none() {
            return new PendingReviewLoad(PendingReviewLoadStatus.NONE, null, null);
        }

        static PendingReviewLoad failed(Exception failure) {
            return new PendingReviewLoad(PendingReviewLoadStatus.FAILED, null, failure);
        }
    }

    @FunctionalInterface
    interface PendingReviewLoader {
        IntellijGitHubService.PendingReview load() throws Exception;
    }

    // --- Infrastructure ---

    final WebviewResourceServer resourceServer = new WebviewResourceServer();
    volatile String webviewUrl;
    volatile boolean disposed;
    final JBCefBrowser browser;
    final JPanel browserPanel;
    final JBCefJSQuery bridgeQuery;
    final Alarm layoutRepaintAlarm;
    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final PendingReviewIndex pendingIndex = new PendingReviewIndex();
    final Runnable pendingIndexRecoveryAction = this::reload;
    PendingReviewIndexNotifications.Registration pendingIndexRecoveryRegistration = () -> {};
    final IntellijClaudeService claudeService;
    private final GitWorktreeService worktreeService = new GitWorktreeService();
    final com.jinloes.prpilot.review.SemanticReviewService semanticReviews =
            new com.jinloes.prpilot.review.SemanticReviewService();

    final IntellijGitHubService ghSvc;
    final DraftRecoveryStore draftRecoveryStore;
    final Project project;
    private final Consumer<Object> testMessageSink;

    /** Experimental opt-in; while off, the webview hides the IntelliJ-assisted controls. */
    final java.util.function.BooleanSupplier intellijAssistedEnabled;

    private final java.util.function.BinaryOperator<String> repositoryInstructionsLookup;

    static final String INTELLIJ_ASSISTED_DISABLED_ERROR =
            "IntelliJ-assisted review is disabled; enable it in PR Pilot settings (experimental)";

    /**
     * Points to the service that owns the currently running review process (may be a per-worktree
     * instance). Reset to {@code claudeService} after every review.
     */
    volatile IntellijClaudeService activeReviewService;

    volatile ReviewProvider activeReviewProvider = ReviewProvider.CLAUDE;

    volatile List<PullRequest> cachedPRs = List.of();
    volatile PullRequest activePR = null;
    volatile ReviewResult lastResult = null;

    final Map<String, GeneratedReview> generatedReviews = new ConcurrentHashMap<>();
    final AtomicLong generationSequence = new AtomicLong();
    volatile long activeGenerationId;
    volatile String activeReviewOperationId;

    final ReviewOutcomeLog outcomeLog = new ReviewOutcomeLog();
    volatile String pendingReviewId = null;
    volatile String pendingReviewKey = null;
    volatile long selectionRevision = 0;
    final Object draftMutationLock = new Object();
    volatile String prefetchedDiff = null;
    volatile String prefetchedValidationDiff = null;
    volatile String prefetchedExistingReviews = null;
    final WebviewWorktreeManager worktreeManager;
    final PrChatController chats;
    final DeepReviewController assistedReviews;

    volatile String prStateFilter = "open";
    volatile String searchScope = "currentRepo";

    Consumer<PullRequest> onPRSelected = pr -> {};
    Runnable onPageReady = () -> {};
    private final WebviewBridgeHandler bridgeHandler;
    private final WebviewPrSelectionController selectionController;
    private final WebviewReviewController reviewController;
    private final WebviewPrListController prListController;
    private final WebviewBrowserController browserController;
    private final WebviewThemeController themeController;
    private final WebviewPanelLifecycle lifecycleController;

    public WebviewPanel(Project project) {
        this.project = project;
        this.layoutRepaintAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        this.ghSvc = IntellijGitHubService.getInstance();
        this.draftRecoveryStore = DraftRecoveryStore.getInstance();
        this.testMessageSink = null;
        this.intellijAssistedEnabled =
                () -> PluginSettings.getInstance().isExperimentalIntellijAssistedReview();
        this.repositoryInstructionsLookup =
                (owner, repo) ->
                        PluginSettings.getInstance().getRepositoryReviewInstructions(owner, repo);
        this.bridgeHandler = new WebviewBridgeHandler(this, mapper);
        this.selectionController = new WebviewPrSelectionController(this);
        this.reviewController = new WebviewReviewController(this);
        this.prListController = new WebviewPrListController(this);
        this.browserController = new WebviewBrowserController(this);
        this.themeController = new WebviewThemeController(this);
        this.lifecycleController = new WebviewPanelLifecycle(this);
        DeepReviewIo io =
                new DeepReviewIo() {
                    public com.jinloes.prpilot.review.SemanticReviewService.Preparation prepare(
                            JsonNode options, FreshDeepPr fresh) throws Exception {
                        String base = project.getBasePath();
                        var root =
                                base == null
                                        ? null
                                        : worktreeService.findGitRoot(new java.io.File(base));
                        String detected = base == null ? null : ghSvc.detectCurrentRepo(base);
                        String owner = options.path("owner").asText(),
                                repo = options.path("repo").asText();
                        if (root == null || !(owner + "/" + repo).equalsIgnoreCase(detected))
                            throw new java.io.IOException(
                                    "Open the pull request repository before preparing a deep review.");
                        String digest =
                                java.util.HexFormat.of()
                                        .formatHex(
                                                java.security.MessageDigest.getInstance("SHA-256")
                                                        .digest(
                                                                fresh.diff()
                                                                        .getBytes(
                                                                                java.nio.charset
                                                                                        .StandardCharsets
                                                                                        .UTF_8)));
                        int number = options.path("number").asInt();
                        return semanticReviews.prepare(
                                root,
                                number,
                                fresh.head().ref(),
                                fresh.head().sha(),
                                fresh.head().isFork() ? fresh.head().forkCloneUrl() : "",
                                owner + "/" + repo + "#" + number,
                                digest,
                                options.path("operationId").asText());
                    }

                    public FreshDeepPr fresh(int number, String owner, String repo)
                            throws Exception {
                        return freshDeepPr(number, owner, repo);
                    }

                    public void generate(DeepPending pending, String operationId, String server) {
                        var options = pending.options();
                        handleGenerateReview(
                                options.path("number").asInt(),
                                options.path("owner").asText(),
                                options.path("repo").asText(),
                                pending.diff(),
                                options.path("chunkedReview").asBoolean(),
                                options.path("focusAreas").asText(""),
                                options.path("customInstructions").asText(""),
                                operationId,
                                new DeepInvocation(pending, server));
                    }

                    public List<com.jinloes.prpilot.review.SemanticWorktreeStore.Retained> list()
                            throws Exception {
                        return semanticReviews.list();
                    }

                    public void cleanup(String id, boolean closed) throws Exception {
                        semanticReviews.cleanup(id, closed);
                    }
                };
        this.claudeService = new IntellijClaudeService(project.getBasePath());
        this.activeReviewService = this.claudeService;
        this.chats = new PrChatController(this, new WebviewChatHost(this));
        this.assistedReviews =
                new DeepReviewController(
                        this,
                        new WebviewDeepHost(this),
                        io,
                        job -> getApplication().executeOnPooledThread(job),
                        this::readDeepSettingsIdentity);
        this.worktreeManager =
                new WebviewWorktreeManager(
                        project.getBasePath(), ghSvc, worktreeService, new WorktreeCoordinator<>());
        browser = JBCefBrowser.createBuilder().setOffScreenRendering(true).build();
        browserPanel = createBrowserHostPanel(browser.getComponent());
        bridgeQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);

        bridgeQuery.addHandler(
                request -> {
                    handleIncoming(request);
                    return new JBCefJSQuery.Response(null);
                });

        browser.getJBCefClient()
                .addLoadHandler(
                        new CefLoadHandlerAdapter() {
                            @Override
                            public void onLoadEnd(
                                    CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                                if (!frame.isMain() || disposed) {
                                    return;
                                }
                                String target = webviewUrl;
                                if (!isWebviewPage(frame.getURL(), target)) {
                                    // JCEF replays a load issued before its native browser
                                    // existed via invokeLater, so the "Starting webview"
                                    // placeholder can land after the real page and replace it.
                                    if (target != null) {
                                        getApplication()
                                                .invokeLater(
                                                        () -> {
                                                            if (!disposed) {
                                                                browser.loadURL(target);
                                                            }
                                                        });
                                    }
                                    return;
                                }
                                injectBridge(cefBrowser);
                                getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (disposed) {
                                                        return;
                                                    }
                                                    pushCurrentTheme();
                                                    onPageReady.run();
                                                });
                            }
                        },
                        browser.getCefBrowser());

        browser.loadHTML(
                "<html><body style='color:#888;background:#0a0805;"
                        + "font-family:monospace;padding:1em'>"
                        + "<p>Starting webview…</p>"
                        + "</body></html>");

        getApplication().executeOnPooledThread(this::startServerAndLoad);

        getApplication()
                .getMessageBus()
                .connect(this)
                .subscribe(LafManagerListener.TOPIC, source -> pushCurrentTheme());
    }

    /** Headless bridge fixture with the experimental IntelliJ-assisted setting enabled. */
    WebviewPanel(
            PullRequest selected,
            DeepReviewIo io,
            Consumer<Runnable> background,
            java.util.function.Supplier<Object> settings,
            Consumer<Object> messages) {
        this(selected, io, background, settings, messages, () -> true);
    }

    /** Headless bridge fixture: only external IDE/Git/provider effects are substituted. */
    WebviewPanel(
            PullRequest selected,
            DeepReviewIo io,
            Consumer<Runnable> background,
            java.util.function.Supplier<Object> settings,
            Consumer<Object> messages,
            java.util.function.BooleanSupplier intellijAssistedEnabled) {
        project = null;
        browser = null;
        browserPanel = null;
        bridgeQuery = null;
        layoutRepaintAlarm = null;
        ghSvc = null;
        draftRecoveryStore = null;
        claudeService = null;
        worktreeManager =
                new WebviewWorktreeManager(
                        null, null, worktreeService, new WorktreeCoordinator<>());
        chats = new PrChatController(this, new WebviewChatHost(this));
        assistedReviews =
                new DeepReviewController(this, new WebviewDeepHost(this), io, background, settings);
        activePR = selected;
        testMessageSink = messages;
        this.intellijAssistedEnabled = intellijAssistedEnabled;
        this.repositoryInstructionsLookup = (owner, repo) -> "";
        this.bridgeHandler = new WebviewBridgeHandler(this, mapper);
        this.selectionController = new WebviewPrSelectionController(this);
        this.reviewController = new WebviewReviewController(this);
        this.prListController = new WebviewPrListController(this);
        this.browserController = new WebviewBrowserController(this);
        this.themeController = new WebviewThemeController(this);
        this.lifecycleController = new WebviewPanelLifecycle(this);
    }

    private void startServerAndLoad() {
        browserController.startServerAndLoad();
    }

    /** Whether a finished main-frame load is the served webview rather than a placeholder. */
    static boolean isWebviewPage(String loadedUrl, String webviewUrl) {
        return WebviewPanelSupport.isWebviewPage(loadedUrl, webviewUrl);
    }

    static JPanel createBrowserHostPanel(JComponent browserComponent) {
        return WebviewPanelSupport.createBrowserHostPanel(browserComponent);
    }

    private void injectBridge(CefBrowser cefBrowser) {
        browserController.injectBridge(cefBrowser);
    }

    void scheduleWebviewLayoutRepaint() {
        browserController.scheduleLayoutRepaint();
    }

    void handleIncoming(String json) {
        bridgeHandler.handle(json);
    }

    static boolean isValidIncomingMessage(JsonNode node) {
        return WebviewPanelSupport.isValidIncomingMessage(node);
    }

    // --- selectPR ---

    static PendingReviewLoad loadPendingReview(PendingReviewLoader loader) {
        return WebviewPanelSupport.loadPendingReview(loader);
    }

    static Object pendingReviewFailureMessage(String prKey, PendingReviewLoad load) {
        return WebviewPanelSupport.pendingReviewFailureMessage(prKey, load);
    }

    void handleSelectPR(int number, String owner, String repo) {
        selectionController.handleSelectPR(number, owner, repo);
    }

    LifecycleTransition transitionToSelection(PullRequest pr) {
        return lifecycleController.transitionToSelection(pr);
    }

    void finishLifecycleTransition(LifecycleTransition transition) {
        lifecycleController.finishLifecycleTransition(transition);
    }

    void cancelActiveReview(String operationId) {
        lifecycleController.cancelActiveReview(operationId);
    }

    // --- generateReview ---

    private Object readDeepSettingsIdentity() {
        PluginSettings settings = PluginSettings.getInstance();
        return java.util.Arrays.asList(
                IntellijClaudeService.snapshotReviewRuntimeSettings().identity(),
                settings.getResolvedReviewFocusAreas(),
                settings.getResolvedReviewCustomInstructions(),
                List.copyOf(settings.getResolvedReviewGuidanceGlobs()),
                settings.getReviewRulesDirectory());
    }

    private FreshDeepPr freshDeepPr(int number, String owner, String repo) throws Exception {
        var before = ghSvc.getPRHeadInfo(owner, repo, number);
        String diff = ghSvc.getPRDiffFull(owner, repo, number);
        var after = ghSvc.getPRHeadInfo(owner, repo, number);
        if (before.sha() == null
                || !before.sha().equals(after.sha())
                || diff == null
                || diff.isBlank())
            throw new java.io.IOException("Cannot bind a fresh PR head and diff. Prepare again.");
        return new FreshDeepPr(after, diff);
    }

    void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            boolean chunkedReview,
            String focus,
            String custom,
            String operationId) {
        reviewController.handleGenerateReview(
                number, owner, repo, overrideDiff, chunkedReview, focus, custom, operationId);
    }

    void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            boolean chunkedReview,
            String overrideFocusAreas,
            String overrideCustomInstructions,
            String operationId,
            DeepInvocation deep) {
        reviewController.handleGenerateReview(
                number,
                owner,
                repo,
                overrideDiff,
                chunkedReview,
                overrideFocusAreas,
                overrideCustomInstructions,
                operationId,
                deep);
    }

    static boolean canPersistDraft(boolean activePr, boolean hasExplicitResult) {
        return WebviewReviewController.canPersistDraft(activePr, hasExplicitResult);
    }

    static ReviewOutcomeLog.Metadata generationMetadata(
            ReviewProvider provider, String reviewType, boolean includeDiff) {
        return WebviewReviewController.generationMetadata(provider, reviewType, includeDiff);
    }

    static boolean isProviderBinaryAvailable(ReviewProvider provider) {
        return WebviewPanelSupport.isProviderBinaryAvailable(provider);
    }

    static ProviderReadinessDto currentProviderReadiness() {
        return WebviewPanelSupport.currentProviderReadiness();
    }

    static ProviderReadinessDto providerReadiness(ProviderSetupProbe.Result setup) {
        return WebviewPanelSupport.providerReadiness(setup);
    }

    // --- saveDraft ---

    void handleSaveDraft(
            int number,
            String owner,
            String repo,
            long saveId,
            ReviewResult bridgeResult,
            ReviewResult bridgeGeneratedResult,
            List<LineComment> orphans) {
        reviewController.handleSaveDraft(
                number, owner, repo, saveId, bridgeResult, bridgeGeneratedResult, orphans);
    }

    void handleSubmitReview(int number, String owner, String repo, String verdict, String comment) {
        reviewController.handleSubmitReview(number, owner, repo, verdict, comment);
    }

    void handleDeleteDraft(int number, String owner, String repo) {
        reviewController.handleDeleteDraft(number, owner, repo);
    }

    String rememberedRepositoryInstructions(String owner, String repo) {
        return StringUtils.defaultIfEmpty(repositoryInstructionsLookup.apply(owner, repo), null);
    }

    boolean isCurrentSelection(String expectedKey, long expectedRevision) {
        synchronized (this) {
            return isCurrentSelectionLocked(expectedKey, expectedRevision);
        }
    }

    boolean isCurrentSelectionLocked(String expectedKey, long expectedRevision) {
        return WebviewPrSupport.isCurrentSelection(
                activePR, selectionRevision, expectedKey, expectedRevision);
    }

    void publishIfCurrentSelection(String expectedKey, long expectedRevision, Object message) {
        synchronized (this) {
            if (isCurrentSelectionLocked(expectedKey, expectedRevision)) {
                pushMessage(message);
            }
        }
    }

    boolean isCurrentGeneration(
            String expectedKey, long expectedRevision, long expectedGenerationId) {
        synchronized (this) {
            return isCurrentGenerationLocked(expectedKey, expectedRevision, expectedGenerationId);
        }
    }

    boolean isCurrentGenerationLocked(
            String expectedKey, long expectedRevision, long expectedGenerationId) {
        return activeGenerationId == expectedGenerationId
                && isCurrentSelectionLocked(expectedKey, expectedRevision);
    }

    void publishIfCurrentGeneration(
            String expectedKey, long expectedRevision, long expectedGenerationId, Object message) {
        synchronized (this) {
            if (isCurrentGenerationLocked(expectedKey, expectedRevision, expectedGenerationId)) {
                pushMessage(message);
            }
        }
    }

    boolean isActivePrKey(String key) {
        PullRequest pr = activePR;
        return pr != null
                && StringUtils.equals(
                        key, bridgePrKey(pr.getNumber(), pr.getOwner(), pr.getRepo()));
    }

    /** Formats a prior generated review as compact context for a re-generation prompt. */
    static String formatPriorReview(ReviewResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Verdict: ").append(result.getVerdict());
        if (StringUtils.isNotBlank(result.getSummary())) {
            sb.append("\nSummary: ").append(result.getSummary());
        }
        for (LineComment c : result.getLineComments()) {
            sb.append("\n- ")
                    .append(c.getFile())
                    .append(":")
                    .append(c.getLine())
                    .append(" [")
                    .append(c.getType())
                    .append("] ")
                    .append(c.getBody());
        }
        return sb.toString();
    }

    IntellijClaudeService resolvePrClaudeService(PullRequest pr) {
        synchronized (this) {
            if (pr == null || !isSamePr(activePR, pr)) {
                throw new IllegalStateException("The selected pull request changed.");
            }
        }
        return worktreeManager.resolve(pr);
    }

    static IntellijClaudeService serviceForWorktree(java.io.File directory) {
        return WebviewWorktreeManager.serviceForWorktree(directory);
    }

    String buildPrContext(PullRequest pr) {
        return WebviewPanelSupport.buildPrContext(this, pr);
    }

    // --- Helpers ---

    /**
     * Serializes {@code payload} to JSON and pushes it into the webview via {@code
     * __handleMessage}. The JSON is embedded directly as a JS expression (JSON is a valid JS
     * literal) instead of as a quoted string, avoiding script-injection risk from untrusted PR
     * content. U+2028/U+2029 are escaped because they are line terminators in JS but appear as
     * literal characters inside JSON strings.
     */
    void pushMessage(Object payload) {
        if (testMessageSink != null) {
            publishIfActive(this, () -> disposed, () -> testMessageSink.accept(payload));
            return;
        }
        try {
            ObjectNode versioned = mapper.valueToTree(payload);
            versioned.put("protocolVersion", BridgeMessageValidator.PROTOCOL_VERSION);
            String json = mapper.writeValueAsString(versioned);
            String safe = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            publishIfActive(
                    this,
                    () -> disposed,
                    () -> {
                        CefBrowser cefBrowser = browser.getCefBrowser();
                        cefBrowser.executeJavaScript(
                                "if(window.__handleMessage){window.__handleMessage(" + safe + ");}",
                                cefBrowser.getURL(),
                                0);
                    });
        } catch (JsonProcessingException e) {
            log.warn("pushMessage serialization failed: {}", e.getMessage());
        }
    }

    static void publishIfActive(
            Object lifecycleLock, BooleanSupplier disposed, Runnable browserCall) {
        WebviewPanelSupport.publishIfActive(lifecycleLock, disposed, browserCall);
    }

    private void pushCurrentTheme() {
        themeController.pushCurrentTheme();
    }

    public void loadPRs(
            List<PullRequest> prs,
            String defaultRepo,
            String searchScope,
            String currentRepo,
            boolean limited,
            boolean reviewStatusAvailable,
            ProviderSetupProbe.Result providerSetup) {
        prListController.loadPRs(
                prs,
                defaultRepo,
                searchScope,
                currentRepo,
                limited,
                reviewStatusAvailable,
                providerSetup);
    }

    PrListMessage prListMessage(
            List<WebviewPr> prs,
            String defaultRepo,
            PrListStatus listStatus,
            ProviderReadinessDto providerReadiness) {
        return prListController.prListMessage(prs, defaultRepo, listStatus, providerReadiness);
    }

    public void setOnPRSelected(Consumer<PullRequest> callback) {
        this.onPRSelected = callback;
    }

    public void setOnPageReady(Runnable callback) {
        this.onPageReady = callback;
    }

    /** Pushes a setup-required screen into the webview. Call from the EDT. */
    public void pushSetupRequired(String reason, String detail) {
        pushSetupRequired(reason, detail, currentProviderReadiness());
    }

    void pushSetupRequired(String reason, String detail, ProviderReadinessDto providerReadiness) {
        pushMessage(new SetupRequiredMsg("setupRequired", reason, detail, providerReadiness));
    }

    public void activatePr(PullRequest pr, String source) {
        prListController.activatePr(pr, source);
    }

    Optional<List<PendingReviewIndex.Entry>> loadHealthyDraftEntries() {
        return prListController.loadHealthyDraftEntries();
    }

    void reportPendingIndexMutation(String operation, PendingReviewIndex.MutationResult result) {
        prListController.reportPendingIndexMutation(operation, result);
    }

    public String getPrStateFilter() {
        return prStateFilter;
    }

    public String getSearchScope() {
        return searchScope;
    }

    public void reload() {
        if (!disposed) {
            browser.getCefBrowser().reloadIgnoreCache();
        }
    }

    public JComponent getComponent() {
        return browserPanel;
    }

    boolean isDisposedForHost() {
        return disposed;
    }

    PrChatController chatController() {
        return chats;
    }

    DeepReviewController deepController() {
        return assistedReviews;
    }

    @Override
    public void dispose() {
        lifecycleController.dispose();
    }
}
