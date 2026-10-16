package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.review.SemanticReviewService;
import com.jinloes.prpilot.review.SemanticWorktreeStore;
import com.jinloes.prpilot.services.IntellijGitHubService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link WebviewPanel} for PR acme/widget#42 whose deep-review IO, job executor, and outgoing
 * message sink are in-memory fakes; {@link #drain()} runs the queued jobs on the test thread.
 */
final class WebviewPanelFixture implements DeepReviewController.DeepReviewIo {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
