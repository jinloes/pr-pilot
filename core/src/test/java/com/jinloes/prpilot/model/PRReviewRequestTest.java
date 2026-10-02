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

    @Nested
    class GetGuidanceGlobs {
        @Test
        void defaultsToEmptyWhenUnset() {
            assertThat(PRReviewRequest.builder(pr(), "diff").build().getGuidanceGlobs()).isEmpty();
            assertThat(
                            PRReviewRequest.builder(pr(), "diff")
                                    .guidanceGlobs(null)
                                    .build()
                                    .getGuidanceGlobs())
                    .isEmpty();
        }

        @Test
        void isAnImmutableCopyWithoutNulls() {
            List<String> globs = new ArrayList<>(List.of("a/*.md"));
            globs.add(null);
            PRReviewRequest request =
                    PRReviewRequest.builder(pr(), "diff").guidanceGlobs(globs).build();
            globs.add("b.md");

            assertThat(request.getGuidanceGlobs()).containsExactly("a/*.md");
            assertThatThrownBy(() -> request.getGuidanceGlobs().add("c"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class ToBuilder {
        private static final String SHA = "a".repeat(40);

        private PRReviewRequest full() {
            SemanticReviewContext semantic = new SemanticReviewContext();
            semantic.setEvidence("evidence");
            return PRReviewRequest.builder(pr(), "diff")
                    .priorReview("prior")
                    .existingReviews("existing")
                    .repoGuidelines("guidelines")
                    .focusAreas("focus")
                    .customInstructions("custom")
                    .ciStatus("ci")
                    .commits("commits")
                    .linkedIssue("issue")
                    .repoProfile("profile")
                    .ciAnnotations(List.of(new CiAnnotation()))
                    .semanticContext(semantic)
                    .baseSha(SHA)
                    .guidanceGlobs(List.of("docs/rules/*.md"))
                    .fileHistory("history")
                    .callSites("sites")
                    .candidateRecall(true)
                    .diffCoverage(new DiffCoverage(1, List.of("x"), 10, true))
                    .build();
        }

        @Test
        void roundTripsEveryField() {
            PRReviewRequest source = full();
            PRReviewRequest copy = source.toBuilder().build();

            assertThat(copy).usingRecursiveComparison().isEqualTo(source);
            assertThat(copy.getBaseSha()).isEqualTo(SHA);
            assertThat(copy.getFileHistory()).isEqualTo("history");
            assertThat(copy.isCandidateRecall()).isTrue();
            assertThat(copy.getGuidanceGlobs()).containsExactly("docs/rules/*.md");
        }

        @Test
        void baseCommitContextKeepsGuidanceGlobs() {
            PRReviewRequest copy = full().withBaseCommitContext("g", "h", "c");

            assertThat(copy.getGuidanceGlobs()).containsExactly("docs/rules/*.md");
            assertThat(copy.getRepoGuidelines()).isEqualTo("g");
        }

        @Test
        void aNewDiffKeepsEveryOtherFieldAndTheSourceCoverage() {
            PRReviewRequest source = full();
            PRReviewRequest copy = source.toBuilder("other").customInstructions("new").build();

            assertThat(copy.getDiff()).isEqualTo("other");
            assertThat(copy.getCustomInstructions()).isEqualTo("new");
            assertThat(copy.diffCoverage()).isEqualTo(source.diffCoverage());
            assertThat(copy.getBaseSha()).isEqualTo(SHA);
            assertThat(copy.getFileHistory()).isEqualTo("history");
            assertThat(copy.getCallSites()).isEqualTo("sites");
            assertThat(copy.isCandidateRecall()).isTrue();
            assertThat(copy.getSemanticContext().getEvidence()).isEqualTo("evidence");
        }

        @Test
        void newFieldsDefaultToAbsent() {
            PRReviewRequest request = new PRReviewRequest(pr(), "diff");

            assertThat(request.getBaseSha()).isNull();
            assertThat(request.getFileHistory()).isNull();
            assertThat(request.getCallSites()).isNull();
            assertThat(request.isCandidateRecall()).isFalse();
        }

        @Test
        void withSemanticContextPreservesTheNewFields() {
            PRReviewRequest deep = full().withSemanticContext(new SemanticReviewContext());

            assertThat(deep.getBaseSha()).isEqualTo(SHA);
            assertThat(deep.getFileHistory()).isEqualTo("history");
            assertThat(deep.isCandidateRecall()).isTrue();
        }

        @Test
        void withBaseCommitContextReplacesGuidanceAndHistoryOnly() {
            PRReviewRequest source = full();
            PRReviewRequest enriched =
                    source.withBaseCommitContext("base rules", "base history", "base sites");

            assertThat(enriched.getRepoGuidelines()).isEqualTo("base rules");
            assertThat(enriched.getFileHistory()).isEqualTo("base history");
            assertThat(enriched.getCallSites()).isEqualTo("base sites");
            assertThat(enriched.getCustomInstructions()).isEqualTo("custom");
            assertThat(source.getRepoGuidelines()).isEqualTo("guidelines");
        }

        @Test
        void withCandidateRecallTogglesOnlyTheFlag() {
            PRReviewRequest off = full().withCandidateRecall(false);

            assertThat(off.isCandidateRecall()).isFalse();
            assertThat(off.getBaseSha()).isEqualTo(SHA);
            assertThat(off.withCandidateRecall(true).isCandidateRecall()).isTrue();
        }
    }
}
