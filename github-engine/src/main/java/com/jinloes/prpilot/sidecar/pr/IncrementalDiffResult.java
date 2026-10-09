package com.jinloes.prpilot.sidecar.pr;

/**
 * Token-free result of {@code prs/getIncrementalDiff}. On {@code ok}, {@code scope} is {@code
 * incremental} with a bounded baseline..head diff, or {@code full} with no diff and a {@code
 * fallbackReason}; any other status carries no scope.
 */
public record IncrementalDiffResult(
        String status,
        String message,
        String scope,
        String fallbackReason,
        String baselineSha,
        String headSha,
        String diff,
        boolean truncated,
        int limitBytes) {
    public static final String SCOPE_INCREMENTAL = "incremental";
    public static final String SCOPE_FULL = "full";
    public static final String NO_PRIOR_REVIEW = "no_prior_review";
    public static final String UP_TO_DATE = "up_to_date";
    public static final String BASELINE_NOT_IN_HISTORY = "baseline_not_in_history";
    public static final String BASELINE_UNAVAILABLE = "baseline_unavailable";
    public static final String EMPTY_INCREMENTAL_DIFF = "empty_incremental_diff";
    public static final String INCREMENTAL_DIFF_TOO_LARGE = "incremental_diff_too_large";

    static IncrementalDiffResult incremental(
            String baselineSha, String headSha, String diff, boolean truncated, int limitBytes) {
        return new IncrementalDiffResult(
                "ok",
                "Changes since your last review loaded.",
                SCOPE_INCREMENTAL,
                null,
                baselineSha,
                headSha,
                diff,
                truncated,
                limitBytes);
    }

    static IncrementalDiffResult fullFallback(
            String reason, String baselineSha, String headSha, int limitBytes) {
        return new IncrementalDiffResult(
                "ok",
                "Falling back to a full review.",
                SCOPE_FULL,
                reason,
                baselineSha,
                headSha,
                null,
                false,
                limitBytes);
    }

    static IncrementalDiffResult failure(String status, String message, int limitBytes) {
        return new IncrementalDiffResult(
                status, message, null, null, null, null, null, false, limitBytes);
    }
}
