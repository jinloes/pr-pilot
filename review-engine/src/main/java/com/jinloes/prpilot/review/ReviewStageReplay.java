package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Stage-level replay seam over {@link ReviewPipelineService} for fast evaluation suites.
 *
 * <p>Allows callers (such as {@code review-benchmark}'s {@code stageEval} suite) to replay frozen
 * primary-pass JSON into the real post-primary pipeline stages — either fully deterministically
 * from recorded stage outputs or in hybrid mode where only single-turn downstream stages (critique,
 * rule-trigger selection, hygiene) hit a live provider without a 40-turn worktree exploration loop.
 */
public final class ReviewStageReplay {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RULE_NAME_HEADER =
            Pattern.compile("<review_rule>(?:\\RThe rule to apply:\\R)?\\s*name:\\s*([^\\r\\n]+)");

    private ReviewStageReplay() {}

    /**
     * Frozen raw JSON responses for each pipeline stage. Blank/absent entries return empty JSON.
     */
    public record RecordedStages(
            String primaryRawJson,
            String critiqueRawJson,
            String hygieneRawJson,
            String ruleSelectionRawJson,
            Map<String, String> ruleAgentRawJson) {
        public RecordedStages {
            Objects.requireNonNull(primaryRawJson, "primaryRawJson");
            ruleAgentRawJson = ruleAgentRawJson == null ? Map.of() : Map.copyOf(ruleAgentRawJson);
        }
    }

    /** Runs the full post-primary pipeline deterministically using {@code stages}. */
    public static ReviewResult replayDeterministic(
            PRReviewRequest request,
            RecordedStages stages,
            boolean selfCritique,
            boolean supervisorEnabled,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(stages, "stages");
        Consumer<String> statusSink = onStatus != null ? onStatus : ignored -> {};
        ReviewProviderExecutor executor = new RecordedStageExecutor(stages);
        return new ReviewPipelineService(
                        executor, new ChunkedReviewService(), new ReviewCoverageAnalyzer())
                .review(request, false, selfCritique, supervisorEnabled, statusSink, null);
    }

    /**
     * Replays {@code frozenPrimaryRawJson} without calling a model for the primary pass, while
     * delegating downstream single-turn stages (critique, rule selection/agents, hygiene) to the
     * configured live {@code provider}.
     */
    public static ReviewResult replayWithLiveDownstreamStages(
            PRReviewRequest request,
            String frozenPrimaryRawJson,
            ReviewProvider provider,
            String model,
            String effort,
            File workingDir,
            boolean selfCritique,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(frozenPrimaryRawJson, "frozenPrimaryRawJson");
        Objects.requireNonNull(provider, "provider");
        String dir = workingDir != null ? workingDir.getAbsolutePath() : null;
        ReviewProviderExecutor liveDelegate =
                switch (provider) {
                    case COPILOT ->
                            new ReviewProviderExecutor.CopilotExecutor(
                                    new CopilotService(dir),
                                    StringUtils.defaultString(model),
                                    StringUtils.defaultIfBlank(
                                            effort, CopilotService.DEFAULT_REASONING_EFFORT),
                                    false,
                                    null);
                    case ANTIGRAVITY ->
                            new ReviewProviderExecutor.AntigravityExecutor(
                                    new AntigravityService(dir), StringUtils.defaultString(model));
                    case CLAUDE ->
                            new ReviewProviderExecutor.ClaudeExecutor(
                                    new ClaudeService(dir), StringUtils.defaultString(model));
                };
        ReviewProviderExecutor hybrid = new HybridStageExecutor(frozenPrimaryRawJson, liveDelegate);
        Consumer<String> statusSink = onStatus != null ? onStatus : ignored -> {};
        return new ReviewPipelineService(
                        hybrid, new ChunkedReviewService(), new ReviewCoverageAnalyzer())
                .review(request, false, selfCritique, false, statusSink, null);
    }

    static ReviewPassResult parseFrozenPrimary(PRReviewRequest request, String primaryRawJson)
            throws IOException {
        InspectionManifest manifest = InspectionManifest.fromDiff(request.getDiff());
        return ReviewPassParser.parse(
                primaryRawJson, manifest, null, ReviewResultParser.maxComments(request));
    }

    static String extractRuleName(String prompt) {
        Matcher matcher = RULE_NAME_HEADER.matcher(StringUtils.defaultString(prompt));
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static String emptyReviewJson(String verdict) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("summary", "");
        root.put("verdict", verdict);
        root.putArray("lineComments");
        return JSON.writeValueAsString(root);
    }

    private static String emptySelectionJson() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode triggered = root.putArray("triggered");
        // Keep empty array explicit via Jackson tree serialization.
        assert triggered.isEmpty();
        return JSON.writeValueAsString(root);
    }

    private static final class RecordedStageExecutor implements ReviewProviderExecutor {
        private final RecordedStages stages;

        private RecordedStageExecutor(RecordedStages stages) {
            this.stages = stages;
        }

        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException {
            return parseFrozenPrimary(request, stages.primaryRawJson());
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException {
            if (prompt.startsWith(ReviewCritiquePrompts.CRITIQUE_PREAMBLE)) {
                return StringUtils.isNotBlank(stages.critiqueRawJson())
                        ? stages.critiqueRawJson()
                        : stages.primaryRawJson();
            }
            if (prompt.startsWith(ReviewHygienePrompts.HYGIENE_PREAMBLE)) {
                return StringUtils.isNotBlank(stages.hygieneRawJson())
                        ? stages.hygieneRawJson()
                        : emptyReviewJson("APPROVE");
            }
            if (prompt.startsWith(ReviewRulePrompts.RULE_SELECTION_PREAMBLE)) {
                return StringUtils.isNotBlank(stages.ruleSelectionRawJson())
                        ? stages.ruleSelectionRawJson()
                        : emptySelectionJson();
            }
            if (prompt.startsWith(ReviewRulePrompts.RULE_REVIEW_PREAMBLE)) {
                String ruleName = extractRuleName(prompt);
                String configured = stages.ruleAgentRawJson().get(ruleName);
                return StringUtils.isNotBlank(configured) ? configured : emptyReviewJson("COMMENT");
            }
            return emptyReviewJson("COMMENT");
        }

        @Override
        public void checkCancelled() {
            // In-memory deterministic replay cannot block on external I/O.
        }

        @Override
        public String displayModel() {
            return "recorded-stage-fixture";
        }
    }

    private static final class HybridStageExecutor implements ReviewProviderExecutor {
        private final String frozenPrimaryRawJson;
        private final ReviewProviderExecutor liveDelegate;

        private HybridStageExecutor(
                String frozenPrimaryRawJson, ReviewProviderExecutor liveDelegate) {
            this.frozenPrimaryRawJson = frozenPrimaryRawJson;
            this.liveDelegate = liveDelegate;
        }

        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException {
            return parseFrozenPrimary(request, frozenPrimaryRawJson);
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException, InterruptedException {
            return liveDelegate.complete(prompt, timeoutMillis, allowReadTools, allowMcp, onStatus);
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            liveDelegate.checkCancelled();
        }

        @Override
        public File projectDir() {
            return liveDelegate.projectDir();
        }

        @Override
        public String displayModel() {
            return liveDelegate.displayModel();
        }

        @Override
        public Closeable openSessionScope() {
            return liveDelegate.openSessionScope();
        }

        @SuppressWarnings("unused")
        List<String> loadedBuiltInRules() {
            return LocalReviewRules.load(null, true).stream()
                    .map(LocalReviewRules.Rule::name)
                    .toList();
        }
    }
}
