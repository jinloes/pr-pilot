package com.jinloes.claudereviews.model;

/**
 * Bundles the data needed to generate a PR review: the pull-request metadata, the unified diff, any
 * previously-verified patterns that should be excluded from re-flagging, and project conventions
 * (CLAUDE.md / AGENTS.md content from the open project root).
 */
public record PRReviewRequest(
        PullRequest pr, String diff, String knownPatterns, String projectConventions) {}
