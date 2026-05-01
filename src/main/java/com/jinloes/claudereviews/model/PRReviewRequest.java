package com.jinloes.claudereviews.model;

/**
 * Bundles the data needed to generate a PR review: the pull-request metadata, the unified diff, any
 * previously-verified patterns that should be excluded from re-flagging, project conventions
 * (CLAUDE.md / AGENTS.md content from the open project root), an optional prior review result to
 * use as refinement context on re-generation, and a formatted summary of reviews already submitted
 * by other reviewers so Claude avoids repeating their findings.
 */
public record PRReviewRequest(
        PullRequest pr,
        String diff,
        String knownPatterns,
        String projectConventions,
        String priorReview,
        String existingReviews) {

    /** Convenience constructor with no prior review or existing reviews context. */
    public PRReviewRequest(
            PullRequest pr, String diff, String knownPatterns, String projectConventions) {
        this(pr, diff, knownPatterns, projectConventions, null, null);
    }

    /** Convenience constructor with no existing reviews context. */
    public PRReviewRequest(
            PullRequest pr,
            String diff,
            String knownPatterns,
            String projectConventions,
            String priorReview) {
        this(pr, diff, knownPatterns, projectConventions, priorReview, null);
    }
}
