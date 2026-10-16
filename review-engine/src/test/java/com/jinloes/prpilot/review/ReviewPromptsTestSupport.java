package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;

/** Shared fixtures for the review prompt tests. */
final class ReviewPromptsTestSupport {
    private ReviewPromptsTestSupport() {}

    static PullRequest fakePr() {
        return new PullRequest(
                "T", "https://github.com/o/r/pull/1", "o", "r", 1, "", "a", "2024-01-01");
    }

    static PRReviewRequest fakeRequest() {
        return new PRReviewRequest(fakePr(), "");
    }
}
