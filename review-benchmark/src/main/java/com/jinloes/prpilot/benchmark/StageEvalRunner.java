package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewStageReplay;
import com.jinloes.prpilot.review.ReviewStageReplay.RecordedStages;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Stage-isolated golden evaluation harness for PR Pilot's review pipeline.
 *
 * <p>Replays curated pull-request diffs and frozen primary-pass JSON into the engine's real
 * validation, rule-selection, hygiene, CI-suppression, and suggestion-guard stages:
 *
 * <ul>
 *   <li><b>Deterministic mode (default):</b> uses recorded stage outputs for zero-cost, sub-second
 *       regression checks (also run automatically in {@code :review-benchmark:test}).
 *   <li><b>Live downstream mode ({@code --live}):</b> replays frozen primary JSON in 0ms and runs
 *       only the single-turn downstream stages (critique, rule selection/agents, hygiene) against
 *       the configured live provider ({@code copilot} or {@code claude}), measuring prompt/rule
 *       changes in seconds without 40-turn worktree exploration loops.
 * </ul>
 */
public final class StageEvalRunner {

    static final String DEFAULT_CORPUS_RESOURCE = "/stage-eval/cases.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String USAGE =
            "Usage: ./gradlew :review-benchmark:stageEval --args=\"[options]\"\n"
                    + "  --corpus <path>             Optional path to custom cases.json\n"
                    + "  --only <case-id>            Run only matching case ID (repeatable)\n"
                    + "  --live                      Run downstream stages (critique/rules/hygiene) on live CLI\n"
                    + "  --provider copilot|claude|antigravity\n"
                    + "                              Live provider when --live is set (default: copilot)\n"
                    + "  --model <id>                Optional live model override\n"
                    + "  --effort low|medium|high    Copilot reasoning effort for --live (default: medium)\n"
                    + "  --json                      Emit full JSON report to stdout\n"
                    + "  --help                      Show this help";

    public record Options(
            Path corpusPath,
            Set<String> onlyIds,
            boolean live,
            ReviewProvider provider,
            String model,
            String effort,
            boolean json,
            boolean help) {}

    public record CiAnnotationSpec(String file, int line, String level, String message) {}

    public record EvalCase(
            String id,
            String title,
            String description,
            String diff,
            boolean selfCritique,
            boolean supervisorEnabled,
            boolean builtInRules,
            List<CiAnnotationSpec> ciAnnotations,
            JsonNode primaryResponse,
            JsonNode critiqueResponse,
            JsonNode hygieneResponse,
            JsonNode ruleSelectionResponse,
            Map<String, JsonNode> ruleAgentResponses,
            String expectedVerdict,
            List<String> expectedKeptAnchors,
            List<String> expectedDroppedAnchors,
            List<String> expectedRuleTags,
            Map<String, String> expectedSuggestedChanges) {}

    public record CaseOutcome(
            String id,
            boolean passed,
            String actualVerdict,
            List<String> actualAnchors,
            int keptHits,
            int keptTotal,
            int droppedHits,
            int droppedTotal,
            long elapsedMs,
            List<String> failures) {}

    public record SuiteSummary(
            String mode,
            int totalCases,
            int passedCases,
            int keptHits,
            int keptTotal,
            double truePositiveRecall,
            int droppedHits,
            int droppedTotal,
            double falsePositiveSuppressionRate,
            long totalElapsedMs,
            List<CaseOutcome> cases) {
        public boolean allPassed() {
            return passedCases == totalCases;
        }
    }

    @FunctionalInterface
    interface CaseExecutor {
        ReviewResult execute(EvalCase evalCase, PRReviewRequest request, RecordedStages stages)
                throws Exception;
    }

    private StageEvalRunner() {}

    public static void main(String[] args) throws Exception {
        int code = runCli(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int runCli(String[] args, PrintStream out, PrintStream err) throws Exception {
        Options options;
        try {
            options = parseArgs(args);
        } catch (IllegalArgumentException badArgs) {
            err.println(badArgs.getMessage());
            err.println(USAGE);
            return 2;
        }
        if (options.help()) {
            out.println(USAGE);
            return 0;
        }

        List<EvalCase> corpus =
                options.corpusPath() != null
                        ? loadCorpus(options.corpusPath())
                        : loadBundledCorpus();
        if (!options.onlyIds().isEmpty()) {
            corpus = corpus.stream().filter(c -> options.onlyIds().contains(c.id())).toList();
            if (corpus.isEmpty()) {
                err.println("No cases matched --only " + options.onlyIds());
                return 2;
            }
        }

        CaseExecutor executor =
                options.live()
                        ? (evalCase, request, stages) ->
                                ReviewStageReplay.replayWithLiveDownstreamStages(
                                        request,
                                        stages.primaryRawJson(),
                                        options.provider(),
                                        options.model(),
                                        options.effort(),
                                        null,
                                        evalCase.selfCritique(),
                                        ignored -> {})
                        : (evalCase, request, stages) ->
                                ReviewStageReplay.replayDeterministic(
                                        request,
                                        stages,
                                        evalCase.selfCritique(),
                                        evalCase.supervisorEnabled(),
                                        ignored -> {});

        SuiteSummary summary =
                evaluateSuite(
                        options.live() ? "live-" + options.provider().getId() : "deterministic",
                        corpus,
                        executor);

        if (options.json()) {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        } else {
            renderHumanSummary(summary, out);
        }
        return summary.allPassed() ? 0 : 1;
    }

    static Options parseArgs(String[] args) {
        Path corpusPath = null;
        Set<String> onlyIds = new LinkedHashSet<>();
        boolean live = false;
        ReviewProvider provider = ReviewProvider.COPILOT;
        String model = "";
        String effort = "medium";
        boolean json = false;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> help = true;
                case "--live" -> live = true;
                case "--json" -> json = true;
                case "--corpus" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing path after --corpus");
                    }
                    corpusPath = Path.of(args[++i]);
                }
                case "--only" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing case ID after --only");
                    }
                    onlyIds.add(args[++i].trim());
                }
                case "--provider" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value after --provider");
                    }
                    String p = args[++i].trim().toLowerCase(Locale.ROOT);
                    if (!"copilot".equals(p) && !"claude".equals(p) && !"antigravity".equals(p)) {
                        throw new IllegalArgumentException("Unsupported --provider: " + p);
                    }
                    provider = ReviewProvider.fromId(p);
                }
                case "--model" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value after --model");
                    }
                    model = args[++i].trim();
                }
                case "--effort" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value after --effort");
                    }
                    effort = args[++i].trim().toLowerCase(Locale.ROOT);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return new Options(
                corpusPath, Set.copyOf(onlyIds), live, provider, model, effort, json, help);
    }

    public static List<EvalCase> loadBundledCorpus() throws IOException {
        try (InputStream in = StageEvalRunner.class.getResourceAsStream(DEFAULT_CORPUS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing bundled corpus resource: " + DEFAULT_CORPUS_RESOURCE);
            }
            return JSON.readValue(in, new TypeReference<List<EvalCase>>() {});
        }
    }

    public static List<EvalCase> loadCorpus(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return JSON.readValue(in, new TypeReference<List<EvalCase>>() {});
        }
    }

    public static SuiteSummary runDeterministicDefaultCorpus() throws Exception {
        return evaluateSuite(
                "deterministic",
                loadBundledCorpus(),
                (evalCase, request, stages) ->
                        ReviewStageReplay.replayDeterministic(
                                request,
                                stages,
                                evalCase.selfCritique(),
                                evalCase.supervisorEnabled(),
                                ignored -> {}));
    }

    static SuiteSummary evaluateSuite(String mode, List<EvalCase> corpus, CaseExecutor executor)
            throws Exception {
        long suiteStart = System.nanoTime();
        List<CaseOutcome> outcomes = new ArrayList<>(corpus.size());
        int passedCount = 0;
        int totalKeptHits = 0;
        int totalKeptExpected = 0;
        int totalDroppedHits = 0;
        int totalDroppedExpected = 0;

        for (int index = 0; index < corpus.size(); index++) {
            EvalCase evalCase = corpus.get(index);
            CaseOutcome outcome = evaluateCase(index + 1, evalCase, executor);
            outcomes.add(outcome);
            if (outcome.passed()) {
                passedCount++;
            }
            totalKeptHits += outcome.keptHits();
            totalKeptExpected += outcome.keptTotal();
            totalDroppedHits += outcome.droppedHits();
            totalDroppedExpected += outcome.droppedTotal();
        }

        double recall = totalKeptExpected == 0 ? 1.0 : (double) totalKeptHits / totalKeptExpected;
        double suppression =
                totalDroppedExpected == 0 ? 1.0 : (double) totalDroppedHits / totalDroppedExpected;
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - suiteStart);
        return new SuiteSummary(
                mode,
                corpus.size(),
                passedCount,
                totalKeptHits,
                totalKeptExpected,
                recall,
                totalDroppedHits,
                totalDroppedExpected,
                suppression,
                elapsedMs,
                List.copyOf(outcomes));
    }

    private static CaseOutcome evaluateCase(int prNumber, EvalCase evalCase, CaseExecutor executor)
            throws Exception {
        long started = System.nanoTime();
        PullRequest pr =
                new PullRequest(
                        StringUtils.defaultString(evalCase.title()),
                        "https://github.com/acme/widgets/pull/" + prNumber,
                        "acme",
                        "widgets",
                        prNumber,
                        StringUtils.defaultString(evalCase.description()),
                        "author",
                        "2026-11-19T00:00:00Z",
                        false);
        List<CiAnnotation> ci =
                evalCase.ciAnnotations() == null
                        ? List.of()
                        : evalCase.ciAnnotations().stream()
                                .map(
                                        a ->
                                                new CiAnnotation(
                                                        a.file(), a.line(), a.level(), a.message()))
                                .toList();
        PRReviewRequest request =
                PRReviewRequest.builder(pr, evalCase.diff())
                        .builtInRules(evalCase.builtInRules())
                        .ciAnnotations(ci)
                        .build();

        RecordedStages stages = toRecordedStages(evalCase);
        ReviewResult result = executor.execute(evalCase, request, stages);

        List<String> actualAnchors =
                result.getLineComments().stream()
                        .map(c -> c.getFile() + ":" + c.getLine())
                        .toList();
        Set<String> actualAnchorSet = new LinkedHashSet<>(actualAnchors);

        List<String> failures = new ArrayList<>();
        if (StringUtils.isNotBlank(evalCase.expectedVerdict())
                && !Objects.equals(evalCase.expectedVerdict(), result.getVerdict())) {
            failures.add(
                    "expected verdict "
                            + evalCase.expectedVerdict()
                            + " but got "
                            + result.getVerdict());
        }

        List<String> expectedKept =
                evalCase.expectedKeptAnchors() == null ? List.of() : evalCase.expectedKeptAnchors();
        int keptHits = 0;
        for (String expected : expectedKept) {
            if (actualAnchorSet.contains(expected)) {
                keptHits++;
            } else {
                failures.add("missing expected finding at " + expected);
            }
        }

        List<String> expectedDropped =
                evalCase.expectedDroppedAnchors() == null
                        ? List.of()
                        : evalCase.expectedDroppedAnchors();
        int droppedHits = 0;
        for (String forbidden : expectedDropped) {
            if (!actualAnchorSet.contains(forbidden)) {
                droppedHits++;
            } else {
                failures.add("expected finding at " + forbidden + " to be dropped");
            }
        }

        if (evalCase.expectedRuleTags() != null) {
            String allRationales =
                    result.getLineComments().stream()
                            .map(LineComment::getRationale)
                            .collect(Collectors.joining(" | "));
            for (String tag : evalCase.expectedRuleTags()) {
                if (!allRationales.contains(tag)) {
                    failures.add("missing expected rule rationale tag '" + tag + "'");
                }
            }
        }

        if (evalCase.expectedSuggestedChanges() != null) {
            for (Map.Entry<String, String> entry : evalCase.expectedSuggestedChanges().entrySet()) {
                String anchor = entry.getKey();
                LineComment matching =
                        result.getLineComments().stream()
                                .filter(c -> (c.getFile() + ":" + c.getLine()).equals(anchor))
                                .findFirst()
                                .orElse(null);
                if (matching == null) {
                    failures.add("cannot verify suggestedChange on missing finding " + anchor);
                } else if (!Objects.equals(entry.getValue(), matching.getSuggestedChange())) {
                    failures.add(
                            "expected suggestedChange on "
                                    + anchor
                                    + " to be '"
                                    + entry.getValue()
                                    + "' but got '"
                                    + matching.getSuggestedChange()
                                    + "'");
                }
            }
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new CaseOutcome(
                evalCase.id(),
                failures.isEmpty(),
                result.getVerdict(),
                actualAnchors,
                keptHits,
                expectedKept.size(),
                droppedHits,
                expectedDropped.size(),
                elapsedMs,
                List.copyOf(failures));
    }

    private static RecordedStages toRecordedStages(EvalCase evalCase) throws IOException {
        Map<String, String> ruleResponses = new LinkedHashMap<>();
        if (evalCase.ruleAgentResponses() != null) {
            for (Map.Entry<String, JsonNode> entry : evalCase.ruleAgentResponses().entrySet()) {
                String serialized = renderRawStageNode(entry.getValue());
                if (serialized != null) {
                    ruleResponses.put(entry.getKey(), serialized);
                }
            }
        }
        String primary = Objects.requireNonNull(renderRawStageNode(evalCase.primaryResponse()));
        return new RecordedStages(
                primary,
                renderRawStageNode(evalCase.critiqueResponse()),
                renderRawStageNode(evalCase.hygieneResponse()),
                renderRawStageNode(evalCase.ruleSelectionResponse()),
                ruleResponses);
    }

    private static String renderRawStageNode(JsonNode node) throws IOException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        // String nodes allow intentional raw/markdown-fenced model output fixtures.
        return node.isTextual() ? node.textValue() : JSON.writeValueAsString(node);
    }

    private static void renderHumanSummary(SuiteSummary summary, PrintStream out) {
        for (CaseOutcome c : summary.cases()) {
            if (c.passed()) {
                out.printf(
                        "ok   %-36s verdict=%-15s anchors=%s (%dms)%n",
                        c.id(), c.actualVerdict(), c.actualAnchors(), c.elapsedMs());
            } else {
                out.printf(
                        "FAIL %-36s verdict=%-15s failures=%s (%dms)%n",
                        c.id(), c.actualVerdict(), c.failures(), c.elapsedMs());
            }
        }
        out.printf(
                "%nStage eval (%s): %d/%d cases passed | TP recall %d/%d (%.1f%%) | FP suppression %d/%d (%.1f%%) | %dms%n",
                summary.mode(),
                summary.passedCases(),
                summary.totalCases(),
                summary.keptHits(),
                summary.keptTotal(),
                summary.truePositiveRecall() * 100.0,
                summary.droppedHits(),
                summary.droppedTotal(),
                summary.falsePositiveSuppressionRate() * 100.0,
                summary.totalElapsedMs());
    }
}
