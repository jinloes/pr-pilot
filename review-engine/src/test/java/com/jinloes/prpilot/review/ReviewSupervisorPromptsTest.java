package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.DiffCoverage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewSupervisorPromptsTest {
    @Nested
    class ParseDirectives {
        @Test
        void ignoresInventedGapIdsAndKeepsKnownSelections() throws Exception {
            CoverageGap known = new CoverageGap("G001", "H-known", "schema.sql", 10, "schema", 100);

            List<FollowUpDirective> directives =
                    ReviewSupervisorPrompts.parseDirectives(
                            "{\"selectedGapIds\":[\"G-invented\",\"G001\"]}", List.of(known));

            assertThat(directives)
                    .singleElement()
                    .satisfies(
                            directive -> {
                                assertThat(directive.gapId()).isEqualTo("G001");
                                assertThat(directive.targetId()).isEqualTo("H-known");
                            });
        }
    }

    @Nested
    class FollowUpRequest {
        private static final String DIFF =
                "diff --git a/Api.java b/Api.java\n"
                        + "--- a/Api.java\n"
                        + "+++ b/Api.java\n"
                        + "@@ -1 +1 @@\n"
                        + "-void call(String old) {}\n"
                        + "+void call() {}\n";

        private PRReviewRequest followUpFor(PRReviewRequest original) {
            InspectionManifest manifest = InspectionManifest.fromDiff(original.getDiff());
            InspectionManifest.FileTarget file = manifest.files().get(0);
            return ReviewSupervisorPrompts.followUpRequest(
                    original,
                    manifest,
                    List.of(new FollowUpDirective("G001", file.id(), file.path(), 1, "check")));
        }

        @Test
        void keepsTheOmittedListOnTheFollowUp() {
            DiffCoverage coverage = new DiffCoverage(1, List.of("Big.java"), 250_000, true);
            PRReviewRequest original =
                    PRReviewRequest.builder(pr(), DIFF + coverage.trailer()).build();

            PRReviewRequest followUp = followUpFor(original);

            assertThat(followUp.diffCoverage()).isEqualTo(coverage);
            assertThat(followUp.getDiff()).contains("Api.java").doesNotContain("[pr-pilot:");
            assertThat(ClaudeService.buildPrompt(followUp))
                    .contains("<omitted_files>\n", "- Big.java");
        }

        @Test
        void completeCoverageStaysComplete() {
            PRReviewRequest followUp = followUpFor(PRReviewRequest.builder(pr(), DIFF).build());

            assertThat(followUp.diffCoverage()).isEqualTo(DiffCoverage.NONE);
            assertThat(ClaudeService.buildPrompt(followUp)).doesNotContain("<omitted_files>\n");
        }

        private static PullRequest pr() {
            return new PullRequest(
                    "T", "https://github.com/o/r/pull/1", "o", "r", 1, "", "a", "2024-01-01");
        }
    }
}
