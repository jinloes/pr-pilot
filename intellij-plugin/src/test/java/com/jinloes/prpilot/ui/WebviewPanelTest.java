package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.SemanticReviewService;
import com.jinloes.prpilot.review.SemanticWorktreeStore;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.services.IntellijGitHubService;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewPanelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class DeepBridgeCallbacks {
        @Test
        void actualBridgePreparesWithoutProviderAndConsumesContinueOnlyOnce() throws Exception {
            for (String provider : List.of("claude", "copilot")) {
                var f = new DeepFixture();
                f.settings = provider;
                f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
                assertThat(f.generations).isZero();
                f.drain();
                assertThat(f.generations).isZero();
                assertThat(f.messages.get(0).path("type").asText()).isEqualTo("deepReviewPrepared");
                f.send("continueDeepReview", "continue-1", f.continuation());
                f.drain();
                assertThat(f.generations).isEqualTo(1);
                int messages = f.messages.size();
                f.send("continueDeepReview", "continue-1", f.continuation());
                f.drain();
                assertThat(f.generations).isEqualTo(1);
                assertThat(f.messages).hasSize(messages);
            }
        }

        @Test
        void rejectsAssistedRequestWhileExperimentalSettingIsOff() throws Exception {
            var f = new DeepFixture();
            f.assistedEnabled = false;
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            assertThat(f.jobs).isEmpty();
            f.drain();
            assertThat(f.generations).isZero();
            assertThat(f.messages).hasSize(1);
            assertThat(f.messages.get(0).path("type").asText()).isEqualTo("reviewError");
            assertThat(f.messages.get(0).path("prKey").asText()).isEqualTo("acme/widget#42");
            assertThat(f.messages.get(0).path("message").asText())
                    .isEqualTo(
                            "IntelliJ-assisted review is disabled; enable it in PR Pilot"
                                    + " settings (experimental)");

            f.assistedEnabled = true;
            f.send("generateReview", "prepare-2", Map.of("intellijAssisted", true));
            f.drain();
            assertThat(f.messages.get(f.messages.size() - 1).path("type").asText())
                    .isEqualTo("deepReviewPrepared");
        }

        @Test
        void retriesTransientPreflightFailureWithNewOperationOnly() throws Exception {
            var f = new DeepFixture();
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            f.drain();
            f.failRefresh = true;
            f.send("continueDeepReview", "continue-1", f.continuation());
            f.drain();
            assertThat(f.generations).isZero();
            assertThat(f.messages.get(f.messages.size() - 1).path("type").asText())
                    .isEqualTo("reviewError");
            f.failRefresh = false;
            f.send("continueDeepReview", "continue-1", f.continuation());
            f.drain();
            assertThat(f.generations).isZero();
            f.send("continueDeepReview", "retry-2", f.continuation());
            f.drain();
            assertThat(f.generations).isEqualTo(1);
        }

        @Test
        void failedPreflightDoesNotRestoreInvalidatedPreparation() throws Exception {
            for (String change :
                    List.of("cancel", "settings", "selection", "disposed", "prepare")) {
                var f = new DeepFixture();
                f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
                f.drain();
                f.failRefresh = true;
                f.send("continueDeepReview", "continue-1", f.continuation());
                switch (change) {
                    case "cancel" -> f.send("cancelReview", "continue-1", Map.of());
                    case "settings" -> f.settings = "copilot";
                    case "selection" -> f.field("selectionRevision", 1L);
                    case "disposed" -> f.field("disposed", true);
                    case "prepare" ->
                            f.send("generateReview", "prepare-2", Map.of("intellijAssisted", true));
                    default -> throw new AssertionError(change);
                }
                f.drain();
                f.failRefresh = false;
                f.settings = "claude";
                f.field("selectionRevision", 0L);
                f.field("disposed", false);
                f.send("continueDeepReview", "retry-2", f.continuation());
                f.drain();
                assertThat(f.generations).as(change).isZero();
            }
        }

        @Test
        void confirmedHeadChangeDoesNotRestorePreparation() throws Exception {
            var f = new DeepFixture();
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            f.drain();
            f.head = "b".repeat(40);
            f.send("continueDeepReview", "continue-1", f.continuation());
            f.drain();
            f.head = "a".repeat(40);
            f.send("continueDeepReview", "retry-2", f.continuation());
            f.drain();
            assertThat(f.generations).isZero();
        }

        @Test
        void actualBridgeRejectsHeadSettingsSelectionAndDisposedChanges() throws Exception {
            for (String change : List.of("head", "settings", "selection", "disposed")) {
                var f = new DeepFixture();
                f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
                f.drain();
                f.send("continueDeepReview", "continue-1", f.continuation());
                switch (change) {
                    case "head" -> f.head = "b".repeat(40);
                    case "settings" -> f.settings = "copilot";
                    case "selection" -> f.field("selectionRevision", 1L);
                    case "disposed" -> f.field("disposed", true);
                    default -> throw new AssertionError(change);
                }
                f.drain();
                assertThat(f.generations).as(change).isZero();
                assertThat(f.removals).isEmpty();
            }
        }

        @Test
        void actualBridgeCancellationInvalidatesQueuedPreparationWithoutRemoval() throws Exception {
            var f = new DeepFixture();
            f.send("generateReview", "prepare-1", Map.of("intellijAssisted", true));
            f.send("cancelReview", "prepare-1", Map.of());
            f.drain();
            assertThat(f.messages).isEmpty();
            assertThat(f.generations).isZero();
            assertThat(f.removals).isEmpty();
        }

        @Test
        void actualBridgeMaintenanceNeedsNoPrAndRejectsUnconfirmedCleanup() throws Exception {
            var f = new DeepFixture();
            f.field("activePR", null);
            f.send("listDeepReviews", "list-1", Map.of());
            f.drain();
            assertThat(f.messages.get(0).path("type").asText()).isEqualTo("retainedDeepReviews");
            f.send(
                    "cleanupDeepReview",
                    "cleanup-1",
                    Map.of("retainedId", f.id, "projectClosed", false));
            f.drain();
            assertThat(f.removals).isEmpty();
            f.send(
                    "cleanupDeepReview",
                    "cleanup-2",
                    Map.of("retainedId", f.id, "projectClosed", true));
            f.drain();
            assertThat(f.removals).containsExactly(f.id);
            assertThat(f.generations).isZero();
        }
    }

    private static final class DeepFixture implements DeepReviewController.DeepReviewIo {
        final String id = "11111111-1111-4111-8111-111111111111";
        String head = "a".repeat(40);
        String settings = "claude";
        boolean assistedEnabled = true;
        boolean failRefresh;
        int generations;
        final List<Runnable> jobs = new ArrayList<>();
        final List<com.fasterxml.jackson.databind.JsonNode> messages = new ArrayList<>();
        final List<String> removals = new ArrayList<>();
        final WebviewPanel panel =
                new WebviewPanel(
                        new PullRequest("Fixture", "", "acme", "widget", 42, "", "", ""),
                        this,
                        jobs::add,
                        () -> settings,
                        message -> messages.add(MAPPER.valueToTree(message)),
                        () -> assistedEnabled);

        void field(String name, Object value) throws Exception {
            boolean chat = name.startsWith("chat") || name.startsWith("activeChat");
            var field = (chat ? PrChatController.class : WebviewPanel.class).getDeclaredField(name);
            field.setAccessible(true);
            field.set(chat ? panel.chatController() : panel, value);
        }

        void send(String type, String operationId, Map<String, Object> extra) throws Exception {
            var message =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", type)
                            .put("number", 42)
                            .put("owner", "acme")
                            .put("repo", "widget")
                            .put("operationId", operationId);
            extra.forEach((key, value) -> message.set(key, MAPPER.valueToTree(value)));
            panel.handleIncoming(MAPPER.writeValueAsString(message));
        }

        Map<String, Object> continuation() {
            return Map.of("retainedId", id, "server", "private");
        }

        void drain() {
            while (!jobs.isEmpty()) jobs.remove(0).run();
        }

        public SemanticReviewService.Preparation prepare(
                com.fasterxml.jackson.databind.JsonNode options,
                DeepReviewController.FreshDeepPr fresh) {
            assertThat(options.path("intellijAssisted").asBoolean()).isTrue();
            return new SemanticReviewService.Preparation(
                    id, fresh.head().sha(), "/fixture/deep", List.of("private"));
        }

        public DeepReviewController.FreshDeepPr fresh(int number, String owner, String repo)
                throws IOException {
            if (failRefresh) throw new IOException("Temporary GitHub refresh failure");
            return new DeepReviewController.FreshDeepPr(
                    new IntellijGitHubService.PRHeadInfo("fix", head, false, ""), "fresh-diff");
        }

        public void generate(
                DeepReviewController.DeepPending pending, String operation, String server) {
            assertThat(pending.diff()).isEqualTo("fresh-diff");
            assertThat(pending.settings()).isEqualTo(settings);
            assertThat(server).isEqualTo("private");
            generations++;
        }

        public List<SemanticWorktreeStore.Retained> list() {
            return List.of(
                    new SemanticWorktreeStore.Retained(id, "/fixture", "/fixture/deep", head, 1));
        }

        public void cleanup(String retainedId, boolean closed) {
            assertThat(closed).isTrue();
            removals.add(retainedId);
        }
    }

    @Nested
    class MessagePublication {

        @Test
        void disposalWinningTheLifecycleLockSuppressesTheBrowserCall() throws Exception {
            Object lifecycleLock = new Object();
            AtomicBoolean disposed = new AtomicBoolean();
            AtomicInteger browserCalls = new AtomicInteger();
            CountDownLatch readyToPublish = new CountDownLatch(1);
            CountDownLatch publish = new CountDownLatch(1);
            Thread publisher =
                    new Thread(
                            () -> {
                                readyToPublish.countDown();
                                try {
                                    publish.await();
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                WebviewPanel.publishIfActive(
                                        lifecycleLock,
                                        disposed::get,
                                        browserCalls::incrementAndGet);
                            });
            publisher.start();
            assertThat(readyToPublish.await(1, TimeUnit.SECONDS)).isTrue();

            synchronized (lifecycleLock) {
                disposed.set(true);
            }
            publish.countDown();
            publisher.join(TimeUnit.SECONDS.toMillis(1));

            assertThat(publisher.isAlive()).isFalse();
            assertThat(browserCalls).hasValue(0);
        }
    }

    @Nested
    class IsCurrentChat {

        private final PullRequest pr =
                new PullRequest("title", "", "acme", "platform", 7, "", "a", "");

        @Test
        void acceptsMatchingSelectionAndChatId() {
            assertThat(PrChatController.isCurrentChat(pr, 3, 9, "acme/platform#7", 3, 9)).isTrue();
        }

        @Test
        void rejectsOlderChatOrSelection() {
            assertThat(PrChatController.isCurrentChat(pr, 3, 10, "acme/platform#7", 3, 9))
                    .isFalse();
            assertThat(PrChatController.isCurrentChat(pr, 4, 9, "acme/platform#7", 3, 9)).isFalse();
        }
    }

    @Nested
    class OperationServiceOwnership {

        @Test
        void createsDistinctProviderOwnersForTheSameWorktree() {
            File worktree = new File("/tmp/pr-pilot-test-worktree");

            IntellijClaudeService first = WebviewPanel.serviceForWorktree(worktree);
            IntellijClaudeService second = WebviewPanel.serviceForWorktree(worktree);

            assertThat(second).isNotSameAs(first);
        }
    }

    @Nested
    class CanPersistDraft {
        @Test
        void activePrMayUseHostState() {
            assertThat(WebviewPanel.canPersistDraft(true, false)).isTrue();
        }

        @Test
        void outgoingPrRequiresExplicitResult() {
            assertThat(WebviewPanel.canPersistDraft(false, true)).isTrue();
            assertThat(WebviewPanel.canPersistDraft(false, false)).isFalse();
        }
    }

    @Nested
    class IncomingMessageValidation {

        @Test
        void acceptsKnownMessageWithValidPrIdentity() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"operationId\":\"review-1\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":\"diff --git a/a b/a\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isTrue();
        }

        @Test
        void rejectsInvalidOrOversizedReviewDiff() throws Exception {
            var nonText =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"generateReview\",\"operationId\":\"review-1\",\"number\":7,\"owner\":\"acme\",\"repo\":\"platform\",\"diff\":42}");
            var oversized =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "generateReview")
                            .put("operationId", "review-1")
                            .put("number", 7)
                            .put("owner", "acme")
                            .put("repo", "platform")
                            .put("diff", "x".repeat(1_100_001));

            assertThat(WebviewPanel.isValidIncomingMessage(nonText)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(oversized)).isFalse();
        }

        @Test
        void rejectsPrMessageWithoutOwnerRepoOrNumber() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"selectPR\",\"number\":0,\"owner\":\"\",\"repo\":\"\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void rejectsUnknownMessageType() throws Exception {
            var node = MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"surprise\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void acceptsRunAuthLoginMessage() throws Exception {
            var node = MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"runAuthLogin\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isTrue();
        }

        @Test
        void validatesWebviewLayoutChangedReason() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\",\"reason\":\"chat-panel\"}");
            var missing =
                    MAPPER.readTree("{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\"}");
            var nonText =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"webviewLayoutChanged\",\"reason\":42}");
            var oversized =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "webviewLayoutChanged")
                            .put("reason", "x".repeat(4_097));

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(missing)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(nonText)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(oversized)).isFalse();
        }

        @Test
        void rejectsMalformedNestedReview() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"result\":{"
                                    + "\"summary\":\"s\",\"verdict\":\"INVALID\",\"lineComments\":[]}}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void validatesGeneratedReviewBaseline() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"generatedResult\":{"
                                    + "\"summary\":\"generated\",\"verdict\":\"COMMENT\",\"lineComments\":[]}}");
            var invalid = (ObjectNode) valid.deepCopy();
            ((ObjectNode) invalid.path("generatedResult")).put("verdict", "INVALID");

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(invalid)).isFalse();
        }

        @Test
        void rejectsDraftSaveWithoutPositiveCorrelationId() throws Exception {
            var missing =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,"
                                    + "\"owner\":\"acme\",\"repo\":\"widget\"}");
            var zero = ((ObjectNode) missing.deepCopy()).put("saveId", 0);
            var valid = ((ObjectNode) missing.deepCopy()).put("saveId", 1);

            assertThat(WebviewPanel.isValidIncomingMessage(missing)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(zero)).isFalse();
            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
        }

        @Test
        void validatesRefreshCompatibilityBooleans() throws Exception {
            var valid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"refreshPRs\",\"assignedToMe\":true,\"reviewRequested\":false}");
            var invalid =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"refreshPRs\",\"assignedToMe\":\"yes\"}");

            assertThat(WebviewPanel.isValidIncomingMessage(valid)).isTrue();
            assertThat(WebviewPanel.isValidIncomingMessage(invalid)).isFalse();
        }

        @Test
        void rejectsOversizedPrIdentity() {
            var node =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "selectPR")
                            .put("number", 7)
                            .put("owner", "x".repeat(257))
                            .put("repo", "platform");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }

        @Test
        void rejectsInvalidRichCommentMetadata() throws Exception {
            var node =
                    MAPPER.readTree(
                            "{\"protocolVersion\":1,\"type\":\"saveDraft\",\"number\":7,\"saveId\":1,"
                                    + "\"owner\":\"acme\",\"repo\":\"platform\",\"result\":{"
                                    + "\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[{"
                                    + "\"file\":\"a.java\",\"line\":1,\"type\":\"note\",\"body\":\"b\","
                                    + "\"severity\":\"urgent\"}]}}");

            assertThat(WebviewPanel.isValidIncomingMessage(node)).isFalse();
        }
    }

    @Nested
    class DraftLoadedSerialization {

        @Test
        void includesBoundedAndValidationDiffsInNoDraftMessage() {
            var message =
                    new WebviewBridgeMessages.DraftLoadedMsg(
                            "draftLoaded",
                            "acme/platform#42",
                            "NO_DRAFT",
                            null,
                            null,
                            "bounded diff",
                            "full validation diff",
                            false,
                            false,
                            false,
                            "",
                            new WebviewBridgeMessages.ProviderReadinessDto("claude", true, "Ready"),
                            true,
                            "API PRs precede service PRs.");

            var json = MAPPER.valueToTree(message);

            assertThat(json.path("intellijAssistedEnabled").isBoolean()).isTrue();
            assertThat(json.path("intellijAssistedEnabled").asBoolean()).isTrue();
            assertThat(json.path("repositoryInstructions").asText())
                    .isEqualTo("API PRs precede service PRs.");

            assertThat(json.has("reviewId")).isFalse();
            assertThat(json.has("result")).isFalse();
            assertThat(json.path("diff").asText()).isEqualTo("bounded diff");
            assertThat(json.path("validationDiff").asText()).isEqualTo("full validation diff");
        }

        @Test
        void omitsAbsentOptionalFieldsFromMergedMessage() {
            var message =
                    new WebviewBridgeMessages.DraftLoadedMsg(
                            "draftLoaded",
                            "acme/platform#42",
                            "MERGED",
                            null,
                            null,
                            null,
                            null,
                            false,
                            false,
                            false,
                            "PR is merged.",
                            new WebviewBridgeMessages.ProviderReadinessDto(
                                    "copilot", true, "Ready"),
                            false,
                            null);

            var json = MAPPER.valueToTree(message);
            assertThat(json.has("repositoryInstructions")).isFalse();

            assertThat(json.path("intellijAssistedEnabled").isBoolean()).isTrue();
            assertThat(json.path("intellijAssistedEnabled").asBoolean()).isFalse();

            assertThat(json.has("reviewId")).isFalse();
            assertThat(json.has("result")).isFalse();
            assertThat(json.has("diff")).isFalse();
            assertThat(json.has("validationDiff")).isFalse();
        }
    }

    @Nested
    class PrListSerialization {

        @Test
        void carriesTheIntellijAssistedSettingForPrAgnosticUi() throws Exception {
            var f = new DeepFixture();
            for (boolean enabled : new boolean[] {false, true}) {
                f.assistedEnabled = enabled;

                var json =
                        MAPPER.valueToTree(
                                f.panel.prListMessage(List.of(), "acme/widget", null, null));

                assertThat(json.path("type").asText()).isEqualTo("prListLoaded");
                assertThat(json.path("intellijAssistedEnabled").isBoolean()).isTrue();
                assertThat(json.path("intellijAssistedEnabled").asBoolean()).isEqualTo(enabled);
            }
        }
    }

    @Nested
    class CancelChat {

        private final List<ReviewProvider> cancelled = new ArrayList<>();
        private final IntellijClaudeService chatService =
                new IntellijClaudeService() {
                    @Override
                    public void cancelCurrentRequest(ReviewProvider provider) {
                        cancelled.add(provider);
                    }
                };

        private Object field(DeepFixture f, String name) throws Exception {
            var field = PrChatController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(f.panel.chatController());
        }

        private DeepFixture fixtureWithActiveChat(String operationId) throws Exception {
            var f = new DeepFixture();
            f.field(
                    "chatHistory",
                    List.of(
                            new ChatMessage(ChatMessage.Role.USER, "Q"),
                            new ChatMessage(ChatMessage.Role.ASSISTANT, "A")));
            f.field("activeChatOperationId", operationId);
            f.field("activeChatService", chatService);
            f.field("activeChatProvider", ReviewProvider.COPILOT);
            f.field("activeChatId", 7L);
            return f;
        }

        @Test
        void stopsTheOwningAnswerAndKeepsConversationHistory() throws Exception {
            var f = fixtureWithActiveChat("chat-1");
            Object history = field(f, "chatHistory");

            f.send("cancelChat", "chat-1", Map.of());

            assertThat(cancelled).containsExactly(ReviewProvider.COPILOT);
            assertThat(field(f, "chatHistory")).isSameAs(history);
            assertThat(field(f, "activeChatOperationId")).isNull();
            assertThat((long) field(f, "activeChatId")).isNotEqualTo(7L);
            assertThat(f.messages).isEmpty();
        }

        @Test
        void ignoresAStopForAnOperationItDoesNotOwn() throws Exception {
            var f = fixtureWithActiveChat("chat-1");

            f.send("cancelChat", "other", Map.of());

            assertThat(cancelled).isEmpty();
            assertThat(field(f, "activeChatOperationId")).isEqualTo("chat-1");
            assertThat((long) field(f, "activeChatId")).isEqualTo(7L);
        }

        @Test
        void validatorAcceptsCancelChatOnlyWithAnOperationId() {
            ObjectNode valid =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "cancelChat")
                            .put("operationId", "chat-1");
            assertThat(BridgeMessageValidator.isValid(valid)).isTrue();
            ObjectNode missing = valid.deepCopy();
            missing.remove("operationId");
            assertThat(BridgeMessageValidator.isValid(missing)).isFalse();
            ObjectNode blank = valid.deepCopy().put("operationId", "");
            assertThat(BridgeMessageValidator.isValid(blank)).isFalse();
            ObjectNode tooLong = valid.deepCopy().put("operationId", "x".repeat(129));
            assertThat(BridgeMessageValidator.isValid(tooLong)).isFalse();
        }
    }

    @Nested
    class PendingReviewLoading {

        @Test
        void preservesServiceFailuresAsTypedFailures() {
            IOException failure = new IOException("network unavailable");

            WebviewPanel.PendingReviewLoad result =
                    WebviewPanel.loadPendingReview(
                            () -> {
                                throw failure;
                            });

            assertThat(result.status()).isEqualTo(WebviewPanel.PendingReviewLoadStatus.FAILED);
            assertThat(result.failure()).isSameAs(failure);
            assertThat(result.review()).isNull();

            var message =
                    MAPPER.valueToTree(
                            WebviewPanel.pendingReviewFailureMessage("acme/platform#42", result));
            assertThat(message.path("type").asText()).isEqualTo("reviewError");
            assertThat(message.path("prKey").asText()).isEqualTo("acme/platform#42");
            assertThat(message.has("prState")).isFalse();
            assertThat(message.toString()).doesNotContain("NO_DRAFT");
        }

        @Test
        void distinguishesARealMissingReviewFromFailure() {
            WebviewPanel.PendingReviewLoad result = WebviewPanel.loadPendingReview(() -> null);

            assertThat(result.status()).isEqualTo(WebviewPanel.PendingReviewLoadStatus.NONE);
            assertThat(result.failure()).isNull();
            assertThat(result.review()).isNull();
        }
    }

    @Nested
    class GenerationMetadata {

        @Test
        void capturesGenerationTimeProviderAndModel() {
            var metadata =
                    WebviewPanel.generationMetadata(
                            ReviewProvider.COPILOT, "claude-sonnet-4.6", true);

            assertThat(metadata.promptVersion()).endsWith("-supervisor-on");
            assertThat(metadata.provider()).isEqualTo("copilot");
            assertThat(metadata.model()).isEqualTo("claude-sonnet-4.6");
        }
    }

    @Nested
    class IsWebviewPage {

        private static final String SERVED = "http://127.0.0.1:55767/";

        @Test
        void acceptsTheServedPageAndItsSubpaths() {
            assertThat(WebviewPanel.isWebviewPage(SERVED, SERVED)).isTrue();
            assertThat(WebviewPanel.isWebviewPage(SERVED + "index.html#pr", SERVED)).isTrue();
        }

        @Test
        void rejectsThePlaceholderAndOtherOrigins() {
            assertThat(
                            WebviewPanel.isWebviewPage(
                                    "file:///jbcefbrowser/123#url=about:blank", SERVED))
                    .isFalse();
            assertThat(WebviewPanel.isWebviewPage("http://127.0.0.1:1/", SERVED)).isFalse();
            assertThat(WebviewPanel.isWebviewPage(null, SERVED)).isFalse();
        }

        @Test
        void rejectsEveryPageBeforeTheServerStarts() {
            assertThat(WebviewPanel.isWebviewPage(SERVED, null)).isFalse();
        }
    }

    @Nested
    class BrowserHostLayout {

        @Test
        void browserTracksSuccessiveToolWindowSizes() {
            JPanel parent = new JPanel(new BorderLayout());
            JPanel browser = new JPanel();
            JPanel host = WebviewPanel.createBrowserHostPanel(browser);
            parent.add(host, BorderLayout.CENTER);

            layoutAt(parent, host, 640, 160);
            assertThat(host.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 160));
            assertThat(browser.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 160));

            layoutAt(parent, host, 640, 800);
            assertThat(host.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 800));
            assertThat(browser.getBounds()).isEqualTo(new Rectangle(0, 0, 640, 800));
        }

        private static void layoutAt(JPanel parent, JPanel host, int width, int height) {
            parent.setSize(width, height);
            parent.doLayout();
            host.doLayout();
        }
    }
}
