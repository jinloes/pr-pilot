package com.jinloes.prpilot.model;

/**
 * Bundles the data needed to generate a PR review: the pull-request metadata and unified diff, plus
 * the optional context sections that let a review be judged against something more than the diff
 * alone — CI results, commit messages, the issue the PR claims to close, the repository's stack,
 * project guidelines, and reviews already submitted.
 *
 * <p>Every context field is optional. A null or blank value omits its prompt section entirely, so a
 * caller that cannot supply one degrades to exactly the previous behavior rather than failing.
 *
 * <p>Built through {@link #builder}: the fields are almost all strings, so positional construction
 * would silently accept a wrong argument order.
 */
public final class PRReviewRequest {

    private final PullRequest pr;
    private final String diff;
    private final String priorReview;
    private final String existingReviews;
    private final String repoGuidelines;
    private final String focusAreas;
    private final String customInstructions;
    private final String ciStatus;
    private final String commits;
    private final String linkedIssue;
    private final String repoProfile;
    private final java.util.List<CiAnnotation> ciAnnotations;
    private final SemanticReviewContext semanticContext;

    private PRReviewRequest(Builder builder) {
        this.pr = builder.pr;
        this.diff = builder.diff;
        this.priorReview = builder.priorReview;
        this.existingReviews = builder.existingReviews;
        this.repoGuidelines = builder.repoGuidelines;
        this.focusAreas = builder.focusAreas;
        this.customInstructions = builder.customInstructions;
        this.ciStatus = builder.ciStatus;
        this.commits = builder.commits;
        this.linkedIssue = builder.linkedIssue;
        this.repoProfile = builder.repoProfile;
        this.semanticContext = copySemanticContext(builder.semanticContext);
        this.ciAnnotations =
                builder.ciAnnotations == null
                        ? java.util.List.of()
                        : copyCiAnnotations(builder.ciAnnotations);
    }

    /** Creates a request with only the two required inputs and no optional context. */
    public PRReviewRequest(PullRequest pr, String diff) {
        this(builder(pr, diff));
    }

    public static Builder builder(PullRequest pr, String diff) {
        return new Builder(pr, diff);
    }

    public PullRequest getPr() {
        return pr;
    }

    public String getDiff() {
        return diff;
    }

    public String getPriorReview() {
        return priorReview;
    }

    public String getExistingReviews() {
        return existingReviews;
    }

    public String getRepoGuidelines() {
        return repoGuidelines;
    }

    public String getFocusAreas() {
        return focusAreas;
    }

    public String getCustomInstructions() {
        return customInstructions;
    }

    /** Rendered CI check state for the head commit; empty when CI reported nothing. */
    public String getCiStatus() {
        return ciStatus;
    }

    /** Rendered commit-message list, carrying the author's stated intent. */
    public String getCommits() {
        return commits;
    }

    /** Rendered summary of the issues this PR declares it closes. */
    public String getLinkedIssue() {
        return linkedIssue;
    }

    /** Rendered language and build-tooling profile of the repository. */
    public String getRepoProfile() {
        return repoProfile;
    }

    /**
     * Structured CI findings behind {@link #getCiStatus()}. Unlike the rendered summary these are
     * machine-comparable, which is what lets duplicate review comments be dropped
     * deterministically. Never null.
     */
    public java.util.List<CiAnnotation> getCiAnnotations() {
        return copyCiAnnotations(ciAnnotations);
    }

    public SemanticReviewContext getSemanticContext() {
        return copySemanticContext(semanticContext);
    }

    public PRReviewRequest withSemanticContext(SemanticReviewContext value) {
        return builder(pr, diff)
                .priorReview(priorReview)
                .existingReviews(existingReviews)
                .repoGuidelines(repoGuidelines)
                .focusAreas(focusAreas)
                .customInstructions(customInstructions)
                .ciStatus(ciStatus)
                .commits(commits)
                .linkedIssue(linkedIssue)
                .repoProfile(repoProfile)
                .ciAnnotations(ciAnnotations)
                .semanticContext(value)
                .build();
    }

    private static SemanticReviewContext copySemanticContext(SemanticReviewContext value) {
        if (value == null) return null;
        SemanticReviewContext copy = new SemanticReviewContext();
        copy.setEvidence(value.getEvidence());
        copy.setLimitations(value.getLimitations());
        return copy;
    }

    private static java.util.List<CiAnnotation> copyCiAnnotations(
            java.util.List<CiAnnotation> annotations) {
        return annotations.stream().map(CiAnnotation::copyOf).toList();
    }

    /** Fluent builder; every context setter is optional. */
    public static final class Builder {
        private final PullRequest pr;
        private final String diff;
        private String priorReview;
        private String existingReviews;
        private String repoGuidelines;
        private String focusAreas;
        private String customInstructions;
        private String ciStatus;
        private String commits;
        private String linkedIssue;
        private String repoProfile;
        private java.util.List<CiAnnotation> ciAnnotations = java.util.List.of();
        private SemanticReviewContext semanticContext;

        private Builder(PullRequest pr, String diff) {
            this.pr = pr;
            this.diff = diff;
        }

        public Builder priorReview(String value) {
            this.priorReview = value;
            return this;
        }

        public Builder existingReviews(String value) {
            this.existingReviews = value;
            return this;
        }

        public Builder repoGuidelines(String value) {
            this.repoGuidelines = value;
            return this;
        }

        public Builder focusAreas(String value) {
            this.focusAreas = value;
            return this;
        }

        public Builder customInstructions(String value) {
            this.customInstructions = value;
            return this;
        }

        public Builder ciStatus(String value) {
            this.ciStatus = value;
            return this;
        }

        public Builder commits(String value) {
            this.commits = value;
            return this;
        }

        public Builder linkedIssue(String value) {
            this.linkedIssue = value;
            return this;
        }

        public Builder repoProfile(String value) {
            this.repoProfile = value;
            return this;
        }

        /** Structured CI findings, used to drop review comments that merely restate them. */
        public Builder ciAnnotations(java.util.List<CiAnnotation> value) {
            this.ciAnnotations = value;
            return this;
        }

        public PRReviewRequest build() {
            return new PRReviewRequest(this);
        }

        public Builder semanticContext(SemanticReviewContext value) {
            this.semanticContext = copySemanticContext(value);
            return this;
        }
    }
}
