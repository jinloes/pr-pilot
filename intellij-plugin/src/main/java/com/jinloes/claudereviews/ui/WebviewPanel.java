package com.jinloes.claudereviews.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.jinloes.claudereviews.model.ChatMessage;
import com.jinloes.claudereviews.model.PRReviewRequest;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import com.jinloes.claudereviews.services.GitHubService;
import com.jinloes.claudereviews.services.IntellijClaudeService;
import com.jinloes.claudereviews.services.IntellijGitHubService;
import com.jinloes.claudereviews.services.PatternKnowledgeBase;
import com.jinloes.claudereviews.services.PendingReviewIndex;
import com.jinloes.claudereviews.settings.PluginSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.JComponent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

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
@Slf4j
public class WebviewPanel {

    // --- Outbound DTO records (Java → JS) ---

    private record WebviewPr(
            int number,
            String title,
            String owner,
            String repo,
            String author,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("htmlUrl") String htmlUrl,
            @JsonProperty("hasDraft") boolean hasDraft) {}

    private record PrListMessage(
            String type, List<WebviewPr> prs, @JsonProperty("defaultRepo") String defaultRepo) {}

    private record DraftLoadingMsg(String type) {}

    private record ReviewResultDto(
            String summary,
            String verdict,
            @JsonProperty("lineComments") List<LineCommentDto> lineComments) {}

    private record LineCommentDto(String file, int line, String type, String body) {}

    private record DraftLoadedMsg(
            String type,
            String prState,
            @JsonProperty("reviewId") String reviewId,
            @JsonProperty("result") ReviewResultDto result,
            String diff,
            boolean staleCommits,
            String status) {}

    private record ReviewGeneratingMsg(String type, String message) {}

    private record ReviewChunkMsg(String type, String kind, String chunk) {}

    private record ReviewResultMsg(String type, ReviewResultDto result, String diff) {}

    private record ErrorMsg(String type, String message) {}

    private record DraftSavedMsg(String type, String reviewId, boolean commentsDropped) {}

    private record SimpleMsg(String type) {}

    private record ChatChunkMsg(String type, String chunk) {}

    private record ChatResponseMsg(String type, String response) {}

    private record PrDraftStatusMsg(
            String type,
            int number,
            String owner,
            String repo,
            @JsonProperty("hasDraft") boolean hasDraft) {}

    // --- Infrastructure ---

    private final HttpServer httpServer;
    private final JBCefBrowser browser;
    private final JBCefJSQuery bridgeQuery;
    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new KotlinModule.Builder().build());
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();
    private final PatternKnowledgeBase patternKb = new PatternKnowledgeBase();
    private final IntellijClaudeService claudeService;
    private final IntellijGitHubService ghSvc = IntellijGitHubService.getInstance();
    private final Project project;

    private volatile List<PullRequest> cachedPRs = List.of();
    private volatile PullRequest activePR = null;
    private volatile ReviewResult lastResult = null;
    private volatile String pendingReviewId = null;
    private volatile String prefetchedDiff = null;
    private volatile String prefetchedExistingReviews = null;
    private volatile List<ChatMessage> chatHistory = List.of();

    private volatile String prStateFilter = "open";
    private volatile boolean assignedToMeFilter = false;
    private volatile boolean reviewRequestedFilter = false;

    private Consumer<PullRequest> onPRSelected = pr -> {};
    private Runnable onPageReady = () -> {};

    public WebviewPanel(Project project) {
        this.project = project;
        this.claudeService = new IntellijClaudeService(project.getBasePath());
        httpServer = tryStartServer();
        browser = new JBCefBrowser();
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
                                if (!frame.isMain()) {
                                    return;
                                }
                                injectBridge(cefBrowser);
                                getApplication().invokeLater(onPageReady);
                            }
                        },
                        browser.getCefBrowser());

        if (httpServer != null) {
            String url = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/";
            log.info("Loading webview from {}", url);
            browser.loadURL(url);
        } else {
            browser.loadHTML(
                    "<html><body style='color:#e8a030;background:#0a0805;"
                            + "font-family:monospace'>"
                            + "<p>Could not start webview server</p>"
                            + "</body></html>");
        }
    }

    private HttpServer tryStartServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", this::serveResource);
            s.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> s.stop(0)));
            log.debug("Webview HTTP server listening on port {}", s.getAddress().getPort());
            return s;
        } catch (IOException e) {
            log.error("Failed to start webview HTTP server", e);
            return null;
        }
    }

    private void serveResource(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        try (InputStream in = WebviewPanel.class.getResourceAsStream("/webview" + path)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", mimeFor(path));
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        }
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

    private void handleIncoming(String json) {
        try {
            var node = mapper.readTree(json);
            String type = node.path("type").asText();
            int number = node.path("number").asInt();
            String owner = node.path("owner").asText();
            String repo = node.path("repo").asText();

            switch (type) {
                case "selectPR" -> handleSelectPR(number, owner, repo);
                case "refreshPRs" -> {
                    String state = node.path("state").asText("open");
                    prStateFilter = StringUtils.defaultIfBlank(state, "open");
                    assignedToMeFilter = node.path("assignedToMe").asBoolean(false);
                    reviewRequestedFilter = node.path("reviewRequested").asBoolean(false);
                    getApplication().invokeLater(onPageReady);
                }
                case "openUrl" -> {
                    String url = node.path("url").asText();
                    if (StringUtils.isNotBlank(url) && url.startsWith("https://")) {
                        getApplication().invokeLater(() -> BrowserUtil.browse(url));
                    }
                }
                case "generateReview" -> handleGenerateReview(number, owner, repo);
                case "cancelReview" -> claudeService.cancelCurrentRequest();
                case "saveDraft" -> {
                    ReviewResult bridgeResult = null;
                    try {
                        var resultNode = node.path("result");
                        if (!resultNode.isMissingNode()) {
                            bridgeResult = mapper.treeToValue(resultNode, ReviewResult.class);
                        }
                    } catch (Exception e) {
                        log.warn(
                                "saveDraft: failed to parse result from bridge: {}",
                                e.getMessage());
                    }
                    final ReviewResult finalResult = bridgeResult;
                    getApplication()
                            .executeOnPooledThread(
                                    () -> handleSaveDraft(number, owner, repo, finalResult));
                }
                case "submitReview" -> {
                    String verdict = node.path("verdict").asText();
                    String comment = node.path("comment").asText("");
                    getApplication()
                            .executeOnPooledThread(
                                    () ->
                                            handleSubmitReview(
                                                    number, owner, repo, verdict, comment));
                }
                case "deleteDraft" ->
                        getApplication()
                                .executeOnPooledThread(
                                        () -> handleDeleteDraft(number, owner, repo));
                case "clearChat" -> chatHistory = List.of();
                case "askClaude" -> {
                    String question = node.path("question").asText();
                    String context = node.path("context").asText("");
                    getApplication()
                            .executeOnPooledThread(() -> handleAskClaude(question, context));
                }
                default -> log.warn("Unknown bridge message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Bridge message error: {}", e.getMessage());
        }
    }

    // --- selectPR ---

    private void handleSelectPR(int number, String owner, String repo) {
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
            return;
        }

        activePR = pr;
        lastResult = null;
        pendingReviewId = null;
        prefetchedDiff = null;
        prefetchedExistingReviews = null;
        chatHistory = List.of();

        getApplication().invokeLater(() -> onPRSelected.accept(pr));
        pushMessage(new DraftLoadingMsg("draftLoading"));

        getApplication()
                .executeOnPooledThread(
                        () -> {
                            String token = PluginSettings.getInstance().getGithubToken();
                            if (StringUtils.isBlank(token)) {
                                pushMessage(
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                "NO_DRAFT",
                                                null,
                                                null,
                                                null,
                                                false,
                                                "No GitHub token configured."));
                                return;
                            }

                            // Check local index upfront (no network) so we can prefetch
                            // the current HEAD SHA in parallel if a staleness check is
                            // likely to be needed.
                            PendingReviewIndex.Entry localEntry =
                                    pendingIndex.list().stream()
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
                            CompletableFuture<String> headShaFuture =
                                    StringUtils.isNotBlank(savedHeadSha)
                                            ? CompletableFuture.supplyAsync(
                                                    () -> {
                                                        try {
                                                            return ghSvc.getPRHeadSha(
                                                                    token, owner, repo, number);
                                                        } catch (Exception ex) {
                                                            log.warn(
                                                                    "getPRHeadSha prefetch"
                                                                            + " failed: {}",
                                                                    ex.getMessage());
                                                            return "";
                                                        }
                                                    })
                                            : CompletableFuture.completedFuture("");

                            CompletableFuture<Boolean> mergedFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.isPRMerged(
                                                            token, owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "isPRMerged failed: {}",
                                                            e.getMessage());
                                                    return false;
                                                }
                                            });

                            CompletableFuture<GitHubService.PendingReview> pendingFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.loadDraftReview(
                                                            token, owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "loadDraftReview failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> diffFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getPRDiff(
                                                            token, owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDiff prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> reviewsFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getExistingReviewsSummary(
                                                            token, owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getExistingReviewsSummary prefetch"
                                                                    + " failed: {}",
                                                            e.getMessage());
                                                    return "";
                                                }
                                            });

                            boolean merged = mergedFuture.join();
                            GitHubService.PendingReview pending = pendingFuture.join();
                            prefetchedDiff = diffFuture.join();
                            prefetchedExistingReviews = reviewsFuture.join();
                            String currentHeadSha = headShaFuture.join();

                            // Delete stale draft on a merged PR, best-effort.
                            if (merged && pending != null) {
                                try {
                                    ghSvc.deleteDraftReview(
                                            token, owner, repo, number, pending.getId());
                                } catch (Exception e) {
                                    log.warn("deleteDraftReview failed: {}", e.getMessage());
                                }
                                pending = null;
                            }

                            if (merged) {
                                pushMessage(
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                "MERGED",
                                                null,
                                                null,
                                                null,
                                                false,
                                                "PR is merged."));
                                return;
                            }

                            if (pending != null) {
                                boolean stale =
                                        StringUtils.isNotBlank(savedHeadSha)
                                                && !savedHeadSha.equals(currentHeadSha);
                                ReviewResultDto dto = toDto(pending.getResult());
                                pushMessage(
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                "DRAFT_PRESENT",
                                                pending.getId(),
                                                dto,
                                                prefetchedDiff,
                                                stale,
                                                "Loaded pending draft review."));
                                pendingReviewId = pending.getId();
                                lastResult = pending.getResult();
                                return;
                            }

                            pushMessage(
                                    new DraftLoadedMsg(
                                            "draftLoaded",
                                            "NO_DRAFT",
                                            null,
                                            null,
                                            null,
                                            false,
                                            ""));
                        });
    }

    // --- generateReview ---

    private void handleGenerateReview(int number, String owner, String repo) {
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
            pushMessage(new ErrorMsg("reviewError", "PR not found."));
            return;
        }

        String token = PluginSettings.getInstance().getGithubToken();
        if (StringUtils.isBlank(token)) {
            pushMessage(new ErrorMsg("reviewError", "No GitHub token configured."));
            return;
        }

        // Dispatch all blocking work to a pooled thread so the JCEF bridge returns immediately
        // and status messages can flow during the network-fetch phase.
        final PullRequest finalPr = pr;
        final String finalToken = token;
        getApplication()
                .executeOnPooledThread(
                        () -> {
                            // Delete any existing draft before regenerating so GitHub doesn't
                            // accumulate multiple pending drafts on the same PR.
                            String existingReviewId = pendingReviewId;
                            if (StringUtils.isNotBlank(existingReviewId)) {
                                try {
                                    ghSvc.deleteDraftReview(
                                            finalToken, owner, repo, number, existingReviewId);
                                    pendingIndex.remove(owner, repo, number);
                                    pendingReviewId = null;
                                } catch (Exception e) {
                                    log.warn(
                                            "pre-regen deleteDraftReview failed: {}",
                                            e.getMessage());
                                }
                            }

                            // Reuse prefetched diff; fall back to live fetch only if stale.
                            String diff;
                            if (activePR == finalPr && StringUtils.isNotBlank(prefetchedDiff)) {
                                diff = prefetchedDiff;
                            } else {
                                pushMessage(
                                        new ReviewGeneratingMsg(
                                                "reviewGenerating", "Fetching diff…"));
                                try {
                                    diff = ghSvc.getPRDiff(finalToken, owner, repo, number);
                                } catch (Exception e) {
                                    pushMessage(
                                            new ErrorMsg(
                                                    "reviewError",
                                                    "Failed to fetch diff: " + e.getMessage()));
                                    return;
                                }
                            }

                            // Reuse prefetched existing reviews; fall back to live fetch only if
                            // stale.
                            String existingReviews;
                            if (activePR == finalPr && prefetchedExistingReviews != null) {
                                existingReviews = prefetchedExistingReviews;
                            } else {
                                try {
                                    existingReviews =
                                            ghSvc.getExistingReviewsSummary(
                                                    finalToken, owner, repo, number);
                                } catch (Exception e) {
                                    log.warn(
                                            "getExistingReviewsSummary failed: {}", e.getMessage());
                                    existingReviews = "";
                                }
                            }

                            // Start Claude — callbacks fired on EDT
                            pushMessage(
                                    new ReviewGeneratingMsg(
                                            "reviewGenerating", "Sending to Claude…"));
                            final String finalDiff = diff;
                            final String finalExisting = existingReviews;
                            String typeContext =
                                    DiffTypeContextExtractor.extract(finalDiff, project);
                            String knownPatterns =
                                    patternKb.load(finalPr.getOwner(), finalPr.getRepo());
                            claudeService.reviewPR(
                                    new PRReviewRequest(
                                            finalPr,
                                            "",
                                            knownPatterns,
                                            "",
                                            finalExisting,
                                            typeContext),
                                    statusMsg ->
                                            pushMessage(
                                                    new ReviewGeneratingMsg(
                                                            "reviewGenerating", statusMsg)),
                                    (kind, chunk) ->
                                            pushMessage(
                                                    new ReviewChunkMsg("reviewChunk", kind, chunk)),
                                    result -> {
                                        lastResult = result;
                                        pendingReviewId = null;
                                        pushMessage(
                                                new ReviewResultMsg(
                                                        "reviewResult", toDto(result), finalDiff));
                                    },
                                    err -> pushMessage(new ErrorMsg("reviewError", err)));
                        });
    }

    // --- saveDraft ---

    private void handleSaveDraft(int number, String owner, String repo, ReviewResult bridgeResult) {
        ReviewResult result = bridgeResult != null ? bridgeResult : lastResult;
        if (result == null) {
            pushMessage(new ErrorMsg("draftSaveError", "No review result to save."));
            return;
        }
        lastResult = result;

        String token = PluginSettings.getInstance().getGithubToken();
        if (StringUtils.isBlank(token)) {
            pushMessage(new ErrorMsg("draftSaveError", "No GitHub token configured."));
            return;
        }

        GitHubService.SaveDraftResult saved;
        try {
            saved = ghSvc.saveDraftReview(token, owner, repo, number, result);
        } catch (Exception e) {
            pushMessage(new ErrorMsg("draftSaveError", "Save failed: " + e.getMessage()));
            return;
        }

        String headSha = "";
        try {
            headSha = ghSvc.getPRHeadSha(token, owner, repo, number);
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
        pendingIndex.add(owner, repo, number, title, headSha);
        pendingReviewId = saved.getReviewId();

        pushMessage(
                new DraftSavedMsg("draftSaved", saved.getReviewId(), saved.getCommentsDropped()));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, true));
    }

    // --- submitReview ---

    private void handleSubmitReview(
            int number, String owner, String repo, String verdict, String comment) {
        String reviewId = pendingReviewId;
        if (StringUtils.isBlank(reviewId)) {
            pushMessage(new ErrorMsg("reviewSubmitError", "No pending draft review to submit."));
            return;
        }

        String token = PluginSettings.getInstance().getGithubToken();
        if (StringUtils.isBlank(token)) {
            pushMessage(new ErrorMsg("reviewSubmitError", "No GitHub token configured."));
            return;
        }

        try {
            ghSvc.submitDraftReview(token, owner, repo, number, reviewId, verdict, comment);
        } catch (Exception e) {
            pushMessage(new ErrorMsg("reviewSubmitError", "Submit failed: " + e.getMessage()));
            return;
        }

        pendingIndex.remove(owner, repo, number);
        lastResult = null;
        pendingReviewId = null;

        pushMessage(new SimpleMsg("reviewSubmitted"));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    // --- deleteDraft ---

    private void handleDeleteDraft(int number, String owner, String repo) {
        String reviewId = pendingReviewId;
        if (StringUtils.isBlank(reviewId)) {
            pushMessage(new ErrorMsg("draftDeleteError", "No pending draft review to delete."));
            return;
        }

        String token = PluginSettings.getInstance().getGithubToken();
        if (StringUtils.isBlank(token)) {
            pushMessage(new ErrorMsg("draftDeleteError", "No GitHub token configured."));
            return;
        }

        try {
            ghSvc.deleteDraftReview(token, owner, repo, number, reviewId);
        } catch (Exception e) {
            pushMessage(new ErrorMsg("draftDeleteError", "Delete failed: " + e.getMessage()));
            return;
        }

        pendingIndex.remove(owner, repo, number);
        lastResult = null;
        pendingReviewId = null;

        pushMessage(new SimpleMsg("draftDeleted"));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    // --- askClaude ---

    private void handleAskClaude(String question, String context) {
        if (StringUtils.isBlank(question)) {
            return;
        }

        PullRequest pr = activePR;
        if (pr == null) {
            pushMessage(new ErrorMsg("chatError", "No PR selected."));
            return;
        }

        String prContext = buildPrContext(pr);
        List<ChatMessage> history = chatHistory;

        // When the user has selected a code snippet, prepend it so Claude can reference it.
        String fullQuestion =
                StringUtils.isBlank(context)
                        ? question
                        : "<selection_context>\n"
                                + context
                                + "\n</selection_context>\n\n"
                                + question;

        claudeService.chat(
                prContext,
                history,
                fullQuestion,
                chunk -> pushMessage(new ChatChunkMsg("chatChunk", chunk)),
                response -> {
                    List<ChatMessage> updated = new ArrayList<>(history);
                    updated.add(new ChatMessage(ChatMessage.Role.USER, fullQuestion));
                    updated.add(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
                    chatHistory = updated;
                    pushMessage(new ChatResponseMsg("chatResponse", response));
                },
                err -> pushMessage(new ErrorMsg("chatError", err)));
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
     * __handleMessage}.
     */
    private void pushMessage(Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            String escaped = escapeForJsString(json);
            browser.getCefBrowser()
                    .executeJavaScript(
                            "if(window.__handleMessage){window.__handleMessage('"
                                    + escaped
                                    + "');}",
                            browser.getCefBrowser().getURL(),
                            0);
        } catch (JsonProcessingException e) {
            log.warn("pushMessage serialization failed: {}", e.getMessage());
        }
    }

    private static ReviewResultDto toDto(ReviewResult r) {
        if (r == null) {
            return null;
        }
        List<LineCommentDto> comments =
                r.getLineComments().stream()
                        .map(
                                c ->
                                        new LineCommentDto(
                                                c.getFile(), c.getLine(), c.getType(), c.getBody()))
                        .toList();
        return new ReviewResultDto(r.getSummary(), r.getVerdict(), comments);
    }

    private static String escapeForJsString(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /** Pushes the PR list into the webview via the bridge. Call from the EDT. */
    public void loadPRs(List<PullRequest> prs, String defaultRepo) {
        cachedPRs = prs;
        Set<String> draftKeys =
                pendingIndex.list().stream()
                        .map(e -> e.owner() + "/" + e.repo() + "#" + e.number())
                        .collect(java.util.stream.Collectors.toSet());
        List<WebviewPr> dtos =
                prs.stream()
                        .map(
                                pr ->
                                        new WebviewPr(
                                                pr.getNumber(),
                                                pr.getTitle(),
                                                pr.getOwner(),
                                                pr.getRepo(),
                                                pr.getAuthor(),
                                                pr.getCreatedAt(),
                                                pr.getHtmlUrl(),
                                                draftKeys.contains(
                                                        pr.getOwner()
                                                                + "/"
                                                                + pr.getRepo()
                                                                + "#"
                                                                + pr.getNumber())))
                        .toList();
        pushMessage(new PrListMessage("prListLoaded", dtos, defaultRepo));
    }

    public void setOnPRSelected(Consumer<PullRequest> callback) {
        this.onPRSelected = callback;
    }

    public void setOnPageReady(Runnable callback) {
        this.onPageReady = callback;
    }

    public String getPrStateFilter() {
        return prStateFilter;
    }

    public boolean isAssignedToMeFilter() {
        return assignedToMeFilter;
    }

    public boolean isReviewRequestedFilter() {
        return reviewRequestedFilter;
    }

    public void reload() {
        browser.getCefBrowser().reloadIgnoreCache();
    }

    public JComponent getComponent() {
        return browser.getComponent();
    }
}
