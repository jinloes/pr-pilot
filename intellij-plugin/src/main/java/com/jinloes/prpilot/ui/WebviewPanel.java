package com.jinloes.prpilot.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.Alarm;
import com.intellij.util.ui.UIUtil;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.services.DraftRecoveryStore;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.services.PendingReviewIndexNotifications;
import com.jinloes.prpilot.services.UserFacingErrors;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.settings.PluginSettingsConfigurable;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
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

    private static final int LAYOUT_REPAINT_DELAY_MS = 50;

    // --- Outbound DTO records (Java → JS) ---
    // ReviewResultDto and LineCommentDto live in WebviewDtos.java (package-private);
    // see ReviewMapper for compile-time-verified model→DTO mapping.

    private record WebviewPr(
            int number,
            String title,
            String owner,
            String repo,
            String author,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("htmlUrl") String htmlUrl,
            @JsonProperty("isDraft") boolean isDraft,
            @JsonProperty("hasReviewDraft") boolean hasReviewDraft,
            @JsonProperty("reviewStatus") ReviewStatus reviewStatus) {}

    record PrListStatus(
            String searchScope,
            String currentRepo,
            int resultLimit,
            boolean limited,
            boolean reviewStatusAvailable) {}

    record PrListMessage(
            String type,
            List<WebviewPr> prs,
            @JsonProperty("defaultRepo") String defaultRepo,
            @JsonProperty("listStatus") PrListStatus listStatus,
            @JsonProperty("providerReadiness") ProviderReadinessDto providerReadiness,
            @JsonProperty("intellijAssistedEnabled") boolean intellijAssistedEnabled) {}

    private record DraftLoadingMsg(String type, @JsonProperty("prKey") String prKey) {}

    private record ThemeChangedMsg(String type, String theme) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DraftLoadedMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            String prState,
            @JsonProperty("reviewId") String reviewId,
            @JsonProperty("result") ReviewResultDto result,
            String diff,
            @JsonProperty("validationDiff") String validationDiff,
            boolean staleCommits,
            boolean importedFromGitHub,
            boolean recoveryPending,
            String status,
            @JsonProperty("providerReadiness") ProviderReadinessDto providerReadiness,
            @JsonProperty("intellijAssistedEnabled") boolean intellijAssistedEnabled) {}

    private record ReviewGeneratingMsg(
            String type, @JsonProperty("prKey") String prKey, String message) {}

    private record ReviewChunkMsg(
            String type, @JsonProperty("prKey") String prKey, String kind, String chunk) {}

    private record ReviewResultMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            ReviewResultDto result,
            String diff,
            @JsonProperty("validationDiff") String validationDiff) {}

    private record ErrorMsg(String type, @JsonProperty("prKey") String prKey, String message) {}

    private record DraftSavedMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            long saveId,
            String reviewId,
            boolean commentsDropped) {}

    private record DraftSaveErrorMsg(
            String type, @JsonProperty("prKey") String prKey, long saveId, String message) {}

    private record SimpleMsg(String type, @JsonProperty("prKey") String prKey) {}

    private record ChatChunkMsg(String type, @JsonProperty("prKey") String prKey, String chunk) {}

    private record ChatResponseMsg(
            String type, @JsonProperty("prKey") String prKey, String response) {}

    private record PrDraftStatusMsg(
            String type,
            int number,
            String owner,
            String repo,
            @JsonProperty("hasReviewDraft") boolean hasReviewDraft) {}

    record ProviderReadinessDto(
            String provider,
            boolean available,
            String detail,
            @JsonProperty("binaryStatus") String binaryStatus,
            @JsonProperty("authenticationStatus") String authenticationStatus,
            @JsonProperty("authCommand") String authCommand) {
        ProviderReadinessDto(String provider, boolean available, String detail) {
            this(
                    provider,
                    available,
                    detail,
                    available ? "ready" : "missing",
                    available ? "unverified" : "unavailable",
                    "copilot".equals(provider) ? "copilot login" : "claude auth login");
        }
    }

    private record ActivatePrMsg(String type, @JsonProperty("pr") WebviewPr pr, String source) {}

    private record SetupRequiredMsg(
            String type,
            String reason,
            String detail,
            @JsonProperty("providerReadiness") ProviderReadinessDto providerReadiness) {}

    private record ReviewGenerationSettings(
            IntellijClaudeService.ReviewRuntimeSettings runtime,
            String focusAreas,
            String customInstructions,
            List<String> guidanceGlobs) {}

    record GeneratedReview(
            long generationId, ReviewResult result, ReviewOutcomeLog.Metadata metadata) {}

    private record PrWorktree(java.io.File directory, java.io.File gitRoot) {}

    private record LifecycleTransition(
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

    record WorktreeLease<T>(long epoch, String key, CompletableFuture<T> future, boolean owner) {}

    static final class WorktreeCoordinator<T> {
        private long epoch;
        private String activeKey;
        private T activeValue;
        private String inFlightKey;
        private CompletableFuture<T> inFlight;

        synchronized WorktreeLease<T> acquire(String key) {
            if (activeValue != null && StringUtils.equals(activeKey, key)) {
                return new WorktreeLease<>(
                        epoch, key, CompletableFuture.completedFuture(activeValue), false);
            }
            if (inFlight != null && StringUtils.equals(inFlightKey, key)) {
                return new WorktreeLease<>(epoch, key, inFlight, false);
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            inFlightKey = key;
            inFlight = future;
            return new WorktreeLease<>(epoch, key, future, true);
        }

        boolean install(WorktreeLease<T> lease, T value) {
            boolean accepted;
            synchronized (this) {
                accepted =
                        lease.epoch() == epoch
                                && inFlight == lease.future()
                                && StringUtils.equals(inFlightKey, lease.key());
                if (accepted) {
                    activeKey = lease.key();
                    activeValue = value;
                    inFlightKey = null;
                    inFlight = null;
                }
            }
            if (accepted) {
                lease.future().complete(value);
            }
            return accepted;
        }

        void fail(WorktreeLease<T> lease) {
            synchronized (this) {
                if (inFlight == lease.future()) {
                    inFlightKey = null;
                    inFlight = null;
                }
            }
            lease.future()
                    .completeExceptionally(
                            new IllegalStateException(
                                    "Unable to create an isolated pull request worktree."));
        }

        synchronized T activeValue() {
            return activeValue;
        }

        T clear() {
            T previous;
            CompletableFuture<T> detached;
            synchronized (this) {
                epoch++;
                previous = activeValue;
                detached = inFlight;
                activeKey = null;
                activeValue = null;
                inFlightKey = null;
                inFlight = null;
            }
            if (detached != null) {
                detached.completeExceptionally(
                        new IllegalStateException("Pull request worktree creation was cancelled."));
            }
            return previous;
        }
    }

    // --- Infrastructure ---

    private volatile HttpServer httpServer;
    private volatile boolean disposed;
    private final JBCefBrowser browser;
    private final JPanel browserPanel;
    private final JBCefJSQuery bridgeQuery;
    private final Alarm layoutRepaintAlarm;
    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();
    private final Runnable pendingIndexRecoveryAction = this::reload;
    private PendingReviewIndexNotifications.Registration pendingIndexRecoveryRegistration =
            () -> {};
    private final IntellijClaudeService claudeService;
    private final GitWorktreeService worktreeService = new GitWorktreeService();
    private final com.jinloes.prpilot.review.SemanticReviewService semanticReviews =
            new com.jinloes.prpilot.review.SemanticReviewService();
    private DeepPending deepPending;
    private long deepPreparationRevision;
    private String deepOperationId;
    private final java.util.Set<String> consumedDeepOperations = new java.util.HashSet<>();

    record DeepPending(
            com.jinloes.prpilot.review.SemanticReviewService.Preparation preparation,
            JsonNode options,
            String key,
            long selection,
            Object settings,
            String diff,
            long revision) {}

    private record DeepInvocation(DeepPending pending, String server) {}

    private final IntellijGitHubService ghSvc;
    private final DraftRecoveryStore draftRecoveryStore;
    private final Project project;
    private final DeepReviewIo deepIo;
    private final Consumer<Runnable> deepBackground;
    private final java.util.function.Supplier<Object> deepSettings;
    private final Consumer<Object> testMessageSink;

    /** Experimental opt-in; while off, the webview hides the IntelliJ-assisted controls. */
    private final java.util.function.BooleanSupplier intellijAssistedEnabled;

    static final String INTELLIJ_ASSISTED_DISABLED_ERROR =
            "IntelliJ-assisted review is disabled; enable it in PR Pilot settings (experimental)";

    /**
     * External effects only; correlation, head checks and Continue consumption stay in the host.
     */
    interface DeepReviewIo {
        com.jinloes.prpilot.review.SemanticReviewService.Preparation prepare(
                JsonNode options, FreshDeepPr fresh) throws Exception;

        FreshDeepPr fresh(int number, String owner, String repo) throws Exception;

        void generate(DeepPending pending, String operationId, String server);

        List<com.jinloes.prpilot.review.SemanticWorktreeStore.Retained> list() throws Exception;

        void cleanup(String id, boolean closed) throws Exception;
    }

    /**
     * Points to the service that owns the currently running review process (may be a per-worktree
     * instance). Reset to {@code claudeService} after every review.
     */
    private volatile IntellijClaudeService activeReviewService;

    private volatile ReviewProvider activeReviewProvider = ReviewProvider.CLAUDE;

    private volatile List<PullRequest> cachedPRs = List.of();
    private volatile PullRequest activePR = null;
    private volatile ReviewResult lastResult = null;

    private final Map<String, GeneratedReview> generatedReviews = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private volatile long activeGenerationId;
    private volatile String activeReviewOperationId;
    private final AtomicLong chatSequence = new AtomicLong();
    private volatile long activeChatId;
    private volatile String activeChatOperationId;

    private final ReviewOutcomeLog outcomeLog = new ReviewOutcomeLog();
    private volatile String pendingReviewId = null;
    private volatile String pendingReviewKey = null;
    private volatile long selectionRevision = 0;
    private final Object draftMutationLock = new Object();
    private volatile String prefetchedDiff = null;
    private volatile String prefetchedValidationDiff = null;
    private volatile String prefetchedExistingReviews = null;
    private volatile List<ChatMessage> chatHistory = List.of();
    private volatile IntellijClaudeService activeChatService;
    private volatile ReviewProvider activeChatProvider = ReviewProvider.CLAUDE;
    private final WorktreeCoordinator<PrWorktree> worktrees;

    private volatile String prStateFilter = "open";
    private volatile String searchScope = "currentRepo";

    private Consumer<PullRequest> onPRSelected = pr -> {};
    private Runnable onPageReady = () -> {};

    public WebviewPanel(Project project) {
        this.project = project;
        this.layoutRepaintAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        this.ghSvc = IntellijGitHubService.getInstance();
        this.draftRecoveryStore = DraftRecoveryStore.getInstance();
        this.deepSettings = this::readDeepSettingsIdentity;
        this.deepBackground = job -> getApplication().executeOnPooledThread(job);
        this.testMessageSink = null;
        this.intellijAssistedEnabled =
                () -> PluginSettings.getInstance().isExperimentalIntellijAssistedReview();
        this.deepIo =
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
        this.activeChatService = this.claudeService;
        this.worktrees = new WorktreeCoordinator<>();
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
        worktrees = new WorktreeCoordinator<>();
        activePR = selected;
        deepIo = io;
        deepBackground = background;
        deepSettings = settings;
        testMessageSink = messages;
        this.intellijAssistedEnabled = intellijAssistedEnabled;
    }

    private void startServerAndLoad() {
        HttpServer server = tryStartServer();
        if (disposed) {
            if (server != null) {
                server.stop(0);
            }
            return;
        }
        httpServer = server;
        if (server == null) {
            getApplication()
                    .invokeLater(
                            () -> {
                                if (!disposed) {
                                    browser.loadHTML(
                                            "<html><body style='color:#e8a030;"
                                                    + "background:#0a0805;"
                                                    + "font-family:monospace'>"
                                                    + "<p>Could not start webview server</p>"
                                                    + "</body></html>");
                                }
                            });
            return;
        }
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        log.info("Loading webview from {}", url);
        getApplication()
                .invokeLater(
                        () -> {
                            if (!disposed) {
                                browser.loadURL(url);
                            }
                        });
    }

    static JPanel createBrowserHostPanel(JComponent browserComponent) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(browserComponent, BorderLayout.CENTER);
        return panel;
    }

    private HttpServer tryStartServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", this::serveResource);
            s.start();
            log.debug("Webview HTTP server listening on port {}", s.getAddress().getPort());
            return s;
        } catch (IOException e) {
            log.error("Failed to start webview HTTP server", e);
            return null;
        }
    }

    private void serveResource(HttpExchange exchange) throws IOException {
        String resource = resolveResourcePath(exchange.getRequestURI().getPath());
        if (resource == null) {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        try (InputStream in = WebviewPanel.class.getResourceAsStream(resource)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", mimeFor(resource));
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * Maps a request path to a classpath resource path under {@code /webview/}, or returns null if
     * the request would escape that root.
     */
    static String resolveResourcePath(String requestPath) {
        if (StringUtils.isBlank(requestPath) || !requestPath.startsWith("/")) {
            return null;
        }
        String path = "/".equals(requestPath) ? "/index.html" : requestPath;
        String candidate = "/webview" + path;
        String normalized = URI.create(candidate).normalize().getPath();
        if (!normalized.startsWith("/webview/")) {
            return null;
        }
        return normalized;
    }

    private static String mimeFor(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private void injectBridge(CefBrowser cefBrowser) {
        String js =
                "window.cefQuery = function(opts) { " + bridgeQuery.inject("opts.request") + " };";
        cefBrowser.executeJavaScript(js, cefBrowser.getURL(), 0);
    }

    private void scheduleWebviewLayoutRepaint() {
        getApplication()
                .invokeLater(
                        () -> {
                            if (disposed) {
                                return;
                            }
                            layoutRepaintAlarm.cancelAllRequests();
                            layoutRepaintAlarm.addRequest(
                                    () -> {
                                        if (disposed) {
                                            return;
                                        }
                                        CefBrowser cefBrowser = browser.getCefBrowser();
                                        if (cefBrowser != null) {
                                            cefBrowser.invalidate();
                                        }
                                        browser.getComponent().revalidate();
                                        browser.getComponent().repaint();
                                        browserPanel.revalidate();
                                        browserPanel.repaint();
                                    },
                                    LAYOUT_REPAINT_DELAY_MS);
                        });
    }

    void handleIncoming(String json) {
        try {
            var node = mapper.readTree(json);
            if (!isValidIncomingMessage(node)) {
                log.warn("Invalid bridge message payload: {}", json);
                return;
            }
            String type = node.path("type").asText();
            int number = node.path("number").asInt();
            String owner = node.path("owner").asText();
            String repo = node.path("repo").asText();

            switch (type) {
                case "selectPR" -> handleSelectPR(number, owner, repo);
                case "refreshPRs" -> {
                    String state = node.path("state").asText("open");
                    prStateFilter = StringUtils.defaultIfBlank(state, "open");
                    String scope = node.path("searchScope").asText("");
                    if (StringUtils.isNotBlank(scope)) {
                        searchScope = normalizeSearchScope(scope);
                    } else if (node.path("assignedToMe").asBoolean(false)) {
                        searchScope = "assigned";
                    } else if (node.path("reviewRequested").asBoolean(false)) {
                        searchScope = "reviewRequested";
                    }
                    getApplication().invokeLater(onPageReady);
                }
                case "openUrl" -> {
                    String url = node.path("url").asText();
                    if (StringUtils.isNotBlank(url) && url.startsWith("https://")) {
                        getApplication().invokeLater(() -> BrowserUtil.browse(url));
                    }
                }
                case "openSettings" ->
                        getApplication()
                                .invokeLater(
                                        () ->
                                                ShowSettingsUtil.getInstance()
                                                        .showSettingsDialog(
                                                                project,
                                                                PluginSettingsConfigurable.class));
                case "runAuthLogin" ->
                        getApplication()
                                .invokeLater(
                                        () ->
                                                BrowserUtil.browse(
                                                        "https://cli.github.com/manual/gh_auth_login"));
                case "webviewLayoutChanged" -> scheduleWebviewLayoutRepaint();
                case "generateReview" -> {
                    if (node.path("intellijAssisted").asBoolean(false)) {
                        handlePrepareDeepReview(node.deepCopy());
                    } else {
                        synchronized (this) {
                            deepPending = null;
                            deepPreparationRevision++;
                        }
                        handleGenerateReview(
                                number,
                                owner,
                                repo,
                                node.path("diff").asText(""),
                                node.path("chunkedReview").asBoolean(false),
                                node.path("focusAreas").asText(""),
                                node.path("customInstructions").asText(""),
                                node.path("operationId").asText(),
                                null);
                    }
                }
                case "continueDeepReview" -> handleContinueDeepReview(node.deepCopy());
                case "listDeepReviews", "cleanupDeepReview" ->
                        handleDeepMaintenance(node.deepCopy());
                case "cancelReview" -> cancelActiveReview(node.path("operationId").asText());
                case "saveDraft" -> {
                    long saveId = node.path("saveId").asLong();
                    ReviewResult bridgeResult = null;
                    ReviewResult bridgeGeneratedResult = null;
                    List<LineComment> bridgeOrphans = List.of();
                    try {
                        var resultNode = node.path("result");
                        if (!resultNode.isMissingNode()) {
                            bridgeResult = mapper.treeToValue(resultNode, ReviewResult.class);
                        }
                        var generatedResultNode = node.path("generatedResult");
                        if (!generatedResultNode.isMissingNode()) {
                            bridgeGeneratedResult =
                                    mapper.treeToValue(generatedResultNode, ReviewResult.class);
                        }
                        var orphansNode = node.path("orphans");
                        if (orphansNode.isArray()) {
                            List<LineComment> parsed = new ArrayList<>();
                            for (var el : orphansNode) {
                                parsed.add(mapper.treeToValue(el, LineComment.class));
                            }
                            bridgeOrphans = parsed;
                        }
                    } catch (Exception e) {
                        log.warn(
                                "saveDraft: failed to parse review data from bridge: {}",
                                e.getMessage());
                    }
                    final ReviewResult finalResult = bridgeResult;
                    final ReviewResult finalGeneratedResult = bridgeGeneratedResult;
                    final List<LineComment> finalOrphans = bridgeOrphans;
                    getApplication()
                            .executeOnPooledThread(
                                    () -> {
                                        synchronized (draftMutationLock) {
                                            handleSaveDraft(
                                                    number,
                                                    owner,
                                                    repo,
                                                    saveId,
                                                    finalResult,
                                                    finalGeneratedResult,
                                                    finalOrphans);
                                        }
                                    });
                }
                case "submitReview" -> {
                    String verdict = node.path("verdict").asText();
                    String comment = node.path("comment").asText("");
                    getApplication()
                            .executeOnPooledThread(
                                    () -> {
                                        synchronized (draftMutationLock) {
                                            handleSubmitReview(
                                                    number, owner, repo, verdict, comment);
                                        }
                                    });
                }
                case "deleteDraft" ->
                        getApplication()
                                .executeOnPooledThread(
                                        () -> {
                                            synchronized (draftMutationLock) {
                                                handleDeleteDraft(number, owner, repo);
                                            }
                                        });
                case "clearChat" -> clearChat(node.path("operationId").asText());
                case "cancelChat" -> cancelChat(node.path("operationId").asText());
                case "askClaude" -> {
                    String question = node.path("question").asText();
                    String context = node.path("context").asText("");
                    String operationId = node.path("operationId").asText();
                    getApplication()
                            .executeOnPooledThread(
                                    () -> handleAskClaude(question, context, operationId));
                }
                default -> log.warn("Unknown bridge message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Bridge message error: {}", e.getMessage());
        }
    }

    static boolean isValidIncomingMessage(JsonNode node) {
        return BridgeMessageValidator.isValid(node);
    }

    // --- selectPR ---

    static PendingReviewLoad loadPendingReview(PendingReviewLoader loader) {
        try {
            IntellijGitHubService.PendingReview review = loader.load();
            return review == null ? PendingReviewLoad.none() : PendingReviewLoad.loaded(review);
        } catch (Exception e) {
            return PendingReviewLoad.failed(e);
        }
    }

    static Object pendingReviewFailureMessage(String prKey, PendingReviewLoad load) {
        if (load.status() != PendingReviewLoadStatus.FAILED || load.failure() == null) {
            throw new IllegalArgumentException("A failed pending-review load is required");
        }
        return new ErrorMsg(
                "reviewError",
                prKey,
                UserFacingErrors.forGitHub(load.failure(), "load the pending review"));
    }

    private void handleSelectPR(int number, String owner, String repo) {
        String key = bridgePrKey(number, owner, repo);
        PullRequest pr =
                cachedPRs.stream()
                        .filter(
                                p ->
                                        p.getNumber() == number
                                                && p.getOwner().equals(owner)
                                                && p.getRepo().equals(repo))
                        .findFirst()
                        .orElse(null);
        if (pr == null) {
            pushMessage(
                    new DraftLoadedMsg(
                            "draftLoaded",
                            key,
                            "NO_DRAFT",
                            null,
                            null,
                            null,
                            null,
                            false,
                            false,
                            false,
                            "Pull request is no longer available. Refresh the pull request list and try again.",
                            currentProviderReadiness(),
                            intellijAssistedEnabled.getAsBoolean()));
            return;
        }

        LifecycleTransition transition;
        synchronized (this) {
            transition = transitionToSelection(pr);
        }
        finishLifecycleTransition(transition);
        long revision = transition.selectionRevision();

        getApplication()
                .invokeLater(
                        () -> {
                            if (isCurrentSelection(key, revision)) {
                                onPRSelected.accept(pr);
                            }
                        });
        publishIfCurrentSelection(key, revision, new DraftLoadingMsg("draftLoading", key));

        getApplication()
                .executeOnPooledThread(
                        () -> {
                            // Check local index upfront (no network) so we can prefetch
                            // the current HEAD SHA in parallel if a staleness check is
                            // likely to be needed.
                            Optional<List<PendingReviewIndex.Entry>> localEntries =
                                    loadHealthyDraftEntries();
                            if (localEntries.isEmpty()) {
                                publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                PendingReviewIndexNotifications.userMessage()));
                                return;
                            }
                            PendingReviewIndex.Entry localEntry =
                                    localEntries.orElseThrow().stream()
                                            .filter(
                                                    e ->
                                                            e.owner().equals(owner)
                                                                    && e.repo().equals(repo)
                                                                    && e.number() == number)
                                            .findFirst()
                                            .orElse(null);
                            String savedHeadSha = localEntry != null ? localEntry.headSha() : "";

                            // All calls are independent — run concurrently so total
                            // latency is max(each) instead of sum(each).
                            CompletableFuture<PrDetail> detailFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getPRDetail(owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDetail prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<PendingReviewLoad> pendingFuture =
                                    CompletableFuture.supplyAsync(
                                            () ->
                                                    loadPendingReview(
                                                            () ->
                                                                    ghSvc.loadDraftReview(
                                                                            owner, repo, number)));

                            CompletableFuture<String> diffFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getPRDiff(owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDiff prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> validationDiffFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getPRDiffFull(owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDiffFull prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> reviewsFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getExistingReviewsSummary(
                                                            owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getExistingReviewsSummary prefetch"
                                                                    + " failed: {}",
                                                            e.getMessage());
                                                    return "";
                                                }
                                            });

                            PrDetail detail = detailFuture.join();
                            PullRequest hydratedPr = hydratePullRequest(pr, detail);
                            boolean merged = detail != null && detail.merged();
                            PendingReviewLoad pendingLoad = pendingFuture.join();
                            IntellijGitHubService.PendingReview pending = pendingLoad.review();
                            String fetchedDiff = diffFuture.join();
                            String fetchedValidationDiff = validationDiffFuture.join();
                            String fetchedReviews = reviewsFuture.join();
                            String currentHeadSha =
                                    detail != null && detail.head() != null
                                            ? StringUtils.defaultString(detail.head().sha())
                                            : "";
                            String effectiveValidationDiff =
                                    StringUtils.isNotBlank(fetchedValidationDiff)
                                            ? fetchedValidationDiff
                                            : fetchedDiff;
                            ProviderReadinessDto providerReadiness = currentProviderReadiness();

                            synchronized (WebviewPanel.this) {
                                if (!isCurrentSelectionLocked(key, revision)) {
                                    return;
                                }
                                activePR = hydratedPr;
                                prefetchedDiff = fetchedDiff;
                                prefetchedValidationDiff = effectiveValidationDiff;
                                prefetchedExistingReviews = fetchedReviews;
                            }

                            if (pendingLoad.status() == PendingReviewLoadStatus.FAILED) {
                                log.warn(
                                        "loadDraftReview failed: {}",
                                        pendingLoad.failure().getMessage());
                                publishIfCurrentSelection(
                                        key,
                                        revision,
                                        pendingReviewFailureMessage(key, pendingLoad));
                                return;
                            }

                            // Delete stale draft on a merged PR, best-effort.
                            if (merged && pending != null && isCurrentSelection(key, revision)) {
                                try {
                                    ghSvc.deleteDraftReview(owner, repo, number, pending.id());
                                } catch (Exception e) {
                                    log.warn("deleteDraftReview failed: {}", e.getMessage());
                                }
                                pending = null;
                            }

                            if (merged) {
                                draftRecoveryStore.clear(key);
                                synchronized (WebviewPanel.this) {
                                    if (!isCurrentSelectionLocked(key, revision)) {
                                        return;
                                    }
                                    pendingReviewId = null;
                                    pendingReviewKey = null;
                                    lastResult = null;
                                }
                                publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new PrDraftStatusMsg(
                                                "prDraftStatusUpdated",
                                                number,
                                                owner,
                                                repo,
                                                false));
                                publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                key,
                                                "MERGED",
                                                null,
                                                null,
                                                fetchedDiff,
                                                effectiveValidationDiff,
                                                false,
                                                false,
                                                false,
                                                "PR is merged.",
                                                providerReadiness,
                                                intellijAssistedEnabled.getAsBoolean()));
                                return;
                            }

                            DraftRecoveryStore.Snapshot recovery = draftRecoveryStore.get(key);
                            if (pending != null || recovery != null) {
                                boolean stale =
                                        pending != null
                                                && StringUtils.isNotBlank(savedHeadSha)
                                                && !savedHeadSha.equals(currentHeadSha);
                                ReviewResult restored =
                                        recovery != null ? recovery.result() : pending.result();
                                String reviewId = pending != null ? pending.id() : null;
                                ReviewResultDto dto = ReviewMapper.INSTANCE.toDto(restored);
                                synchronized (WebviewPanel.this) {
                                    if (!isCurrentSelectionLocked(key, revision)) {
                                        return;
                                    }
                                    pendingReviewId = reviewId;
                                    pendingReviewKey =
                                            StringUtils.isNotBlank(reviewId) ? key : null;
                                    lastResult = restored;
                                }
                                publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new PrDraftStatusMsg(
                                                "prDraftStatusUpdated", number, owner, repo, true));
                                publishIfCurrentSelection(
                                        key,
                                        revision,
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                key,
                                                "DRAFT_PRESENT",
                                                reviewId,
                                                dto,
                                                fetchedDiff,
                                                effectiveValidationDiff,
                                                stale,
                                                pending != null && pending.importedFromGitHub(),
                                                recovery != null,
                                                recovery != null
                                                        ? "Recovered a local draft snapshot; save is pending."
                                                        : "Loaded pending draft review.",
                                                providerReadiness,
                                                intellijAssistedEnabled.getAsBoolean()));
                                return;
                            }

                            synchronized (WebviewPanel.this) {
                                if (!isCurrentSelectionLocked(key, revision)) {
                                    return;
                                }
                                pendingReviewId = null;
                                pendingReviewKey = null;
                                lastResult = null;
                            }
                            publishIfCurrentSelection(
                                    key,
                                    revision,
                                    new PrDraftStatusMsg(
                                            "prDraftStatusUpdated", number, owner, repo, false));
                            publishIfCurrentSelection(
                                    key,
                                    revision,
                                    new DraftLoadedMsg(
                                            "draftLoaded",
                                            key,
                                            "NO_DRAFT",
                                            null,
                                            null,
                                            fetchedDiff,
                                            effectiveValidationDiff,
                                            false,
                                            false,
                                            false,
                                            "",
                                            providerReadiness,
                                            intellijAssistedEnabled.getAsBoolean()));
                        });
    }

    private LifecycleTransition transitionToSelection(PullRequest pr) {
        IntellijClaudeService reviewService = activeReviewService;
        IntellijClaudeService chatService = activeChatService;
        ReviewProvider reviewProvider = activeReviewProvider;
        ReviewProvider chatProvider = activeChatProvider;
        String reviewOperationId = activeReviewOperationId;
        String chatOperationId = activeChatOperationId;
        activeReviewService = claudeService;
        activeChatService = claudeService;
        activeReviewProvider = ReviewProvider.CLAUDE;
        activeChatProvider = ReviewProvider.CLAUDE;
        activeReviewOperationId = null;
        activeChatOperationId = null;
        activeGenerationId = generationSequence.incrementAndGet();
        activeChatId = chatSequence.incrementAndGet();
        PrWorktree worktree = worktrees.clear();

        boolean samePullRequest =
                activePR != null
                        && activePR.getNumber() == pr.getNumber()
                        && StringUtils.equals(activePR.getOwner(), pr.getOwner())
                        && StringUtils.equals(activePR.getRepo(), pr.getRepo());
        activePR = pr;
        if (!samePullRequest) {
            lastResult = null;
            pendingReviewId = null;
            pendingReviewKey = null;
        }
        prefetchedDiff = null;
        prefetchedValidationDiff = null;
        prefetchedExistingReviews = null;
        chatHistory = List.of();
        return new LifecycleTransition(
                ++selectionRevision,
                reviewService,
                chatService,
                reviewProvider,
                chatProvider,
                reviewOperationId,
                chatOperationId,
                worktree);
    }

    private void finishLifecycleTransition(LifecycleTransition transition) {
        if (transition.reviewOperationId() != null) {
            transition.reviewService().cancelCurrentRequest(transition.reviewProvider());
        }
        if (transition.chatOperationId() != null) {
            transition.chatService().cancelCurrentRequest(transition.chatProvider());
        }
        removeWorktreeAsync(transition.worktree());
    }

    private void cancelActiveReview(String operationId) {
        IntellijClaudeService service;
        ReviewProvider provider;
        synchronized (this) {
            if (operationId != null && operationId.equals(deepOperationId)) {
                deepPending = null;
                deepOperationId = null;
                deepPreparationRevision++;
            }
            if (!StringUtils.equals(activeReviewOperationId, operationId)) return;
            activeGenerationId = generationSequence.incrementAndGet();
            service = activeReviewService;
            provider = activeReviewProvider;
            activeReviewService = claudeService;
            activeReviewProvider = ReviewProvider.CLAUDE;
            activeReviewOperationId = null;
        }
        if (service != null) service.cancelCurrentRequest(provider);
    }

    // --- generateReview ---

    private Object readDeepSettingsIdentity() {
        PluginSettings settings = PluginSettings.getInstance();
        return java.util.Arrays.asList(
                IntellijClaudeService.snapshotReviewRuntimeSettings().identity(),
                settings.getResolvedReviewFocusAreas(),
                settings.getResolvedReviewCustomInstructions(),
                List.copyOf(settings.getResolvedReviewGuidanceGlobs()));
    }

    record FreshDeepPr(IntellijGitHubService.PRHeadInfo head, String diff) {}

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

    private void publishDeepPrepared(DeepPending pending, String operationId, String message) {
        var p = pending.preparation();
        pushMessage(
                java.util.Map.of(
                        "type",
                        "deepReviewPrepared",
                        "prKey",
                        pending.key(),
                        "operationId",
                        operationId,
                        "retainedId",
                        p.retainedId(),
                        "worktree",
                        p.worktree(),
                        "head",
                        p.head(),
                        "servers",
                        p.servers(),
                        "message",
                        message));
    }

    private void handlePrepareDeepReview(JsonNode options) {
        int number = options.path("number").asInt();
        String owner = options.path("owner").asText(), repo = options.path("repo").asText();
        String key = bridgePrKey(number, owner, repo);
        if (!intellijAssistedEnabled.getAsBoolean()) {
            // Reject rather than downgrade: an ordinary review must be an explicit user choice.
            pushMessage(new ErrorMsg("reviewError", key, INTELLIJ_ASSISTED_DISABLED_ERROR));
            return;
        }
        final long revision, selection;
        final Object settings = deepSettings.get();
        synchronized (this) {
            if (disposed || !matchesPrRequest(activePR, number, owner, repo)) return;
            revision = ++deepPreparationRevision;
            selection = selectionRevision;
            deepPending = null;
            deepOperationId = options.path("operationId").asText();
        }
        cancelActiveReview(activeReviewOperationId);
        deepBackground.accept(
                () -> {
                    try {
                        var fresh = deepIo.fresh(number, owner, repo);
                        var prepared = deepIo.prepare(options, fresh);
                        synchronized (this) {
                            if (disposed
                                    || revision != deepPreparationRevision
                                    || !isCurrentSelectionLocked(key, selection)
                                    || !settings.equals(deepSettings.get())) return;
                            deepPending =
                                    new DeepPending(
                                            prepared,
                                            options,
                                            key,
                                            selection,
                                            settings,
                                            fresh.diff(),
                                            revision);
                            publishDeepPrepared(
                                    deepPending,
                                    options.path("operationId").asText(),
                                    "Open this exact worktree in IntelliJ, enable MCP and import Gradle. Continue may first arm tracking; then run one manual Gradle sync and Retry.");
                        }
                    } catch (Exception error) {
                        synchronized (this) {
                            if (!disposed
                                    && revision == deepPreparationRevision
                                    && isCurrentSelectionLocked(key, selection))
                                pushMessage(
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                String.valueOf(error.getMessage())));
                        }
                    }
                });
    }

    private void handleContinueDeepReview(JsonNode input) {
        final DeepPending pending;
        String operationId = input.path("operationId").asText(),
                server = input.path("server").asText();
        String key =
                bridgePrKey(
                        input.path("number").asInt(),
                        input.path("owner").asText(),
                        input.path("repo").asText());
        synchronized (this) {
            pending = deepPending;
            if (disposed
                    || pending == null
                    || consumedDeepOperations.contains(operationId)
                    || !pending.key().equals(key)
                    || !isCurrentSelectionLocked(key, pending.selection())
                    || !pending.preparation()
                            .retainedId()
                            .equals(input.path("retainedId").asText())) return;
            if (pending.revision() != deepPreparationRevision
                    || !pending.settings().equals(deepSettings.get())
                    || !pending.preparation().servers().contains(server)) {
                pushMessage(
                        new ErrorMsg(
                                "reviewError",
                                key,
                                "Stale, duplicate or changed deep review; prepare again."));
                return;
            }
            consumedDeepOperations.add(operationId);
            deepOperationId = operationId;
            deepPending = null;
        }
        deepBackground.accept(
                () -> {
                    boolean refreshing = true;
                    try {
                        var o = pending.options();
                        var fresh =
                                deepIo.fresh(
                                        o.path("number").asInt(),
                                        o.path("owner").asText(),
                                        o.path("repo").asText());
                        refreshing = false;
                        validateDeepHead(pending, fresh);
                        deepIo.generate(pending, operationId, server);
                    } catch (Exception error) {
                        synchronized (this) {
                            if (!disposed
                                    && pending.revision() == deepPreparationRevision
                                    && operationId.equals(deepOperationId)
                                    && isCurrentSelectionLocked(key, pending.selection())) {
                                if (refreshing && pending.settings().equals(deepSettings.get())) {
                                    deepPending = pending;
                                }
                                pushMessage(
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                String.valueOf(error.getMessage())));
                            }
                        }
                    }
                });
    }

    private void validateDeepHead(DeepPending pending) throws Exception {
        var o = pending.options();
        var fresh =
                deepIo.fresh(
                        o.path("number").asInt(),
                        o.path("owner").asText(),
                        o.path("repo").asText());
        validateDeepHead(pending, fresh);
    }

    private void validateDeepHead(DeepPending pending, FreshDeepPr fresh) throws Exception {
        synchronized (this) {
            if (disposed
                    || pending.revision() != deepPreparationRevision
                    || !fresh.head().sha().equals(pending.preparation().head())
                    || !fresh.diff().equals(pending.diff())
                    || !pending.settings().equals(deepSettings.get())
                    || !isCurrentSelectionLocked(pending.key(), pending.selection()))
                throw new java.io.IOException(
                        "Deep review PR, settings or selection changed; prepare again.");
        }
    }

    private void handleDeepMaintenance(JsonNode input) {
        if (disposed) return;
        deepBackground.accept(
                () -> {
                    try {
                        if ("cleanupDeepReview".equals(input.path("type").asText()))
                            deepIo.cleanup(
                                    input.path("retainedId").asText(),
                                    input.path("projectClosed").asBoolean());
                        pushMessage(
                                java.util.Map.of(
                                        "type",
                                        "retainedDeepReviews",
                                        "operationId",
                                        input.path("operationId").asText(),
                                        "retained",
                                        deepIo.list()));
                    } catch (Exception error) {
                        pushMessage(
                                java.util.Map.of(
                                        "type",
                                        "deepReviewMaintenanceError",
                                        "operationId",
                                        input.path("operationId").asText(),
                                        "message",
                                        String.valueOf(error.getMessage())));
                    }
                });
    }

    private void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            boolean chunkedReview,
            String focus,
            String custom,
            String operationId) {
        handleGenerateReview(
                number, owner, repo, overrideDiff, chunkedReview, focus, custom, operationId, null);
    }

    private void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            boolean chunkedReview,
            String overrideFocusAreas,
            String overrideCustomInstructions,
            String operationId,
            DeepInvocation deep) {
        String key = bridgePrKey(number, owner, repo);
        final PullRequest pr;
        final long reviewRevision;
        synchronized (this) {
            pr = activePR;
            reviewRevision = selectionRevision;
        }
        if (!matchesPrRequest(pr, number, owner, repo)) {
            pushMessage(new ErrorMsg("reviewError", key, "PR not found."));
            return;
        }

        PluginSettings settings = PluginSettings.getInstance();
        ReviewGenerationSettings generationSettings =
                new ReviewGenerationSettings(
                        IntellijClaudeService.snapshotReviewRuntimeSettings(),
                        settings.getResolvedReviewFocusAreas(),
                        settings.getResolvedReviewCustomInstructions(),
                        List.copyOf(settings.getResolvedReviewGuidanceGlobs()));
        long generationId;
        IntellijClaudeService previousReviewService;
        ReviewProvider previousReviewProvider;
        synchronized (this) {
            if (!isCurrentSelectionLocked(key, reviewRevision)) {
                return;
            }
            generationId = generationSequence.incrementAndGet();
            previousReviewService = activeReviewService;
            previousReviewProvider = activeReviewProvider;
            activeGenerationId = generationId;
            activeReviewService = claudeService;
            activeReviewProvider = generationSettings.runtime().provider();
            activeReviewOperationId = operationId;
        }
        previousReviewService.cancelCurrentRequest(previousReviewProvider);

        // Provider preflight: fail fast with actionable guidance instead of a raw CLI spawn error
        // when the configured review provider's binary isn't installed/resolvable.
        ReviewProvider provider = generationSettings.runtime().provider();
        if (!isProviderBinaryAvailable(provider)) {
            pushMessage(
                    new ErrorMsg(
                            "reviewError",
                            key,
                            UserFacingErrors.forProviderNotInstalled(provider)));
            synchronized (this) {
                if (isCurrentGenerationLocked(key, reviewRevision, generationId)) {
                    activeReviewService = claudeService;
                    activeReviewProvider = ReviewProvider.CLAUDE;
                    activeReviewOperationId = null;
                }
            }
            return;
        }

        // Dispatch all blocking work to a pooled thread so the JCEF bridge returns immediately
        // and status messages can flow during the network-fetch phase.
        getApplication()
                .executeOnPooledThread(
                        () -> {
                            if (!isCurrentGeneration(key, reviewRevision, generationId)) {
                                return;
                            }
                            // Atomically snapshot prefetched data to prevent check-then-act
                            // races with a concurrent handleSelectPR on the JCEF bridge thread.
                            String snapshotDiff;
                            String snapshotValidationDiff;
                            String snapshotReviews;
                            PullRequest promptPr;
                            synchronized (WebviewPanel.this) {
                                if (!isCurrentGenerationLocked(key, reviewRevision, generationId)) {
                                    return;
                                }
                                promptPr = activePR;
                                snapshotDiff = prefetchedDiff;
                                snapshotValidationDiff = prefetchedValidationDiff;
                                snapshotReviews = prefetchedExistingReviews;
                            }

                            // Reuse prefetched diff; fall back to live fetch only if stale.
                            String diff;
                            if (StringUtils.isNotBlank(overrideDiff)) {
                                diff = overrideDiff;
                            } else if (StringUtils.isNotBlank(snapshotDiff)) {
                                diff = snapshotDiff;
                            } else {
                                publishIfCurrentGeneration(
                                        key,
                                        reviewRevision,
                                        generationId,
                                        new ReviewGeneratingMsg(
                                                "reviewGenerating", key, "Fetching diff…"));
                                try {
                                    diff = ghSvc.getPRDiff(owner, repo, number);
                                } catch (Exception e) {
                                    publishIfCurrentGeneration(
                                            key,
                                            reviewRevision,
                                            generationId,
                                            new ErrorMsg(
                                                    "reviewError",
                                                    key,
                                                    UserFacingErrors.forGitHub(
                                                            e, "load the PR diff")));
                                    return;
                                }
                            }

                            String validationDiff;
                            if (StringUtils.isNotBlank(snapshotValidationDiff)) {
                                validationDiff = snapshotValidationDiff;
                            } else {
                                try {
                                    validationDiff = ghSvc.getPRDiffFull(owner, repo, number);
                                } catch (Exception e) {
                                    log.warn(
                                            "getPRDiffFull failed; falling back to truncated diff: {}",
                                            e.getMessage());
                                    validationDiff = diff;
                                }
                            }

                            // Reuse prefetched existing reviews; fall back to live fetch only if
                            // stale.
                            String existingReviews;
                            if (snapshotReviews != null) {
                                existingReviews = snapshotReviews;
                            } else {
                                try {
                                    existingReviews =
                                            ghSvc.getExistingReviewsSummary(owner, repo, number);
                                } catch (Exception e) {
                                    log.warn(
                                            "getExistingReviewsSummary failed: {}", e.getMessage());
                                    existingReviews = "";
                                }
                            }

                            // Prompt context. Each of these is additive: a failure degrades the
                            // prompt by one section and must never fail the review, so unlike the
                            // diff none of them abort the flow.
                            String ciStatus = "";
                            List<CiAnnotation> ciAnnotations = List.of();
                            try {
                                String headSha = ghSvc.getPRHeadSha(owner, repo, number);
                                if (StringUtils.isNotBlank(headSha)) {
                                    IntellijGitHubService.CheckContext checks =
                                            ghSvc.getCheckContext(owner, repo, headSha);
                                    ciStatus = checks.summary();
                                    ciAnnotations = checks.annotations();
                                }
                            } catch (Exception e) {
                                log.warn("getCheckContext failed: {}", e.getMessage());
                            }
                            IntellijGitHubService.CommitContext commitContext =
                                    new IntellijGitHubService.CommitContext("", List.of());
                            try {
                                commitContext = ghSvc.getCommitContext(owner, repo, number);
                            } catch (Exception e) {
                                log.warn("getCommitContext failed: {}", e.getMessage());
                            }
                            String linkedIssue = "";
                            try {
                                linkedIssue =
                                        ghSvc.getLinkedIssueSummary(
                                                owner,
                                                repo,
                                                promptPr.getBody(),
                                                commitContext.closingIssueNumbers());
                            } catch (Exception e) {
                                log.warn("getLinkedIssueSummary failed: {}", e.getMessage());
                            }

                            publishIfCurrentGeneration(
                                    key,
                                    reviewRevision,
                                    generationId,
                                    new ReviewGeneratingMsg(
                                            "reviewGenerating", key, "Preparing PR branch…"));
                            IntellijClaudeService reviewService;
                            try {
                                reviewService =
                                        deep == null
                                                ? resolvePrClaudeService(promptPr)
                                                : new IntellijClaudeService(
                                                        deep.pending().preparation().worktree());
                            } catch (Exception e) {
                                log.warn(
                                        "Worktree resolution for PR #{} failed: {}",
                                        number,
                                        e.getMessage());
                                synchronized (WebviewPanel.this) {
                                    if (isCurrentGenerationLocked(
                                            key, reviewRevision, generationId)) {
                                        activeReviewService = claudeService;
                                        activeReviewProvider = ReviewProvider.CLAUDE;
                                        activeReviewOperationId = null;
                                    }
                                }
                                publishIfCurrentGeneration(
                                        key,
                                        reviewRevision,
                                        generationId,
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                "Unable to create an isolated pull request worktree."
                                                        + " Open the PR repository and try again."));
                                return;
                            }

                            final IntellijClaudeService finalReviewService = reviewService;

                            // Kick off the review — callbacks fired on EDT
                            final String finalDiff = diff;
                            final String finalValidationDiff = validationDiff;
                            final String finalExisting = existingReviews;
                            java.io.File guidelinesDir;
                            ReviewResult priorResult;
                            synchronized (this) {
                                if (!isCurrentGenerationLocked(key, reviewRevision, generationId)) {
                                    return;
                                }
                                activeReviewService = finalReviewService;
                                activeReviewProvider = generationSettings.runtime().provider();
                                PrWorktree activeWorktree = worktrees.activeValue();
                                guidelinesDir =
                                        deep != null
                                                ? new java.io.File(
                                                        deep.pending().preparation().worktree())
                                                : activeWorktree != null
                                                        ? activeWorktree.directory()
                                                        : (project.getBasePath() != null
                                                                ? new java.io.File(
                                                                        project.getBasePath())
                                                                : null);
                                priorResult = lastResult;
                            }
                            publishIfCurrentGeneration(
                                    key,
                                    reviewRevision,
                                    generationId,
                                    new ReviewGeneratingMsg(
                                            "reviewGenerating", key, "Sending review request…"));
                            // Guidance in the PR worktree is authored by the change under review.
                            // Do not treat it as provider instructions until the engine can resolve
                            // it from the trusted base commit.
                            final String finalGuidelines = "";
                            final String finalPriorReview = formatPriorReview(priorResult);
                            final String finalFocusAreas =
                                    StringUtils.isNotBlank(overrideFocusAreas)
                                            ? overrideFocusAreas
                                            : generationSettings.focusAreas();
                            final String finalCustomInstructions =
                                    StringUtils.isNotBlank(overrideCustomInstructions)
                                            ? overrideCustomInstructions
                                            : generationSettings.customInstructions();
                            final String finalCiStatus = ciStatus;
                            final List<CiAnnotation> finalCiAnnotations = ciAnnotations;
                            final String finalCommits = commitContext.summary();
                            final String finalLinkedIssue = linkedIssue;
                            final String finalRepoProfile =
                                    guidelinesDir == null
                                            ? ""
                                            : ghSvc.getRepoProfileSummary(
                                                    guidelinesDir.getAbsolutePath());
                            finalReviewService.reviewPR(
                                    PRReviewRequest.builder(promptPr, finalDiff)
                                            .priorReview(finalPriorReview)
                                            .existingReviews(finalExisting)
                                            .repoGuidelines(finalGuidelines)
                                            .focusAreas(finalFocusAreas)
                                            .customInstructions(finalCustomInstructions)
                                            .ciStatus(finalCiStatus)
                                            .commits(finalCommits)
                                            .linkedIssue(finalLinkedIssue)
                                            .repoProfile(finalRepoProfile)
                                            .ciAnnotations(finalCiAnnotations)
                                            .build(),
                                    generationSettings.runtime(),
                                    chunkedReview,
                                    statusMsg ->
                                            publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ReviewGeneratingMsg(
                                                            "reviewGenerating", key, statusMsg)),
                                    (kind, chunk) ->
                                            publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ReviewChunkMsg(
                                                            "reviewChunk", key, kind, chunk)),
                                    result -> {
                                        if (result == null) {
                                            synchronized (WebviewPanel.this) {
                                                if (isCurrentGenerationLocked(
                                                        key, reviewRevision, generationId)) {
                                                    activeReviewOperationId = null;
                                                    activeReviewProvider = ReviewProvider.CLAUDE;
                                                    activeReviewService = claudeService;
                                                }
                                            }
                                            publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ErrorMsg(
                                                            "reviewError",
                                                            key,
                                                            UserFacingErrors.forProvider(
                                                                    provider,
                                                                    new Exception(
                                                                            "Provider produced no output"),
                                                                    "generate review")));
                                            return;
                                        }
                                        synchronized (WebviewPanel.this) {
                                            if (!isCurrentGenerationLocked(
                                                    key, reviewRevision, generationId)) {
                                                return;
                                            }
                                            activeReviewService = claudeService;
                                            activeReviewOperationId = null;
                                            activeReviewProvider = ReviewProvider.CLAUDE;
                                            lastResult = result;
                                            generatedReviews.put(
                                                    key,
                                                    new GeneratedReview(
                                                            generationId,
                                                            result,
                                                            generationMetadata(
                                                                    provider,
                                                                    generationSettings
                                                                            .runtime()
                                                                            .model(),
                                                                    generationSettings
                                                                            .runtime()
                                                                            .supervisorEnabled())));
                                            pendingReviewId = null;
                                        }
                                        publishIfCurrentGeneration(
                                                key,
                                                reviewRevision,
                                                generationId,
                                                new ReviewResultMsg(
                                                        "reviewResult",
                                                        key,
                                                        ReviewMapper.INSTANCE.toDto(result),
                                                        finalDiff,
                                                        finalValidationDiff));
                                    },
                                    err -> {
                                        synchronized (WebviewPanel.this) {
                                            if (!isCurrentGenerationLocked(
                                                    key, reviewRevision, generationId)) {
                                                return;
                                            }
                                            activeReviewService = claudeService;
                                            activeReviewOperationId = null;
                                            activeReviewProvider = ReviewProvider.CLAUDE;
                                        }
                                        // Cancellations are user-initiated — don't surface as
                                        // errors.
                                        String lower = err.toLowerCase(java.util.Locale.ROOT);
                                        if (!lower.contains("cancel")
                                                && !lower.contains("interrupt")) {
                                            if (deep != null) {
                                                synchronized (WebviewPanel.this) {
                                                    if (!disposed
                                                            && deep.pending().revision()
                                                                    == deepPreparationRevision
                                                            && isCurrentGenerationLocked(
                                                                    key,
                                                                    reviewRevision,
                                                                    generationId)) {
                                                        deepPending = deep.pending();
                                                        publishDeepPrepared(
                                                                deep.pending(), operationId, err);
                                                    }
                                                }
                                                return;
                                            }
                                            publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ErrorMsg("reviewError", key, err));
                                        }
                                    },
                                    deep == null
                                            ? null
                                            : new IntellijClaudeService.DeepReviewOperation(
                                                    semanticReviews,
                                                    deep.pending().preparation().retainedId(),
                                                    deep.server(),
                                                    owner + "/" + repo + "#" + number,
                                                    operationId,
                                                    () -> validateDeepHead(deep.pending())));
                        });
    }

    private static boolean isProviderBinaryAvailable(ReviewProvider provider) {
        return provider == ReviewProvider.COPILOT
                ? CopilotService.isBinaryAvailable()
                : ClaudeService.isBinaryAvailable();
    }

    private static ProviderReadinessDto currentProviderReadiness() {
        ReviewProvider provider = PluginSettings.getInstance().getReviewProvider();
        boolean available = isProviderBinaryAvailable(provider);
        return new ProviderReadinessDto(
                provider == ReviewProvider.COPILOT ? "copilot" : "claude",
                available,
                available
                        ? "Provider CLI found. Authentication cannot be verified without starting a provider session."
                        : UserFacingErrors.forProviderNotInstalled(provider),
                available ? "ready" : "missing",
                available ? "unverified" : "unavailable",
                provider == ReviewProvider.COPILOT ? "copilot login" : "claude auth login");
    }

    private static ProviderReadinessDto providerReadiness(ProviderSetupProbe.Result setup) {
        ReviewProvider provider = PluginSettings.getInstance().getReviewProvider();
        String detail;
        if (!setup.available()) {
            detail = UserFacingErrors.forProviderNotInstalled(provider);
        } else if ("ready".equals(setup.authenticationStatus())) {
            detail = "Provider CLI and authentication are ready.";
        } else if ("unavailable".equals(setup.authenticationStatus())) {
            detail =
                    "Provider authentication is unavailable. Run '"
                            + setup.authCommand()
                            + "' and check again.";
        } else {
            detail =
                    "Provider CLI found. Authentication cannot be verified non-interactively; run '"
                            + setup.authCommand()
                            + "' if sign-in is required.";
        }
        return new ProviderReadinessDto(
                provider == ReviewProvider.COPILOT ? "copilot" : "claude",
                setup.available(),
                detail,
                setup.binaryStatus(),
                setup.authenticationStatus(),
                setup.authCommand());
    }

    // --- saveDraft ---

    private void handleSaveDraft(
            int number,
            String owner,
            String repo,
            long saveId,
            ReviewResult bridgeResult,
            ReviewResult bridgeGeneratedResult,
            List<LineComment> orphans) {
        String key = bridgePrKey(number, owner, repo);
        boolean activeAtStart = isActivePrKey(key);
        if (!canPersistDraft(activeAtStart, bridgeResult != null)) {
            pushMessage(
                    new DraftSaveErrorMsg(
                            "draftSaveError",
                            key,
                            saveId,
                            "The selected pull request changed before the draft could be saved."));
            return;
        }
        long revision = selectionRevision;
        ReviewResult result = bridgeResult != null ? bridgeResult : lastResult;
        if (result == null) {
            pushMessage(
                    new DraftSaveErrorMsg(
                            "draftSaveError", key, saveId, "No review result to save."));
            return;
        }

        IntellijGitHubService.SaveDraftResult saved;
        try {
            draftRecoveryStore.save(key, result, orphans);
            saved = ghSvc.saveDraftReview(owner, repo, number, result, orphans);
            draftRecoveryStore.clear(key);
        } catch (Exception e) {
            pushMessage(
                    new DraftSaveErrorMsg(
                            "draftSaveError",
                            key,
                            saveId,
                            UserFacingErrors.forGitHub(e, "save the draft review")));
            return;
        }

        String headSha = "";
        try {
            headSha = ghSvc.getPRHeadSha(owner, repo, number);
        } catch (Exception e) {
            log.warn("getPRHeadSha failed during saveDraft: {}", e.getMessage());
        }

        PullRequest pr =
                cachedPRs.stream()
                        .filter(
                                p ->
                                        p.getNumber() == number
                                                && p.getOwner().equals(owner)
                                                && p.getRepo().equals(repo))
                        .findFirst()
                        .orElse(null);
        String title = pr != null ? pr.getTitle() : "";
        PendingReviewIndex.MutationResult indexResult =
                pendingIndex.add(owner, repo, number, title, headSha);
        reportPendingIndexMutation("saving draft", indexResult);
        GeneratedReview generated = generatedReviews.get(key);
        if (bridgeGeneratedResult != null && generated != null) {
            generatedReviews.put(
                    key,
                    new GeneratedReview(
                            generated.generationId(), bridgeGeneratedResult, generated.metadata()));
        }
        if (!isActivePrKey(key) || selectionRevision != revision) {
            return;
        }
        pendingReviewId = saved.reviewId();
        pendingReviewKey = key;
        lastResult = result;

        pushMessage(
                new DraftSavedMsg(
                        "draftSaved", key, saveId, saved.reviewId(), saved.commentsDropped()));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, true));
    }

    static boolean canPersistDraft(boolean activePr, boolean hasExplicitResult) {
        return activePr || hasExplicitResult;
    }

    // --- submitReview ---

    private void handleSubmitReview(
            int number, String owner, String repo, String verdict, String comment) {
        String key = bridgePrKey(number, owner, repo);
        String reviewId = pendingReviewId;
        if (StringUtils.isBlank(reviewId)
                || !isActivePrKey(key)
                || !StringUtils.equals(pendingReviewKey, key)) {
            pushMessage(
                    new ErrorMsg(
                            "reviewSubmitError",
                            key,
                            "No pending draft review belongs to the selected pull request."));
            return;
        }

        try {
            ghSvc.submitDraftReview(owner, repo, number, reviewId, verdict, comment);
            draftRecoveryStore.clear(key);
        } catch (Exception e) {
            pushMessage(
                    new ErrorMsg(
                            "reviewSubmitError",
                            key,
                            UserFacingErrors.forGitHub(e, "submit the draft review")));
            return;
        }

        PendingReviewIndex.MutationResult indexResult = pendingIndex.remove(owner, repo, number);
        reportPendingIndexMutation("submitting draft", indexResult);
        GeneratedReview generated = generatedReviews.remove(key);
        if (generated != null) {
            recordReviewOutcome(generated.result(), lastResult, generated.metadata());
        }
        if (StringUtils.equals(pendingReviewId, reviewId)
                && StringUtils.equals(pendingReviewKey, key)) {
            lastResult = null;
            pendingReviewId = null;
            pendingReviewKey = null;
        }

        pushMessage(new SimpleMsg("reviewSubmitted", key));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    static ReviewOutcomeLog.Metadata generationMetadata(
            ReviewProvider provider, String model, boolean supervisorEnabled) {
        return new ReviewOutcomeLog.Metadata(
                ClaudeService.reviewPipelineVersion(supervisorEnabled),
                provider.name().toLowerCase(java.util.Locale.ROOT),
                model);
    }

    /**
     * Logs what the reviewer did with each generated comment. Runs off the EDT and swallows
     * everything: the review has already been submitted, so instrumentation must not report an
     * error or block the UI. A no-op when the generated review is unavailable (a draft loaded from
     * GitHub in a later session was never generated locally, so there is nothing to compare).
     */
    private void recordReviewOutcome(
            ReviewResult generated, ReviewResult submitted, ReviewOutcomeLog.Metadata metadata) {
        if (generated == null) return;
        List<LineComment> generatedComments = generated.getLineComments();
        List<LineComment> submittedComments =
                submitted == null ? List.of() : submitted.getLineComments();
        getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                outcomeLog.record(generatedComments, submittedComments, metadata);
                            } catch (Exception e) {
                                log.warn("Review outcome logging failed: {}", e.getMessage());
                            }
                        });
    }

    // --- deleteDraft ---

    private void handleDeleteDraft(int number, String owner, String repo) {
        String key = bridgePrKey(number, owner, repo);
        String reviewId = pendingReviewId;
        if (StringUtils.isBlank(reviewId)
                || !isActivePrKey(key)
                || !StringUtils.equals(pendingReviewKey, key)) {
            pushMessage(
                    new ErrorMsg(
                            "draftDeleteError",
                            key,
                            "No pending draft review belongs to the selected pull request."));
            return;
        }

        try {
            ghSvc.deleteDraftReview(owner, repo, number, reviewId);
            draftRecoveryStore.clear(key);
        } catch (Exception e) {
            pushMessage(
                    new ErrorMsg(
                            "draftDeleteError",
                            key,
                            UserFacingErrors.forGitHub(e, "delete the draft review")));
            return;
        }

        PendingReviewIndex.MutationResult indexResult = pendingIndex.remove(owner, repo, number);
        reportPendingIndexMutation("deleting draft", indexResult);
        if (StringUtils.equals(pendingReviewId, reviewId)
                && StringUtils.equals(pendingReviewKey, key)) {
            lastResult = null;
            generatedReviews.remove(key);
            pendingReviewId = null;
            pendingReviewKey = null;
        }

        pushMessage(new SimpleMsg("draftDeleted", key));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    // --- askClaude ---

    private void handleAskClaude(String question, String context, String operationId) {
        if (StringUtils.isBlank(question)) {
            return;
        }

        final PullRequest pr;
        final long selectionRevisionSnapshot;
        final long chatId;
        final List<ChatMessage> history;
        final IntellijClaudeService.ReviewRuntimeSettings runtimeSettings;
        final IntellijClaudeService previousChatService;
        final ReviewProvider previousChatProvider;
        synchronized (this) {
            pr = activePR;
            selectionRevisionSnapshot = selectionRevision;
            chatId = chatSequence.incrementAndGet();
            activeChatId = chatId;
            history = List.copyOf(chatHistory);
            runtimeSettings = IntellijClaudeService.snapshotReviewRuntimeSettings();
            previousChatService = activeChatService;
            previousChatProvider = activeChatProvider;
            activeChatService = claudeService;
            activeChatProvider = runtimeSettings.provider();
            activeChatOperationId = operationId;
        }
        if (pr == null) {
            synchronized (this) {
                if (activeChatId == chatId
                        && StringUtils.equals(activeChatOperationId, operationId)) {
                    activeChatService = claudeService;
                    activeChatProvider = ReviewProvider.CLAUDE;
                    activeChatOperationId = null;
                }
            }
            pushMessage(new ErrorMsg("chatError", null, "No PR selected."));
            return;
        }
        String key = bridgePrKey(pr.getNumber(), pr.getOwner(), pr.getRepo());
        previousChatService.cancelCurrentRequest(previousChatProvider);

        IntellijClaudeService chatService;
        try {
            chatService = resolvePrClaudeService(pr);
        } catch (Exception e) {
            log.warn("Worktree resolution for PR #{} failed: {}", pr.getNumber(), e.getMessage());
            pushMessage(
                    new ErrorMsg(
                            "chatError",
                            key,
                            "Unable to create an isolated pull request worktree."
                                    + " Open the PR repository and try again."));
            synchronized (this) {
                if (isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                    activeChatService = claudeService;
                    activeChatProvider = ReviewProvider.CLAUDE;
                    activeChatOperationId = null;
                }
            }
            return;
        }
        synchronized (this) {
            if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                return;
            }
            activeChatService = chatService;
        }

        // When the user has selected a code snippet, use a focused prompt (no history, no PR
        // context) — matching VS Code's buildFocusedChatPrompt path. Responses for focused
        // questions are not stored in chatHistory since they are context-specific.
        if (StringUtils.isNotBlank(context)) {
            chatService.chatFocused(
                    context,
                    question,
                    runtimeSettings,
                    chunk ->
                            publishIfCurrentChat(
                                    key,
                                    selectionRevisionSnapshot,
                                    chatId,
                                    new ChatChunkMsg("chatChunk", key, chunk)),
                    response -> {
                        synchronized (WebviewPanel.this) {
                            if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                                return;
                            }
                            activeChatService = claudeService;
                            activeChatProvider = ReviewProvider.CLAUDE;
                            activeChatOperationId = null;
                        }
                        publishIfCurrentChat(
                                key,
                                selectionRevisionSnapshot,
                                chatId,
                                new ChatResponseMsg("chatResponse", key, response));
                    },
                    err -> {
                        synchronized (WebviewPanel.this) {
                            if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                                return;
                            }
                            activeChatService = claudeService;
                            activeChatProvider = ReviewProvider.CLAUDE;
                            activeChatOperationId = null;
                        }
                        publishIfCurrentChat(
                                key,
                                selectionRevisionSnapshot,
                                chatId,
                                new ErrorMsg("chatError", key, err));
                    });
            return;
        }

        String prContext = buildPrContext(pr);

        chatService.chat(
                prContext,
                history,
                question,
                runtimeSettings,
                chunk ->
                        publishIfCurrentChat(
                                key,
                                selectionRevisionSnapshot,
                                chatId,
                                new ChatChunkMsg("chatChunk", key, chunk)),
                response -> {
                    synchronized (WebviewPanel.this) {
                        if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                            return;
                        }
                        List<ChatMessage> updated = new ArrayList<>(history);
                        updated.add(new ChatMessage(ChatMessage.Role.USER, question));
                        updated.add(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
                        chatHistory = List.copyOf(updated);
                        activeChatService = claudeService;
                        activeChatProvider = ReviewProvider.CLAUDE;
                        activeChatOperationId = null;
                    }
                    publishIfCurrentChat(
                            key,
                            selectionRevisionSnapshot,
                            chatId,
                            new ChatResponseMsg("chatResponse", key, response));
                },
                err -> {
                    synchronized (WebviewPanel.this) {
                        if (!isCurrentChatLocked(key, selectionRevisionSnapshot, chatId)) {
                            return;
                        }
                        activeChatService = claudeService;
                        activeChatProvider = ReviewProvider.CLAUDE;
                        activeChatOperationId = null;
                    }
                    publishIfCurrentChat(
                            key,
                            selectionRevisionSnapshot,
                            chatId,
                            new ErrorMsg("chatError", key, err));
                });
    }

    private void clearChat(String operationId) {
        IntellijClaudeService service;
        ReviewProvider provider;
        synchronized (this) {
            if (!StringUtils.equals(activeChatOperationId, operationId)) {
                chatHistory = List.of();
                return;
            }
            activeChatId = chatSequence.incrementAndGet();
            chatHistory = List.of();
            service = activeChatService;
            provider = activeChatProvider;
            activeChatService = claudeService;
            activeChatProvider = ReviewProvider.CLAUDE;
            activeChatOperationId = null;
        }
        service.cancelCurrentRequest(provider);
    }

    /**
     * Stops the chat answer that owns {@code operationId} without deleting conversation history.
     * Advancing the chat id drops the stopped answer's late chunks, response and error silently.
     */
    private void cancelChat(String operationId) {
        IntellijClaudeService service;
        ReviewProvider provider;
        synchronized (this) {
            if (activeChatOperationId == null
                    || !StringUtils.equals(activeChatOperationId, operationId)) {
                return;
            }
            activeChatId = chatSequence.incrementAndGet();
            service = activeChatService;
            provider = activeChatProvider;
            activeChatService = claudeService;
            activeChatProvider = ReviewProvider.CLAUDE;
            activeChatOperationId = null;
        }
        if (service != null) service.cancelCurrentRequest(provider);
    }

    static String worktreeKey(int number, String owner, String repo) {
        return owner.toLowerCase(java.util.Locale.ROOT)
                + "/"
                + repo.toLowerCase(java.util.Locale.ROOT)
                + "#"
                + number;
    }

    static String bridgePrKey(int number, String owner, String repo) {
        return owner + "/" + repo + "#" + number;
    }

    static String normalizeSearchScope(String value) {
        return switch (value) {
            case "authored", "assigned", "reviewRequested" -> value;
            default -> "currentRepo";
        };
    }

    static boolean isSamePr(PullRequest left, PullRequest right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getNumber() == right.getNumber()
                && StringUtils.equalsIgnoreCase(left.getOwner(), right.getOwner())
                && StringUtils.equalsIgnoreCase(left.getRepo(), right.getRepo());
    }

    static PullRequest hydratePullRequest(PullRequest summary, PrDetail detail) {
        if (detail == null) {
            return summary;
        }
        return new PullRequest(
                detail.title(),
                summary.getHtmlUrl(),
                summary.getOwner(),
                summary.getRepo(),
                summary.getNumber(),
                detail.body(),
                summary.getAuthor(),
                summary.getCreatedAt(),
                summary.isDraft(),
                summary.getReviewStatus());
    }

    static boolean matchesPrRequest(PullRequest pr, int number, String owner, String repo) {
        return pr != null
                && pr.getNumber() == number
                && StringUtils.equalsIgnoreCase(pr.getOwner(), owner)
                && StringUtils.equalsIgnoreCase(pr.getRepo(), repo);
    }

    static boolean isCurrentSelection(
            PullRequest currentPr,
            long currentRevision,
            String expectedKey,
            long expectedRevision) {
        return currentPr != null
                && currentRevision == expectedRevision
                && StringUtils.equals(
                        bridgePrKey(
                                currentPr.getNumber(), currentPr.getOwner(), currentPr.getRepo()),
                        expectedKey);
    }

    private boolean isCurrentSelection(String expectedKey, long expectedRevision) {
        synchronized (this) {
            return isCurrentSelectionLocked(expectedKey, expectedRevision);
        }
    }

    private boolean isCurrentSelectionLocked(String expectedKey, long expectedRevision) {
        return isCurrentSelection(activePR, selectionRevision, expectedKey, expectedRevision);
    }

    private void publishIfCurrentSelection(
            String expectedKey, long expectedRevision, Object message) {
        synchronized (this) {
            if (isCurrentSelectionLocked(expectedKey, expectedRevision)) {
                pushMessage(message);
            }
        }
    }

    private boolean isCurrentGeneration(
            String expectedKey, long expectedRevision, long expectedGenerationId) {
        synchronized (this) {
            return isCurrentGenerationLocked(expectedKey, expectedRevision, expectedGenerationId);
        }
    }

    private boolean isCurrentGenerationLocked(
            String expectedKey, long expectedRevision, long expectedGenerationId) {
        return activeGenerationId == expectedGenerationId
                && isCurrentSelectionLocked(expectedKey, expectedRevision);
    }

    private void publishIfCurrentGeneration(
            String expectedKey, long expectedRevision, long expectedGenerationId, Object message) {
        synchronized (this) {
            if (isCurrentGenerationLocked(expectedKey, expectedRevision, expectedGenerationId)) {
                pushMessage(message);
            }
        }
    }

    static boolean isCurrentChat(
            PullRequest currentPr,
            long currentSelectionRevision,
            long currentChatId,
            String expectedKey,
            long expectedSelectionRevision,
            long expectedChatId) {
        return currentChatId == expectedChatId
                && isCurrentSelection(
                        currentPr,
                        currentSelectionRevision,
                        expectedKey,
                        expectedSelectionRevision);
    }

    private boolean isCurrentChatLocked(
            String expectedKey, long expectedSelectionRevision, long expectedChatId) {
        return isCurrentChat(
                activePR,
                selectionRevision,
                activeChatId,
                expectedKey,
                expectedSelectionRevision,
                expectedChatId);
    }

    private void publishIfCurrentChat(
            String expectedKey,
            long expectedSelectionRevision,
            long expectedChatId,
            Object message) {
        synchronized (this) {
            if (!isCurrentChatLocked(expectedKey, expectedSelectionRevision, expectedChatId)) {
                return;
            }
            pushMessage(message);
        }
    }

    private boolean isActivePrKey(String key) {
        PullRequest pr = activePR;
        return pr != null
                && StringUtils.equals(
                        key, bridgePrKey(pr.getNumber(), pr.getOwner(), pr.getRepo()));
    }

    /** Formats a prior generated review as compact context for a re-generation prompt. */
    private static String formatPriorReview(ReviewResult result) {
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

    private IntellijClaudeService resolvePrClaudeService(PullRequest pr) {
        // Phase 1: quick local checks — hold the lock briefly to read shared fields.
        final String key;
        final java.io.File detectedRoot;
        final WorktreeLease<PrWorktree> lease;
        synchronized (this) {
            if (pr == null || !isSamePr(activePR, pr)) {
                throw new IllegalStateException("The selected pull request changed.");
            }
            key = worktreeKey(pr.getNumber(), pr.getOwner(), pr.getRepo());
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                throw new IllegalStateException(
                        "Open the pull request repository before starting a review or chat.");
            }
            java.io.File root = worktreeService.findGitRoot(new java.io.File(projectPath));
            String currentRepo = ghSvc.detectCurrentRepo(projectPath);
            boolean sameRepo =
                    currentRepo != null
                            && currentRepo.equalsIgnoreCase(pr.getOwner() + "/" + pr.getRepo());
            if (root == null || !sameRepo) {
                throw new IllegalStateException(
                        "Open the pull request repository before starting a review or chat.");
            }
            detectedRoot = root;
            lease = worktrees.acquire(key);
        }

        if (!lease.owner()) {
            return serviceForWorktree(lease.future().join().directory());
        }

        java.io.File wt = worktreeService.newWorktreePath(pr.getNumber());
        try {
            IntellijGitHubService.PRHeadInfo headInfo =
                    ghSvc.getPRHeadInfo(pr.getOwner(), pr.getRepo(), pr.getNumber());
            if (headInfo.ref().isBlank()) {
                worktrees.fail(lease);
                throw new IllegalStateException("Unable to determine the pull request branch.");
            }

            if (headInfo.isFork()) {
                worktreeService.createWorktreeFromFork(
                        detectedRoot, headInfo.forkCloneUrl(), headInfo.ref(), headInfo.sha(), wt);
            } else {
                worktreeService.createWorktree(detectedRoot, headInfo.ref(), headInfo.sha(), wt);
            }

            PrWorktree created = new PrWorktree(wt, detectedRoot);
            if (worktrees.install(lease, created)) {
                log.info("Using worktree {} for PR #{}", wt, pr.getNumber());
                return serviceForWorktree(created.directory());
            }
            if (!worktreeService.removeWorktree(detectedRoot, wt)) {
                log.warn("Failed to remove discarded worktree at {}", wt);
            }
            throw new IllegalStateException("The selected pull request changed.");
        } catch (Exception e) {
            worktrees.fail(lease);
            if (wt.exists()) {
                if (!worktreeService.removeWorktree(detectedRoot, wt)) {
                    log.warn("Failed to remove incomplete worktree at {}", wt);
                }
            }
            log.warn("Worktree creation for PR #{} failed: {}", pr.getNumber(), e.getMessage());
            throw new IllegalStateException(
                    "Unable to create an isolated pull request worktree.", e);
        }
    }

    static IntellijClaudeService serviceForWorktree(java.io.File directory) {
        return new IntellijClaudeService(directory.getAbsolutePath());
    }

    private void removeWorktreeAsync(PrWorktree worktree) {
        if (worktree != null && worktree.directory() != null && worktree.gitRoot() != null) {
            getApplication()
                    .executeOnPooledThread(
                            () -> {
                                if (!worktreeService.removeWorktree(
                                        worktree.gitRoot(), worktree.directory())) {
                                    log.warn(
                                            "Failed to remove worktree at {}",
                                            worktree.directory());
                                }
                            });
        }
    }

    private String buildPrContext(PullRequest pr) {
        StringBuilder sb = new StringBuilder();
        sb.append("PR #").append(pr.getNumber()).append(": ").append(pr.getTitle()).append("\n");
        sb.append("Author: @").append(pr.getAuthor()).append("\n");
        sb.append("Repo: ").append(pr.getOwner()).append("/").append(pr.getRepo()).append("\n");

        String body = pr.getBody();
        if (StringUtils.isNotBlank(body)) {
            sb.append("\nPR Description:\n").append(body).append("\n");
        }

        ReviewResult result = lastResult;
        if (result != null) {
            sb.append("\nReview verdict: ").append(result.getVerdict()).append("\n");
            sb.append("Review summary: ").append(result.getSummary()).append("\n");
        }

        String diff = prefetchedDiff;
        if (StringUtils.isNotBlank(diff)) {
            sb.append("\nDiff:\n").append(diff);
        }

        return sb.toString();
    }

    // --- Helpers ---

    /**
     * Serializes {@code payload} to JSON and pushes it into the webview via {@code
     * __handleMessage}. The JSON is embedded directly as a JS expression (JSON is a valid JS
     * literal) instead of as a quoted string, avoiding script-injection risk from untrusted PR
     * content. U+2028/U+2029 are escaped because they are line terminators in JS but appear as
     * literal characters inside JSON strings.
     */
    private void pushMessage(Object payload) {
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
        synchronized (lifecycleLock) {
            if (!disposed.getAsBoolean()) {
                browserCall.run();
            }
        }
    }

    private void pushCurrentTheme() {
        getApplication()
                .invokeLater(
                        () -> {
                            if (disposed) {
                                return;
                            }
                            String lafName =
                                    StringUtils.defaultString(
                                                    UIManager.getLookAndFeel() == null
                                                            ? null
                                                            : UIManager.getLookAndFeel().getName())
                                            .toLowerCase(java.util.Locale.ROOT);
                            boolean highContrast = lafName.contains("contrast");
                            boolean dark = UIUtil.isUnderDarcula();
                            String theme = HostThemeClassifier.classify(dark, highContrast);
                            pushMessage(new ThemeChangedMsg("themeChanged", theme));
                        });
    }

    /** Pushes the PR list into the webview via the bridge. Call from the EDT. */
    public void loadPRs(
            List<PullRequest> prs,
            String defaultRepo,
            String searchScope,
            String currentRepo,
            boolean limited,
            boolean reviewStatusAvailable,
            ProviderSetupProbe.Result providerSetup) {
        cachedPRs = prs;
        ProviderReadinessDto providerReadiness = providerReadiness(providerSetup);
        if (!providerReadiness.available()) {
            pushSetupRequired(
                    "provider_not_installed", providerReadiness.detail(), providerReadiness);
            return;
        }
        if ("unavailable".equals(providerReadiness.authenticationStatus())) {
            pushSetupRequired(
                    "provider_not_authenticated", providerReadiness.detail(), providerReadiness);
            return;
        }
        Optional<List<PendingReviewIndex.Entry>> pendingEntries = loadHealthyDraftEntries();
        if (pendingEntries.isEmpty()) {
            pushSetupRequired(
                    "draft_index_unavailable", PendingReviewIndexNotifications.userMessage());
            return;
        }
        Set<String> draftKeys =
                pendingEntries.orElseThrow().stream()
                        .map(e -> e.owner() + "/" + e.repo() + "#" + e.number())
                        .collect(java.util.stream.Collectors.toSet());
        List<WebviewPr> dtos =
                prs.stream()
                        .map(
                                pr ->
                                        toWebviewPr(
                                                pr,
                                                draftKeys.contains(
                                                        pr.getOwner()
                                                                + "/"
                                                                + pr.getRepo()
                                                                + "#"
                                                                + pr.getNumber())))
                        .toList();
        pushMessage(
                prListMessage(
                        dtos,
                        defaultRepo,
                        new PrListStatus(
                                searchScope,
                                currentRepo,
                                PR_SEARCH_LIMIT,
                                limited,
                                reviewStatusAvailable),
                        providerReadiness));
    }

    /**
     * Carries the experimental setting on the session-level list message so PR-agnostic webview
     * surfaces (retained-worktree maintenance) can honor it before any PR is selected.
     */
    PrListMessage prListMessage(
            List<WebviewPr> prs,
            String defaultRepo,
            PrListStatus listStatus,
            ProviderReadinessDto providerReadiness) {
        return new PrListMessage(
                "prListLoaded",
                prs,
                defaultRepo,
                listStatus,
                providerReadiness,
                intellijAssistedEnabled.getAsBoolean());
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

    private void pushSetupRequired(
            String reason, String detail, ProviderReadinessDto providerReadiness) {
        pushMessage(new SetupRequiredMsg("setupRequired", reason, detail, providerReadiness));
    }

    public void activatePr(PullRequest pr, String source) {
        Optional<List<PendingReviewIndex.Entry>> pendingEntries = loadHealthyDraftEntries();
        if (pendingEntries.isEmpty()) {
            pushSetupRequired(
                    "draft_index_unavailable", PendingReviewIndexNotifications.userMessage());
            return;
        }
        boolean hasReviewDraft =
                pendingEntries.orElseThrow().stream()
                        .anyMatch(
                                entry ->
                                        entry.owner().equals(pr.getOwner())
                                                && entry.repo().equals(pr.getRepo())
                                                && entry.number() == pr.getNumber());
        PullRequest activatedPr =
                cachedPRs.stream()
                        .filter(existing -> isSamePr(existing, pr))
                        .findFirst()
                        .map(existing -> mergeActivatedPr(existing, pr))
                        .orElse(pr);
        if (cachedPRs.stream().anyMatch(existing -> isSamePr(existing, activatedPr))) {
            cachedPRs =
                    cachedPRs.stream()
                            .map(
                                    existing ->
                                            isSamePr(existing, activatedPr)
                                                    ? activatedPr
                                                    : existing)
                            .toList();
        } else {
            List<PullRequest> next = new ArrayList<>();
            next.add(activatedPr);
            next.addAll(cachedPRs);
            cachedPRs = next;
        }
        pushMessage(
                new ActivatePrMsg("activatePR", toWebviewPr(activatedPr, hasReviewDraft), source));
    }

    static Optional<List<PendingReviewIndex.Entry>> healthyDraftEntries(
            PendingReviewIndex.LoadResult result) {
        return result.healthy() ? Optional.of(result.entries()) : Optional.empty();
    }

    private Optional<List<PendingReviewIndex.Entry>> loadHealthyDraftEntries() {
        PendingReviewIndex.LoadResult result = pendingIndex.listResult();
        observePendingIndex(result);
        return healthyDraftEntries(result);
    }

    private void reportPendingIndexMutation(
            String operation, PendingReviewIndex.MutationResult result) {
        if (result == PendingReviewIndex.MutationResult.UPDATED) {
            return;
        }
        log.warn("Pending review index was not updated after {}: {}", operation, result);
        if (result == PendingReviewIndex.MutationResult.BLOCKED_CORRUPT) {
            PendingReviewIndex.LoadResult loadResult = pendingIndex.listResult();
            observePendingIndex(loadResult);
        }
    }

    private void observePendingIndex(PendingReviewIndex.LoadResult result) {
        pendingIndexRecoveryRegistration.close();
        pendingIndexRecoveryRegistration =
                PendingReviewIndexNotifications.observe(
                        pendingIndex, result, pendingIndexRecoveryAction);
    }

    private static WebviewPr toWebviewPr(PullRequest pr, boolean hasReviewDraft) {
        return new WebviewPr(
                pr.getNumber(),
                pr.getTitle(),
                pr.getOwner(),
                pr.getRepo(),
                pr.getAuthor(),
                pr.getCreatedAt(),
                pr.getHtmlUrl(),
                pr.isDraft(),
                hasReviewDraft,
                pr.getReviewStatus());
    }

    static PullRequest mergeActivatedPr(PullRequest existing, PullRequest incoming) {
        return incoming.getReviewStatus() == ReviewStatus.UNAVAILABLE
                        && existing.getReviewStatus() != ReviewStatus.UNAVAILABLE
                ? incoming.withReviewStatus(existing.getReviewStatus())
                : incoming;
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

    @Override
    public void dispose() {
        LifecycleTransition transition;
        synchronized (this) {
            if (disposed) {
                return;
            }
            disposed = true;
            transition = transitionToSelection(null);
        }
        finishLifecycleTransition(transition);
        pendingIndexRecoveryRegistration.close();
        HttpServer server = httpServer;
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception e) {
                log.warn("HttpServer.stop failed: {}", e.getMessage());
            }
            httpServer = null;
        }
        Disposer.dispose(bridgeQuery);
        Disposer.dispose(browser);
    }
}
