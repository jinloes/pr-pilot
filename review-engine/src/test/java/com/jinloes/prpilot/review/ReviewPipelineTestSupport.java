package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared fakes and fixtures for the review-pipeline test classes. */
final class ReviewPipelineTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();

    private ReviewPipelineTestSupport() {}

    static final class DeepStages {
        final SemanticReviewServiceTest.Backend backend;
        final int invalidateAt;
        final boolean providerFailure;
        final List<String> calls = new ArrayList<>();

        DeepStages(
                SemanticReviewServiceTest.Backend backend,
                int invalidateAt,
                boolean providerFailure) {
            this.backend = backend;
            this.invalidateAt = invalidateAt;
            this.providerFailure = providerFailure;
        }

        void observe(String stage, String prompt) throws IOException {
            assertThat(prompt)
                    .contains(
                            "<trusted_semantic_review_skills>",
                            "<untrusted_semantic_evidence>",
                            "untrusted text-tree");
            // The complete pinned instructions, not merely a marker, must survive each copy.
            assertThat(prompt).contains(SemanticSkillBundle.load().instructions());
            calls.add(stage);
            if (calls.size() - 1 == invalidateAt) {
                backend.epochs = "settings-edited-and-restored";
                if (providerFailure) throw new IOException("Simulated provider-stage failure");
            }
        }

        ReviewPassResult primary(PRReviewRequest request) throws IOException {
            assertThat(request.getSemanticContext()).isNotNull();
            observe("primary", ReviewPrompts.buildPrompt(request));
            // Mention every file so only the hunk gaps remain and the selection stage runs.
            Set<String> files = new HashSet<>();
            InspectionManifest.fromDiff(request.getDiff())
                    .files()
                    .forEach(file -> files.add(file.id()));
            return new ReviewPassResult(
                    new ReviewResult("candidate", "APPROVE", List.of()),
                    new InspectionLedger(true, files, List.of()));
        }

        String complete(String prompt, boolean reads) throws IOException {
            boolean critique = prompt.contains("<draft_review>");
            boolean hygiene = prompt.contains("hygiene problems only");
            observe(
                    critique ? "critique" : hygiene ? "hygiene" : reads ? "follow-up" : "selection",
                    prompt);
            return !reads ? "{\"selectedGapIds\":[\"G004\",\"G002\"]}" : emptyReviewJson();
        }

        ReviewPipelineService pipeline(boolean copilot) {
            if (copilot) {
                return ReviewPipelineService.forCopilot(
                        new CopilotService() {
                            @Override
                            ReviewPassResult reviewPass(
                                    PRReviewRequest request,
                                    String model,
                                    String effort,
                                    Consumer<String> status,
                                    BiConsumer<String, String> chunks,
                                    boolean inherit,
                                    String config)
                                    throws IOException {
                                assertThat(inherit).isFalse();
                                return primary(request);
                            }

                            @Override
                            String completeReviewPrompt(
                                    String prompt,
                                    String model,
                                    String effort,
                                    boolean inherit,
                                    String config,
                                    boolean reads,
                                    long timeout,
                                    Consumer<String> status)
                                    throws IOException {
                                assertThat(inherit).isFalse();
                                return complete(prompt, reads);
                            }
                        },
                        "fake",
                        "high",
                        true,
                        null);
            }
            return ReviewPipelineService.forClaude(
                    new ClaudeService() {
                        @Override
                        ReviewPassResult reviewPass(
                                PRReviewRequest request,
                                String model,
                                Consumer<String> status,
                                BiConsumer<String, String> chunks)
                                throws IOException {
                            return primary(request);
                        }

                        @Override
                        String completeReviewPrompt(
                                String prompt,
                                String model,
                                Consumer<String> status,
                                long timeout,
                                boolean reads)
                                throws IOException {
                            return complete(prompt, reads);
                        }
                    },
                    "fake");
        }
    }

    /** A scripted completion that makes that one {@code complete} call fail. */
    static final String FAIL = "<fail>";

    static final class FakeProvider implements ReviewPipelineService.ProviderExecutor {
        ReviewPassResult primaryResult;
        final AtomicInteger primaryCalls = new AtomicInteger();
        final List<String> completions = new ArrayList<>();
        final List<PromptCall> completeCalls = new ArrayList<>();
        // Hygiene calls are tracked apart so stage-specific assertions stay readable.
        final List<String> hygieneCompletions = new ArrayList<>();
        final List<PromptCall> hygieneCalls = new ArrayList<>();
        final List<String> callOrder = new ArrayList<>();
        IOException hygieneFailure;
        IOException completionFailure;
        boolean cancelAfterPrimary;
        final List<PRReviewRequest> primaryRequests = new ArrayList<>();
        File projectDir;
        PrimaryHook beforePrimary = () -> {};
        String displayModel = "claude-opus";

        @Override
        public String displayModel() {
            return displayModel;
        }

        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws InterruptedException {
            primaryCalls.incrementAndGet();
            primaryRequests.add(request);
            callOrder.add("review");
            beforePrimary.run();
            return primaryResult;
        }

        @Override
        public File projectDir() {
            return projectDir;
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException {
            PromptCall call = new PromptCall(prompt, timeoutMillis, allowReadTools, allowMcp);
            if (prompt.contains("hygiene problems only")) {
                hygieneCalls.add(call);
                callOrder.add("hygiene");
                if (hygieneFailure != null) throw hygieneFailure;
                return hygieneCompletions.isEmpty()
                        ? emptyReviewJson()
                        : hygieneCompletions.remove(0);
            }
            completeCalls.add(call);
            callOrder.add(prompt.contains("<draft_review>") ? "critique" : "supervisor");
            if (completionFailure != null) {
                throw completionFailure;
            }
            String next = completions.remove(0);
            if (FAIL.equals(next)) throw new IOException("scripted completion failure");
            return next;
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            if (cancelAfterPrimary && primaryCalls.get() > 0) {
                throw new InterruptedException("cancelled");
            }
        }
    }

    @FunctionalInterface
    interface PrimaryHook {
        void run() throws InterruptedException;
    }

    /** A second reviewer that can succeed, fail, or block until it is interrupted. */
    static final class FakeSecondary implements ReviewPipelineService.ProviderExecutor {
        ReviewPassResult result;
        IOException failure;
        boolean blockUntilInterrupted;
        CountDownLatch release;
        final List<PRReviewRequest> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException, InterruptedException {
            requests.add(request);
            started.countDown();
            if (blockUntilInterrupted) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
            }
            if (release != null) release.await(5, TimeUnit.SECONDS);
            if (failure != null) throw failure;
            return result;
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus) {
            throw new AssertionError("The second reviewer only runs the primary pass");
        }

        @Override
        public void checkCancelled() {}

        void cancel() {
            cancels.incrementAndGet();
        }
    }

    record PromptCall(
            String prompt, long timeoutMillis, boolean allowReadTools, boolean allowMcp) {}

    static PRReviewRequest request(String diff) {
        PullRequest pr =
                new PullRequest(
                        "Change API",
                        "https://example.test/pr/1",
                        "acme",
                        "repo",
                        1,
                        "",
                        "author",
                        "",
                        false);
        return PRReviewRequest.builder(pr, diff).build();
    }

    static String reviewJsonWithFinding(String file, int line) throws Exception {
        return JSON.writeValueAsString(
                Map.of(
                        "summary",
                        "follow-up",
                        "verdict",
                        "REQUEST_CHANGES",
                        "lineComments",
                        List.of(
                                Map.of(
                                        "file", file,
                                        "line", line,
                                        "type", "issue",
                                        "severity", "major",
                                        "category", "correctness",
                                        "confidence", "high",
                                        "body", "The changed contract breaks its caller.",
                                        "rationale",
                                                "The new signature no longer accepts the required value."))));
    }

    static String emptyReviewJson() throws IOException {
        return JSON.writeValueAsString(
                Map.of(
                        "summary", "reviewed",
                        "verdict", "APPROVE",
                        "lineComments", List.of()));
    }

    static String oneRiskyHunk() {
        return """
                diff --git a/src/Api.java b/src/Api.java
                --- a/src/Api.java
                +++ b/src/Api.java
                @@ -1 +1 @@
                -private void call(String value) {}
                +public void call() {}
                """;
    }

    static String fourRiskyHunks() {
        return """
                diff --git a/src/Api.java b/src/Api.java
                --- a/src/Api.java
                +++ b/src/Api.java
                @@ -1 +1 @@
                -private void one(String value) {}
                +public void one() {}
                @@ -10 +10 @@
                -private void two(String value) {}
                +public void two() {}
                @@ -20 +20 @@
                -private void three(String value) {}
                +public void three() {}
                @@ -30 +30 @@
                -private void four(String value) {}
                +public void four() {}
                """;
    }

    static String sevenFileDiff() {
        return fileDiff(7);
    }

    static String fileDiff(int files) {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < files; index++) {
            diff.append("diff --git a/F")
                    .append(index)
                    .append(".java b/F")
                    .append(index)
                    .append(".java\n")
                    .append("--- a/F")
                    .append(index)
                    .append(".java\n")
                    .append("+++ b/F")
                    .append(index)
                    .append(".java\n")
                    .append("@@ -1 +1 @@\n-old\n+new\n");
        }
        return diff.toString();
    }
}
