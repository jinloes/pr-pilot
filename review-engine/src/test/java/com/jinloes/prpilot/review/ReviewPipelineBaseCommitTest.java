package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.emptyReviewJson;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.oneRiskyHunk;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pass;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.pipeline;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.request;
import static com.jinloes.prpilot.review.ReviewPipelineTestSupport.result;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.review.ReviewPipelineTestSupport.FakeProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Base-commit guidance and rule enrichment tests for {@link ReviewPipelineService}. */
class ReviewPipelineBaseCommitTest {
    @TempDir Path semanticRoot;

    @Nested
    class Review {
        private static final String BASE_SHA = "b".repeat(40);

        @Test
        void enrichesTheRequestFromTheBaseCommitBeforeReviewing() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.projectDir = semanticRoot.toFile();
            provider.primaryResult = pass(result());
            provider.completions.add(emptyReviewJson());
            List<String> resolvedShas = new ArrayList<>();
            List<String> statuses = new ArrayList<>();

            pipeline(
                            provider,
                            (dir, sha, manifest, globs, cancellation) -> {
                                assertThat(dir).isEqualTo(semanticRoot.toFile());
                                assertThat(globs).containsExactly("rules/*.md");
                                resolvedShas.add(sha);
                                return new BaseCommitContext.Result(
                                        "## AGENTS.md\nBase rule.",
                                        "## src/Api.java\nabc1234 2026-01-01 Keep it stable",
                                        "## save (declaration changed in src/Api.java)\n"
                                                + "src/Caller.java:7: api.save(x);");
                            })
                    .review(
                            request(oneRiskyHunk()).toBuilder()
                                    .baseSha(BASE_SHA)
                                    .guidanceGlobs(List.of("rules/*.md"))
                                    .build(),
                            false,
                            true,
                            false,
                            statuses::add,
                            null);

            assertThat(resolvedShas).containsExactly(BASE_SHA);
            assertThat(statuses).contains(ReviewPipelineService.STATUS_BASE_CONTEXT);
            PRReviewRequest reviewed = provider.primaryRequests.get(0);
            assertThat(reviewed.getRepoGuidelines()).isEqualTo("## AGENTS.md\nBase rule.");
            assertThat(reviewed.getFileHistory()).contains("Keep it stable");
            assertThat(provider.completeCalls.get(0).prompt())
                    .contains("<file_history>", "Keep it stable")
                    .contains("<call_sites>", "src/Caller.java:7: api.save(x);");
            assertThat(reviewed.getCallSites()).contains("api.save(x)");
        }

        @Test
        void keepsExistingGuidanceWhenTheBaseCommitHasNone() throws Exception {
            FakeProvider provider = new FakeProvider();
            provider.projectDir = semanticRoot.toFile();
            provider.primaryResult = pass(result());

            pipeline(
                            provider,
                            (dir, sha, manifest, globs, cancellation) ->
                                    BaseCommitContext.Result.EMPTY)
                    .review(
                            request(oneRiskyHunk()).toBuilder()
                                    .baseSha(BASE_SHA)
                                    .repoGuidelines("Host guidance.")
                                    .build(),
                            false,
                            false,
                            false,
                            s -> {},
                            null);

            assertThat(provider.primaryRequests.get(0).getRepoGuidelines())
                    .isEqualTo("Host guidance.");
        }

        @Test
        void skipsEnrichmentWithoutAProjectDirectoryOrBaseSha() throws Exception {
            AtomicInteger resolutions = new AtomicInteger();
            ReviewPipelineService.BaseContextResolver resolver =
                    (dir, sha, manifest, globs, cancellation) -> {
                        resolutions.incrementAndGet();
                        return BaseCommitContext.Result.EMPTY;
                    };
            FakeProvider noDir = new FakeProvider();
            noDir.primaryResult = pass(result());
            FakeProvider noSha = new FakeProvider();
            noSha.projectDir = semanticRoot.toFile();
            noSha.primaryResult = pass(result());

            pipeline(noDir, resolver)
                    .review(
                            request(oneRiskyHunk()).toBuilder().baseSha(BASE_SHA).build(),
                            false,
                            false,
                            false,
                            s -> {},
                            null);
            pipeline(noSha, resolver)
                    .review(request(oneRiskyHunk()), false, false, false, s -> {}, null);

            assertThat(resolutions).hasValue(0);
        }
    }
}
