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
