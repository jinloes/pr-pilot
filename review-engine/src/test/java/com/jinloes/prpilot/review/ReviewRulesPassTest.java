package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.FAIL;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.JSON;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.emptyReviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.ruleName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.LocalReviewRules.Rule;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.PromptCall;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewRulesPassTest {
    private static final Rule GATED =
            new Rule("gated", "gated.yaml", "Checks retries", "Touches retry code", "Check retry.");
    private static final Rule OTHER =
            new Rule("other", "other.yaml", "Checks caching", "Touches a cache", "Check caches.");
    private static final Rule TEAM = new Rule("team.md", "team.md", null, null, "Prefer Optional.");

    private final FakeProvider provider = new FakeProvider();
    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private final Set<String> statusThreads = ConcurrentHashMap.newKeySet();
    private final Consumer<String> onStatus =
            status -> {
                statusThreads.add(Thread.currentThread().getName());
                statuses.add(status);
            };

    private static PRReviewRequest request() {
        PullRequest pr =
                new PullRequest(
                        "Retry payments",
                        "https://example.test/pr/1",
                        "acme",
                        "repo",
                        1,
                        "Adds a retry loop.",
                        "author",
                        "",
                        false);
        return PRReviewRequest.builder(pr, oneRiskyHunk()).build();
    }

    private ReviewResult run(List<Rule> rules) throws Exception {
        return run(new ReviewRulesPass(provider, provider::checkCancelled), rules);
    }

    private ReviewResult run(ReviewRulesPass pass, List<Rule> rules) throws Exception {
        PRReviewRequest request = request();
        return pass.run(
                rules, request, request, InspectionManifest.fromDiff(request.getDiff()), onStatus);
    }

    private List<String> ruleNamesCalled() {
        return provider.ruleCalls.stream().map(call -> ruleName(call.prompt())).toList();
    }

    private static String finding(String file, int line, String rationale) throws IOException {
        Map<String, Object> comment = new HashMap<>();
        comment.put("file", file);
        comment.put("line", line);
        comment.put("type", "issue");
        comment.put("severity", "major");
        comment.put("category", "correctness");
        comment.put("confidence", "high");
        comment.put("body", "Rule finding at " + line + ".");
        if (rationale != null) comment.put("rationale", rationale);
        return JSON.writeValueAsString(
                Map.of("summary", "rule", "verdict", "COMMENT", "lineComments", List.of(comment)));
    }

    @Nested
    class Selection {
        @Test
        void asksOneToolFreeCallWithTheRulesAndChangedFiles() throws Exception {
            provider.ruleSelectionCompletions.add("{\"triggered\":[]}");

            run(List.of(GATED, OTHER));

            assertThat(provider.ruleSelectionCalls)
                    .singleElement()
                    .satisfies(
                            call -> {
                                assertThat(call.allowReadTools()).isFalse();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(90_000L);
                                assertThat(call.prompt())
                                        .contains(
                                                "title: Retry payments",
                                                "<pr_description>\nAdds a retry loop.",
                                                "- src/Api.java (+1 -1)",
                                                "- name: gated\n  description: Checks retries\n"
                                                        + "  trigger: Touches retry code",
                                                "- name: other",
                                                "{\"triggered\":[\"<rule name>\", ...]}",
                                                "when in doubt, trigger the rule")
                                        .doesNotContain("Check retry.", "<pr_diff>");
                            });
            assertThat(statuses).containsExactly("Rules: 2 loaded, 0 selected");
        }

        @Test
        void runsOnlyTheTriggeredRulesAndIgnoresUnknownNames() throws Exception {
            provider.ruleSelectionCompletions.add("Sure: {\"triggered\":[\"other\",\"missing\"]}");

            run(List.of(GATED, OTHER, TEAM));

            assertThat(ruleNamesCalled()).containsExactlyInAnyOrder("other", "team.md");
            assertThat(statuses).first().isEqualTo("Rules: 3 loaded, 2 selected (other, team.md)");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    FAIL,
                    "not json",
                    "{\"triggered\":\"gated\"}",
                    "{\"triggered\":[1]}",
                    "{\"chosen\":[]}"
                })
        void failsOpenToEveryStructuredRule(String completion) throws Exception {
            provider.ruleSelectionCompletions.add(completion);

            run(List.of(GATED, OTHER, TEAM));

            assertThat(ruleNamesCalled()).containsExactlyInAnyOrder("gated", "other", "team.md");
            assertThat(statuses)
                    .startsWith(
                            "Rule selection failed; applying all 2 rules",
                            "Rules: 3 loaded, 3 selected (gated, other, team.md)");
        }

        @Test
        void skipsSelectionWhenEveryRuleIsUnstructured() throws Exception {
            run(List.of(TEAM));

            assertThat(provider.ruleSelectionCalls).isEmpty();
            assertThat(ruleNamesCalled()).containsExactly("team.md");
        }

        @Test
        void makesNoCallsAndReportsNothingWithoutRules() throws Exception {
            ReviewResult result = run(List.of());

            assertThat(result.getLineComments()).isEmpty();
            assertThat(provider.callOrder).isEmpty();
            assertThat(statuses).isEmpty();
        }

        @Test
        void interruptionPropagatesInsteadOfFailingOpen() {
            provider.cancelled = true;

            assertThatThrownBy(() -> run(List.of(GATED))).isInstanceOf(InterruptedException.class);
            assertThat(provider.ruleCalls).isEmpty();
            assertThat(statuses).isEmpty();
        }

        @Test
        void aSelectionCallInterruptedWithoutCancellationPropagates() {
            provider.ruleSelectionScript =
                    prompt -> {
                        throw new InterruptedException("selection interrupted");
                    };

            assertThatThrownBy(() -> run(List.of(GATED, TEAM)))
                    .isInstanceOf(InterruptedException.class)
                    .hasMessage("selection interrupted");
            assertThat(provider.ruleCalls).isEmpty();
            assertThat(statuses).isEmpty();
        }
    }

    @Nested
    class Agents {
        @Test
        void eachRuleRunsAsAReadOnlyAgentWithANoOpStatusConsumer() throws Exception {
            provider.ruleSelectionCompletions.add("{\"triggered\":[\"gated\"]}");

            run(List.of(GATED, TEAM));

            assertThat(provider.ruleCalls)
                    .hasSize(2)
                    .allSatisfy(
                            call -> {
                                assertThat(call.allowReadTools()).isTrue();
                                assertThat(call.allowMcp()).isFalse();
                                assertThat(call.timeoutMillis()).isEqualTo(10L * 60L * 1000L);
                                assertThat(call.prompt())
                                        .contains(
                                                "Respond ONLY with a JSON object",
                                                "<pr_metadata>",
                                                "<pr_diff>",
                                                "+public void call() {}");
                            });
            assertThat(provider.ruleCalls)
                    .extracting(PromptCall::prompt)
                    .anySatisfy(
                            prompt -> assertThat(prompt).contains("name: gated\n\nCheck retry."))
                    .anySatisfy(
                            prompt ->
                                    assertThat(prompt)
                                            .contains("name: team.md\n\nPrefer Optional."));
            provider.ruleStatusConsumers.forEach(consumer -> consumer.accept("leak"));
            assertThat(statuses).doesNotContain("leak");
            assertThat(statusThreads).containsExactly(Thread.currentThread().getName());
        }

        @Test
        void capsTheReviewAtTwentyFiveRulesAndNamesTheRest() throws Exception {
            List<Rule> rules = new ArrayList<>();
            for (int i = 1; i <= 27; i++) {
                rules.add(new Rule("r" + i + ".md", "r" + i + ".md", null, null, "Rule " + i));
            }

            run(rules);

            assertThat(provider.ruleCalls).hasSize(25);
            assertThat(ruleNamesCalled()).doesNotContain("r26.md", "r27.md");
            assertThat(statuses).contains("Skipped 2 rules over the 25-rule limit: r26.md, r27.md");
            assertThat(statuses.get(0)).startsWith("Rules: 27 loaded, 25 selected (r1.md, r2.md");
        }

        @Test
        void runsAtMostThreeAgentsAtOnce() throws Exception {
            List<Rule> rules = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                String name = "r" + i + ".md";
                rules.add(new Rule(name, name, null, null, "Rule " + i));
                provider.ruleScripts.put(
                        name,
                        prompt -> {
                            Thread.sleep(60);
                            return emptyReviewJson();
                        });
            }

            run(rules);

            assertThat(provider.ruleCalls).hasSize(7);
            assertThat(provider.peakInFlightRules.get()).isBetween(1, 3);
        }

        @Test
        void aFailedRuleIsReportedAndTheOthersAreKept() throws Exception {
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> {
                        throw new IOException("rule agent crashed");
                    });
            Rule second = new Rule("second.md", "second.md", null, null, "Second.");
            provider.ruleScripts.put("second.md", prompt -> finding("src/Api.java", 1, "Why."));

            ReviewResult result = run(List.of(TEAM, second));

            assertThat(statuses)
                    .contains("Rule team.md failed; continuing", "Rule second.md found 1 finding");
            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getBody)
                    .isEqualTo("Rule finding at 1.");
        }

        @Test
        void anAgentInterruptedWithoutCancellationPropagatesInsteadOfFailing() {
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> {
                        throw new InterruptedException("agent interrupted");
                    });

            assertThatThrownBy(() -> run(List.of(TEAM)))
                    .isInstanceOf(InterruptedException.class)
                    .hasMessage("agent interrupted");
            assertThat(statuses).doesNotContain("Rule team.md failed; continuing");
        }

        @Test
        void tagsEachFindingWithItsRuleAndLeavesSourcesEmpty() throws Exception {
            provider.ruleScripts.put("team.md", prompt -> finding("src/Api.java", 1, "Why."));

            ReviewResult result = run(List.of(TEAM));

            assertThat(result.getLineComments())
                    .singleElement()
                    .satisfies(
                            comment -> {
                                assertThat(comment.getRationale())
                                        .isEqualTo("Why. (rule: team.md)");
                                assertThat(comment.getSources()).isEmpty();
                            });
        }

        @Test
        void dropsAFindingThatDoesNotAnchorToAChangedLine() throws Exception {
            provider.ruleScripts.put("team.md", prompt -> finding("src/Missing.java", 4, "Why."));

            ReviewResult result = run(List.of(TEAM));

            assertThat(result.getLineComments()).isEmpty();
            assertThat(statuses).contains("Rule team.md found 0 findings");
        }
    }

    @Nested
    class WithRuleSuffix {
        @Test
        void appendsTheSuffix() {
            assertThat(ReviewRulesPass.withRuleSuffix("Why.", "gated"))
                    .isEqualTo("Why. (rule: gated)");
        }

        @Test
        void keepsAnExistingSuffix() {
            assertThat(ReviewRulesPass.withRuleSuffix("Why. (rule: gated)", "gated"))
                    .isEqualTo("Why. (rule: gated)");
        }

        @Test
        void namesTheRuleWhenTheRationaleIsMissing() {
            assertThat(ReviewRulesPass.withRuleSuffix(null, "gated")).isEqualTo("rule: gated");
            assertThat(ReviewRulesPass.withRuleSuffix("  ", "gated")).isEqualTo("rule: gated");
        }

        @Test
        void truncatesALongRationaleToStayWithinTheLimit() {
            String result = ReviewRulesPass.withRuleSuffix("x".repeat(200), "gated");

            assertThat(result).hasSize(200).endsWith("x (rule: gated)");
        }

        @Test
        void fallsBackToATruncatedRuleNameWhenTheNameLeavesNoRoomForTheRationale() {
            String longName = "rules/" + "deep/".repeat(40) + "check.md";

            String result = ReviewRulesPass.withRuleSuffix("Why.", longName);

            assertThat(longName.length()).isGreaterThan(200);
            assertThat(result).hasSize(200).isEqualTo(("rule: " + longName).substring(0, 200));
        }

        @Test
        void neverExceedsTheLimitForANameJustShorterThanTheLimit() {
            String name = "n".repeat(190);

            assertThat(ReviewRulesPass.withRuleSuffix("Why.", name))
                    .hasSizeLessThanOrEqualTo(200)
                    .endsWith(" (rule: " + name + ")");
        }
    }

    @Nested
    class DeadlineAndCancellation {
        @Test
        void theDeadlineCancelsRunningAgentsSkipsQueuedOnesAndKeepsFindings() throws Exception {
            CountDownLatch interrupted = new CountDownLatch(3);
            RuleBlocker blocker = new RuleBlocker(interrupted);
            List<Rule> rules = new ArrayList<>();
            for (String name :
                    List.of("slow-a.md", "slow-b.md", "quick.md", "slow-c.md", "queued.md")) {
                rules.add(new Rule(name, name, null, null, name));
            }
            provider.ruleScripts.put("slow-a.md", blocker);
            provider.ruleScripts.put("slow-b.md", blocker);
            provider.ruleScripts.put("slow-c.md", blocker);
            provider.ruleScripts.put("quick.md", prompt -> finding("src/Api.java", 1, "Why."));

            ReviewResult result =
                    run(new ReviewRulesPass(provider, provider::checkCancelled, 400, 10), rules);

            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ruleNamesCalled()).doesNotContain("queued.md");
            assertThat(statuses)
                    .contains(
                            "Rule quick.md found 1 finding",
                            "Rule phase hit the 12-minute limit; skipped: slow-a.md, slow-b.md,"
                                    + " slow-c.md, queued.md");
            assertThat(statuses).noneMatch(status -> status.contains("failed"));
            assertThat(result.getLineComments()).hasSize(1);
        }

        @Test
        void theDeadlineDuringSelectionSkipsEveryRule() throws Exception {
            CountDownLatch interrupted = new CountDownLatch(1);
            provider.ruleSelectionScript = new RuleBlocker(interrupted);

            ReviewResult result =
                    run(
                            new ReviewRulesPass(provider, provider::checkCancelled, 200, 10),
                            List.of(GATED, TEAM));

            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.getLineComments()).isEmpty();
            assertThat(statuses)
                    .containsExactly("Rule phase hit the 12-minute limit; skipped: gated, team.md");
        }

        @Test
        void userCancellationInterruptsAgentsAndIsNotAFailure() throws Exception {
            CountDownLatch interrupted = new CountDownLatch(1);
            provider.ruleScripts.put(
                    "team.md",
                    prompt -> {
                        provider.cancelled = true;
                        return new RuleBlocker(interrupted).run(prompt);
                    });
            provider.ruleScripts.put(
                    "killed.md",
                    prompt -> {
                        provider.cancelled = true;
                        throw new IOException("process destroyed");
                    });
            Rule killed = new Rule("killed.md", "killed.md", null, null, "Killed.");

            assertThatThrownBy(() -> run(List.of(TEAM, killed)))
                    .isInstanceOf(InterruptedException.class);

            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(statuses).noneMatch(status -> status.contains("failed"));
        }
    }

    @Nested
    class Authority {
        @Test
        void checksAuthorityBeforeSelectionAndBeforeEachAgent() throws Exception {
            provider.ruleSelectionCompletions.add("{\"triggered\":[\"gated\"]}");
            List<String> order = new CopyOnWriteArrayList<>();
            AtomicInteger checks = new AtomicInteger();
            ReviewRulesPass pass =
                    new ReviewRulesPass(
                            provider,
                            () -> {
                                checks.incrementAndGet();
                                order.add("check@" + provider.callOrder.size());
                            });

            run(pass, List.of(GATED, TEAM));

            assertThat(checks).hasValue(3);
            assertThat(order.get(0)).isEqualTo("check@0");
        }

        @Test
        void anAuthorityFailureAbortsBeforeTheNextAgent() {
            provider.ruleSelectionCompletions.add("{\"triggered\":[\"gated\"]}");
            AtomicInteger checks = new AtomicInteger();
            ReviewRulesPass pass =
                    new ReviewRulesPass(
                            provider,
                            () -> {
                                if (checks.incrementAndGet() == 2) {
                                    throw new IOException("Deep review authority invalidated");
                                }
                            });

            assertThatThrownBy(() -> run(pass, List.of(GATED, TEAM)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("invalidated");
            assertThat(provider.ruleSelectionCalls).hasSize(1);
            assertThat(provider.ruleCalls).isEmpty();
        }
    }

    /** Blocks until interrupted, then counts down. */
    private record RuleBlocker(CountDownLatch interrupted)
            implements ReviewPipelineTestSupport.RuleScript {
        @Override
        public String run(String prompt) throws InterruptedException {
            try {
                new CountDownLatch(1).await();
            } finally {
                interrupted.countDown();
            }
            return "";
        }
    }
}
