package com.jinloes.prpilot.sidecar.pr;

/**
 * Token-free pull-request metadata required for selection and worktree resolution. {@code baseSha}
 * is the base branch commit, null unless GitHub reported a well-formed 40/64-hex object name.
 */
public record PrDetail(
        boolean merged,
        String title,
        String body,
        Head head,
        String baseRepoFullName,
        String baseSha) {
    public record Head(String sha, String ref, String repoFullName, String cloneUrl) {}
}
