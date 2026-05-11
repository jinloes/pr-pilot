package com.jinloes.prpilot.model

import kotlinx.serialization.Serializable

/**
 * Bundles the data needed to generate a PR review: the pull-request metadata, the unified diff,
 * any previously-verified patterns that should be excluded from re-flagging, an optional prior
 * review result to use as refinement context on re-generation, and a formatted summary of reviews
 * already submitted by other reviewers so Claude avoids repeating their findings.
 */
@Serializable
data class PRReviewRequest(
    val pr: PullRequest,
    val diff: String,
    val knownPatterns: String,
    val priorReview: String? = null,
    val existingReviews: String? = null,
) {
    /** Convenience constructor with no prior review or existing reviews. */
    constructor(pr: PullRequest, diff: String, knownPatterns: String) :
        this(pr, diff, knownPatterns, null, null)

    /** Convenience constructor with no existing reviews. */
    constructor(pr: PullRequest, diff: String, knownPatterns: String, priorReview: String?) :
        this(pr, diff, knownPatterns, priorReview, null)
}
