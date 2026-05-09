package com.jinloes.claudereviews.model

import kotlinx.serialization.Serializable

/**
 * Bundles the data needed to generate a PR review: the pull-request metadata, the unified diff,
 * any previously-verified patterns that should be excluded from re-flagging, an optional prior
 * review result to use as refinement context on re-generation, a formatted summary of reviews
 * already submitted by other reviewers so Claude avoids repeating their findings, and an optional
 * type context block containing method/field signatures extracted from the project source via the
 * IDE's PSI index.
 */
@Serializable
data class PRReviewRequest(
    val pr: PullRequest,
    val diff: String,
    val knownPatterns: String,
    val priorReview: String? = null,
    val existingReviews: String? = null,
    val typeContext: String? = null,
) {
    /** Convenience constructor with no prior review, existing reviews, or type context. */
    constructor(pr: PullRequest, diff: String, knownPatterns: String) :
        this(pr, diff, knownPatterns, null, null, null)

    /** Convenience constructor with no existing reviews or type context. */
    constructor(pr: PullRequest, diff: String, knownPatterns: String, priorReview: String?) :
        this(pr, diff, knownPatterns, priorReview, null, null)

    /** Convenience constructor with no type context. */
    constructor(
        pr: PullRequest,
        diff: String,
        knownPatterns: String,
        priorReview: String?,
        existingReviews: String?,
    ) : this(pr, diff, knownPatterns, priorReview, existingReviews, null)

}
