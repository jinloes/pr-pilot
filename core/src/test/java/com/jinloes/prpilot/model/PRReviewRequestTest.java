package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PRReviewRequestTest {
    @Test
    void semanticContextIsOptionalAndDefensiveAcrossBuilderAccessorAndCopies() {
        var ordinary =
                PRReviewRequest.builder(pr(), "diff").customInstructions("instructions").build();
        assertThat(ordinary.getSemanticContext()).isNull();
        var evidence = new SemanticReviewContext();
        evidence.setEvidence("original");
        evidence.setLimitations(List.of("bounded"));
        var deep = ordinary.withSemanticContext(evidence);
        evidence.setEvidence("mutated");
        deep.getSemanticContext().setEvidence("accessor mutation");
        assertThat(deep.getSemanticContext().getEvidence()).isEqualTo("original");
        assertThat(deep.getCustomInstructions()).isEqualTo("instructions");
        assertThat(deep.getSemanticContext().getLimitations()).containsExactly("bounded");
        assertThat(ordinary.getSemanticContext()).isNull();
    }

    private static PullRequest pr() {
        return new PullRequest("T", "url", "o", "r", 1, "body", "author", "2026-01-01", false);
    }

    @Nested
    class DiffCoverageSplit {
        private static final String BODY = "diff --git a/a.txt b/a.txt\n+y\n";
        private static final String TRAILER =
                "[pr-pilot:diff-coverage] omitted=2 listed=1 budget=250000 scan=complete\n"
                        + "[pr-pilot:omitted] big.bin\n";

        @Test
        void builderExposesTheTrailerFreeBodyAndTheParsedCoverage() {
            PRReviewRequest request = PRReviewRequest.builder(pr(), BODY + TRAILER).build();

            assertThat(request.getDiff()).isEqualTo(BODY);
            assertThat(request.diffCoverage())
                    .isEqualTo(new DiffCoverage(2, List.of("big.bin"), 250_000, true));
        }

        @Test
        void aDiffWithoutATrailerHasCompleteCoverage() {
            PRReviewRequest request = new PRReviewRequest(pr(), BODY);

            assertThat(request.getDiff()).isEqualTo(BODY);
            assertThat(request.diffCoverage()).isSameAs(DiffCoverage.NONE);
        }

        @Test
        void nullDiffHasCompleteCoverage() {
            PRReviewRequest request = PRReviewRequest.builder(pr(), null).build();

            assertThat(request.getDiff()).isNull();
            assertThat(request.diffCoverage()).isSameAs(DiffCoverage.NONE);
        }

        /** Negative control: a non-terminal trailer is diff text and must stay visible. */
        @Test
        void aMalformedOrNonTerminalTrailerStaysInTheDiff() {
            String nonTerminal = BODY + TRAILER + "diff --git a/b b/b\n+z\n";
            String malformed = BODY + TRAILER.replace("listed=1", "listed=3");

            assertThat(PRReviewRequest.builder(pr(), nonTerminal).build())
                    .satisfies(
                            request -> {
                                assertThat(request.getDiff()).isEqualTo(nonTerminal);
                                assertThat(request.diffCoverage()).isSameAs(DiffCoverage.NONE);
                            });
            assertThat(PRReviewRequest.builder(pr(), malformed).build())
                    .satisfies(
                            request -> {
                                assertThat(request.getDiff()).isEqualTo(malformed);
                                assertThat(request.diffCoverage()).isSameAs(DiffCoverage.NONE);
                            });
        }

        @Test
        void explicitCoverageOverridesTheParsedCoverageAndNullMeansComplete() {
            DiffCoverage validation = new DiffCoverage(1, List.of("huge.txt"), 1_000_000, true);

            PRReviewRequest overridden =
                    PRReviewRequest.builder(pr(), BODY + TRAILER).diffCoverage(validation).build();
            PRReviewRequest cleared =
                    PRReviewRequest.builder(pr(), BODY + TRAILER).diffCoverage(null).build();

            assertThat(overridden.getDiff()).isEqualTo(BODY);
            assertThat(overridden.diffCoverage()).isEqualTo(validation);
            assertThat(cleared.getDiff()).isEqualTo(BODY);
            assertThat(cleared.diffCoverage()).isSameAs(DiffCoverage.NONE);
        }

        @Test
        void withSemanticContextPreservesBodyAndCoverageWithoutResplitting() {
            // The body itself ends in a lookalike trailer; only the last one was the real trailer.
            String stacked = BODY + TRAILER + TRAILER.replace("omitted=2", "omitted=5");
            PRReviewRequest request = PRReviewRequest.builder(pr(), stacked).build();

            PRReviewRequest deep = request.withSemanticContext(new SemanticReviewContext());

            assertThat(request.getDiff()).isEqualTo(BODY + TRAILER);
            assertThat(deep.getDiff()).isEqualTo(BODY + TRAILER);
            assertThat(deep.diffCoverage()).isEqualTo(request.diffCoverage());
            assertThat(deep.diffCoverage().omitted()).isEqualTo(5);
        }
    }

    @Nested
    class Builder {

        /**
         * Every setter is checked because a builder that silently drops a field produces a review
         * missing one prompt section with no error anywhere — the failure is invisible at runtime.
         */
        @Test
        void roundTripsEveryContextField() {
            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), "diff")
                            .priorReview("prior")
                            .existingReviews("existing")
                            .repoGuidelines("guidelines")
                            .focusAreas("focus")
                            .customInstructions("custom")
                            .ciStatus("ci")
                            .commits("commits")
                            .linkedIssue("issue")
                            .repoProfile("profile")
                            .build();

            assertThat(request.getDiff()).isEqualTo("diff");
            assertThat(request.getPriorReview()).isEqualTo("prior");
            assertThat(request.getExistingReviews()).isEqualTo("existing");
            assertThat(request.getRepoGuidelines()).isEqualTo("guidelines");
            assertThat(request.getFocusAreas()).isEqualTo("focus");
            assertThat(request.getCustomInstructions()).isEqualTo("custom");
            assertThat(request.getCiStatus()).isEqualTo("ci");
            assertThat(request.getCommits()).isEqualTo("commits");
            assertThat(request.getLinkedIssue()).isEqualTo("issue");
            assertThat(request.getRepoProfile()).isEqualTo("profile");
        }

        @Test
        void defaultsCiAnnotationsToEmptyRatherThanNull() {
            assertThat(PRReviewRequest.builder(pr(), "diff").build().getCiAnnotations()).isEmpty();
            assertThat(
                            PRReviewRequest.builder(pr(), "diff")
                                    .ciAnnotations(null)
                                    .build()
                                    .getCiAnnotations())
                    .isEmpty();
        }

        @Test
        void copiesCiAnnotationsSoLaterCallerMutationCannotChangeTheRequest() {
            CiAnnotation annotation = new CiAnnotation("A.java", 1, "WARNING", "m");
            List<CiAnnotation> source = new ArrayList<>(List.of(annotation));

            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), "diff").ciAnnotations(source).build();
            source.clear();
            annotation.setFile("Changed.java");
            annotation.setLine(99);
            annotation.setLevel("failure");
            annotation.setMessage("changed");

            CiAnnotation stored = request.getCiAnnotations().get(0);
            assertThat(stored.getFile()).isEqualTo("A.java");
            assertThat(stored.getLine()).isEqualTo(1);
            assertThat(stored.getLevel()).isEqualTo("warning");
            assertThat(stored.getMessage()).isEqualTo("m");
        }

        @Test
        void accessorMutationCannotChangeTheRequestSnapshot() {
            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), "diff")
                            .ciAnnotations(
                                    List.of(new CiAnnotation("A.java", 1, "warning", "message")))
                            .build();

            request.getCiAnnotations().get(0).setMessage("changed");

            assertThat(request.getCiAnnotations().get(0).getMessage()).isEqualTo("message");
            assertThatThrownBy(() -> request.getCiAnnotations().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void continuesToRejectNullAnnotationElements() {
            List<CiAnnotation> annotations = new ArrayList<>();
            annotations.add(null);

            assertThatThrownBy(
                            () ->
                                    PRReviewRequest.builder(pr(), "diff")
                                            .ciAnnotations(annotations)
                                            .build())
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
