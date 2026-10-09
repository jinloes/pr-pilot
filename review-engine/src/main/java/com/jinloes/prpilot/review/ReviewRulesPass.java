package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the reviewer's local review rules. One tool-free call selects the structured rules whose
 * trigger matches the PR (failing open to every rule); unstructured rules always run. Each selected
 * rule then runs as its own read-only agent, a few at a time, under one overall deadline. Status is
 * reported only from the calling thread because host status sinks are not thread-safe.
 */
final class ReviewRulesPass {
    private static final Logger log = LoggerFactory.getLogger(ReviewRulesPass.class);
    static final int MAX_RULES_PER_REVIEW = 25;
    static final int RULE_CONCURRENCY = 3;
    static final long RULE_PHASE_DEADLINE_MS = 12L * 60L * 1000L;
    static final long SELECTION_TIMEOUT_MS = 90_000;
    static final long RULE_TIMEOUT_MS = 10L * 60L * 1000L;
    static final int MAX_RATIONALE_CHARS = 200;
    private static final long POLL_MS = 250;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Consumer<String> NO_STATUS = ignored -> {};

    /** The pipeline's per-call authority check: cancellation plus, in deep review, pinned state. */
    interface AuthorityCheck {
        void check() throws IOException, InterruptedException;
    }

    private final ReviewPipelineService.ProviderExecutor provider;
    private final AuthorityCheck authority;
    private final long deadlineMillis;
    private final long pollMillis;

    ReviewRulesPass(ReviewPipelineService.ProviderExecutor provider, AuthorityCheck authority) {
        this(provider, authority, RULE_PHASE_DEADLINE_MS, POLL_MS);
    }

    ReviewRulesPass(
            ReviewPipelineService.ProviderExecutor provider,
            AuthorityCheck authority,
            long deadlineMillis,
            long pollMillis) {
        this.provider = provider;
        this.authority = authority;
        this.deadlineMillis = deadlineMillis;
        this.pollMillis = pollMillis;
    }

    /**
     * Selects and applies {@code rules}, returning their anchored findings in rule load order.
     *
     * @param request the full review request, used for selection
     * @param ruleRequest the request rule agents review; a chunked review passes the condensed
     *     index
     */
    ReviewResult run(
            List<LocalReviewRules.Rule> rules,
            PRReviewRequest request,
            PRReviewRequest ruleRequest,
            InspectionManifest manifest,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        if (rules.isEmpty()) return empty();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        ExecutorService executor =
                Executors.newFixedThreadPool(
                        RULE_CONCURRENCY,
                        runnable -> {
                            Thread thread = new Thread(runnable, "pr-pilot-review-rule");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            Set<String> triggered = select(rules, request, manifest, executor, deadline, onStatus);
            if (triggered == null) return empty();
            List<LocalReviewRules.Rule> selected =
                    rules.stream()
                            .filter(rule -> !rule.structured() || triggered.contains(rule.name()))
                            .toList();
            List<LocalReviewRules.Rule> applied =
                    selected.subList(0, Math.min(selected.size(), MAX_RULES_PER_REVIEW));
            onStatus.accept(
                    "Rules: "
                            + rules.size()
                            + " loaded, "
                            + applied.size()
                            + " selected"
                            + (applied.isEmpty() ? "" : " (" + names(applied) + ")"));
            if (selected.size() > applied.size()) {
                List<LocalReviewRules.Rule> skipped =
                        selected.subList(applied.size(), selected.size());
                onStatus.accept(
                        "Skipped "
                                + skipped.size()
                                + " rules over the "
                                + MAX_RULES_PER_REVIEW
                                + "-rule limit: "
                                + names(skipped));
            }
            return apply(applied, ruleRequest, manifest, executor, deadline, onStatus);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Returns the triggered structured rule names, every structured name when selection fails, or
     * null when the phase deadline expired during selection.
     */
    private Set<String> select(
            List<LocalReviewRules.Rule> rules,
            PRReviewRequest request,
            InspectionManifest manifest,
            ExecutorService executor,
            long deadline,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        List<LocalReviewRules.Rule> structured =
                rules.stream().filter(LocalReviewRules.Rule::structured).toList();
        if (structured.isEmpty()) return Set.of();
        Set<String> all = new LinkedHashSet<>();
        structured.forEach(rule -> all.add(rule.name()));
        authority.check();
        String prompt = ReviewPrompts.buildRuleSelectionPrompt(request, manifest, structured);
        Future<String> future =
                executor.submit(
                        () ->
                                provider.complete(
                                        prompt, SELECTION_TIMEOUT_MS, false, false, NO_STATUS));
        Set<String> triggered = null;
        try {
            while (!future.isDone()) {
                provider.checkCancelled();
                if (expired(deadline)) {
                    future.cancel(true);
                    onStatus.accept(deadlineStatus(names(rules)));
                    return null;
                }
                sleep(deadline);
            }
            triggered = parseSelection(future.get());
        } catch (ExecutionException failed) {
            rethrowInterruption(failed);
            log.warn("Rule selection failed; applying every rule", failed.getCause());
        } finally {
            future.cancel(true);
        }
        if (triggered == null) {
            onStatus.accept("Rule selection failed; applying all " + all.size() + " rules");
            return all;
        }
        triggered.retainAll(all);
        return triggered;
    }

    private ReviewResult apply(
            List<LocalReviewRules.Rule> rules,
            PRReviewRequest ruleRequest,
            InspectionManifest manifest,
            ExecutorService executor,
            long deadline,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        ReviewResult[] results = new ReviewResult[rules.size()];
        Map<Future<ReviewResult>, Integer> running = new LinkedHashMap<>();
        int next = 0;
        try {
            while (next < rules.size() || !running.isEmpty()) {
                while (next < rules.size()
                        && running.size() < RULE_CONCURRENCY
                        && !expired(deadline)) {
                    authority.check();
                    LocalReviewRules.Rule rule = rules.get(next);
                    running.put(
                            executor.submit(() -> applyRule(rule, ruleRequest, manifest)), next);
                    next++;
                }
                provider.checkCancelled();
                if (expired(deadline)) {
                    List<LocalReviewRules.Rule> skipped = new ArrayList<>();
                    running.forEach(
                            (future, index) -> {
                                future.cancel(true);
                                skipped.add(rules.get(index));
                            });
                    running.clear();
                    skipped.addAll(rules.subList(next, rules.size()));
                    onStatus.accept(deadlineStatus(names(skipped)));
                    break;
                }
                if (!collectFinished(rules, running, results, onStatus)) sleep(deadline);
            }
        } finally {
            running.keySet().forEach(future -> future.cancel(true));
        }
        List<LineComment> comments = new ArrayList<>();
        for (ReviewResult result : results) {
            if (result != null) comments.addAll(result.getLineComments());
        }
        return new ReviewResult("", "COMMENT", comments);
    }

    private boolean collectFinished(
            List<LocalReviewRules.Rule> rules,
            Map<Future<ReviewResult>, Integer> running,
            ReviewResult[] results,
            Consumer<String> onStatus)
            throws InterruptedException {
        boolean any = false;
        Iterator<Map.Entry<Future<ReviewResult>, Integer>> entries = running.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Future<ReviewResult>, Integer> entry = entries.next();
            if (!entry.getKey().isDone()) continue;
            entries.remove();
            any = true;
            String name = rules.get(entry.getValue()).name();
            try {
                ReviewResult result = entry.getKey().get();
                results[entry.getValue()] = result;
                onStatus.accept(
                        "Rule " + name + " found " + findings(result.getLineComments().size()));
            } catch (ExecutionException failed) {
                // A cancelled review interrupts its agents; that is not a rule failure.
                rethrowInterruption(failed);
                log.warn("Rule {} failed; continuing", name, failed.getCause());
                onStatus.accept("Rule " + name + " failed; continuing");
            }
        }
        return any;
    }

    /**
     * Propagates review cancellation or a provider interruption instead of treating it as failure.
     */
    private void rethrowInterruption(ExecutionException failed) throws InterruptedException {
        provider.checkCancelled();
        if (failed.getCause() instanceof InterruptedException interrupted) throw interrupted;
    }

    private ReviewResult applyRule(
            LocalReviewRules.Rule rule, PRReviewRequest ruleRequest, InspectionManifest manifest)
            throws IOException, InterruptedException {
        String raw =
                provider.complete(
                        ReviewPrompts.buildRuleReviewPrompt(ruleRequest, rule),
                        RULE_TIMEOUT_MS,
                        true,
                        false,
                        NO_STATUS);
        ReviewResult parsed =
                ReviewResultParser.parseReview(raw, ReviewResultParser.RECALL_MAX_LINE_COMMENTS);
        // Rule findings bypass the first-pass validation, so an unanchored one must never survive.
        ReviewResult anchored = ReviewAnchorValidator.validate(parsed, manifest);
        for (LineComment comment : anchored.getLineComments()) {
            comment.setRationale(withRuleSuffix(comment.getRationale(), rule.name()));
        }
        return anchored;
    }

    /** Ends {@code rationale} with {@code (rule: name)}, truncating it to stay within the limit. */
    static String withRuleSuffix(String rationale, String name) {
        String suffix = " (rule: " + name + ")";
        String existing = StringUtils.strip(rationale);
        if (StringUtils.isEmpty(existing)) {
            return StringUtils.left("rule: " + name, MAX_RATIONALE_CHARS);
        }
        if (existing.endsWith(suffix) && existing.length() <= MAX_RATIONALE_CHARS) return existing;
        String kept =
                StringUtils.stripEnd(
                        StringUtils.left(
                                existing, Math.max(0, MAX_RATIONALE_CHARS - suffix.length())),
                        null);
        if (kept.isEmpty()) return StringUtils.left("rule: " + name, MAX_RATIONALE_CHARS);
        return kept + suffix;
    }

    /** Parses {@code {"triggered":[...]}}, returning null when the output has another shape. */
    static Set<String> parseSelection(String raw) {
        String text = StringUtils.strip(raw);
        if (StringUtils.isEmpty(text)) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        try {
            JsonNode triggered = JSON.readTree(text.substring(start, end + 1)).get("triggered");
            if (triggered == null || !triggered.isArray()) return null;
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode name : triggered) {
                if (!name.isTextual()) return null;
                names.add(name.asText().strip());
            }
            return names;
        } catch (IOException malformed) {
            return null;
        }
    }

    private boolean expired(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private void sleep(long deadline) throws InterruptedException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        Thread.sleep(Math.max(1, Math.min(pollMillis, remaining)));
    }

    static String deadlineStatus(String skipped) {
        return "Rule phase hit the "
                + TimeUnit.MILLISECONDS.toMinutes(RULE_PHASE_DEADLINE_MS)
                + "-minute limit; skipped: "
                + skipped;
    }

    private static String names(List<LocalReviewRules.Rule> rules) {
        return String.join(", ", rules.stream().map(LocalReviewRules.Rule::name).toList());
    }

    private static String findings(int count) {
        return count + (count == 1 ? " finding" : " findings");
    }

    private static ReviewResult empty() {
        return new ReviewResult("", "COMMENT", new ArrayList<>());
    }
}
