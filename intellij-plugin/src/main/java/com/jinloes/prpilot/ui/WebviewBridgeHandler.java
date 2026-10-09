package com.jinloes.prpilot.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static com.jinloes.prpilot.ui.WebviewPrSupport.normalizeSearchScope;
import static com.jinloes.prpilot.ui.WebviewPrSupport.saveRepositoryInstructionsReply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.settings.PluginSettingsConfigurable;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatches validated messages received from the webview bridge. */
final class WebviewBridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(WebviewBridgeHandler.class);

    private final WebviewPanel panel;
    private final ObjectMapper mapper;

    WebviewBridgeHandler(WebviewPanel panel, ObjectMapper mapper) {
        this.panel = panel;
        this.mapper = mapper;
    }

    void handle(String json) {
        try {
            var node = mapper.readTree(json);
            if (!WebviewPanel.isValidIncomingMessage(node)) {
                log.warn("Invalid bridge message payload: {}", json);
                return;
            }
            String type = node.path("type").asText();
            int number = node.path("number").asInt();
            String owner = node.path("owner").asText();
            String repo = node.path("repo").asText();

            switch (type) {
                case "selectPR" -> panel.handleSelectPR(number, owner, repo);
                case "refreshPRs" -> {
                    String state = node.path("state").asText("open");
                    panel.prStateFilter = StringUtils.defaultIfBlank(state, "open");
                    String scope = node.path("searchScope").asText("");
                    if (StringUtils.isNotBlank(scope)) {
                        panel.searchScope = normalizeSearchScope(scope);
                    } else if (node.path("assignedToMe").asBoolean(false)) {
                        panel.searchScope = "assigned";
                    } else if (node.path("reviewRequested").asBoolean(false)) {
                        panel.searchScope = "reviewRequested";
                    }
                    getApplication().invokeLater(panel.onPageReady);
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
                                                                panel.project,
                                                                PluginSettingsConfigurable.class));
                case "runAuthLogin" ->
                        getApplication()
                                .invokeLater(
                                        () ->
                                                BrowserUtil.browse(
                                                        "https://cli.github.com/manual/gh_auth_login"));
                case "webviewLayoutChanged" -> panel.scheduleWebviewLayoutRepaint();
                case "generateReview" -> {
                    if (node.path("intellijAssisted").asBoolean(false)) {
                        panel.assistedReviews.prepare(node.deepCopy());
                    } else {
                        synchronized (panel) {
                            panel.assistedReviews.invalidateLocked();
                        }
                        panel.handleGenerateReview(
                                number,
                                owner,
                                repo,
                                node.path("diff").asText(""),
                                node.path("chunkedReview").asBoolean(false),
                                node.path("focusAreas").asText(""),
                                node.path("customInstructions").asText(""),
                                node.path("operationId").asText(),
                                null,
                                isIncrementalRequest(node));
                    }
                }
                case "continueDeepReview" -> panel.assistedReviews.continueReview(node.deepCopy());
                case "listDeepReviews", "cleanupDeepReview" ->
                        panel.assistedReviews.maintenance(node.deepCopy());
                case "cancelReview" -> panel.cancelActiveReview(node.path("operationId").asText());
                case "saveDraft" -> saveDraft(node, number, owner, repo);
                case "submitReview" -> {
                    String verdict = node.path("verdict").asText();
                    String comment = node.path("comment").asText("");
                    getApplication()
                            .executeOnPooledThread(
                                    () -> {
                                        synchronized (panel.draftMutationLock) {
                                            panel.handleSubmitReview(
                                                    number, owner, repo, verdict, comment);
                                        }
                                    });
                }
                case "deleteDraft" ->
                        getApplication()
                                .executeOnPooledThread(
                                        () -> {
                                            synchronized (panel.draftMutationLock) {
                                                panel.handleDeleteDraft(number, owner, repo);
                                            }
                                        });
                case "clearChat" -> panel.chats.clear(node.path("operationId").asText());
                case "cancelChat" -> panel.chats.cancel(node.path("operationId").asText());
                case "saveRepositoryInstructions" ->
                        panel.pushMessage(
                                saveRepositoryInstructionsReply(
                                        PluginSettings.getInstance(),
                                        number,
                                        owner,
                                        repo,
                                        node.path("instructions").asText("")));
                case "askClaude" -> {
                    String question = node.path("question").asText();
                    String context = node.path("context").asText("");
                    String operationId = node.path("operationId").asText();
                    getApplication()
                            .executeOnPooledThread(
                                    () -> panel.chats.ask(question, context, operationId));
                }
                default -> log.warn("Unknown bridge message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Bridge message error: {}", e.getMessage());
        }
    }

    private void saveDraft(JsonNode node, int number, String owner, String repo) {
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
                bridgeGeneratedResult = mapper.treeToValue(generatedResultNode, ReviewResult.class);
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
            log.warn("saveDraft: failed to parse review data from bridge: {}", e.getMessage());
        }
        final ReviewResult finalResult = bridgeResult;
        final ReviewResult finalGeneratedResult = bridgeGeneratedResult;
        final List<LineComment> finalOrphans = bridgeOrphans;
        getApplication()
                .executeOnPooledThread(
                        () -> {
                            synchronized (panel.draftMutationLock) {
                                panel.handleSaveDraft(
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

    /** Only a JSON {@code true} opts in; Jackson's lenient coercion would also accept "true". */
    static boolean isIncrementalRequest(JsonNode node) {
        JsonNode incremental = node.get("incremental");
        return incremental != null && incremental.isBoolean() && incremental.booleanValue();
    }
}
