package com.jinloes.claudereviews.model;

/**
 * Bundles the data needed to generate a PR review: the pull-request metadata, the unified diff, and
 * any previously-verified patterns that should be excluded from re-flagging.
 */
public record PRReviewRequest(PullRequest pr, String diff, String knownPatterns) {}
