package com.jinloes.prpilot.sidecar.pr;

/** Token-free bounded review-diff result returned by {@code prs/getDiff}. */
public record PrDiffResult(
        String status, String message, String diff, boolean truncated, int limitBytes) {
    public static final String STATUS_NOT_FOUND_OR_INACCESSIBLE = "not_found_or_inaccessible";
    public static final String STATUS_DIFF_TOO_LARGE = "diff_too_large";

    static PrDiffResult success(String diff, boolean truncated, int limitBytes) {
        return new PrDiffResult("ok", "Pull request diff loaded.", diff, truncated, limitBytes);
    }

    static PrDiffResult failure(String status, String message) {
        return new PrDiffResult(status, message, null, false, 250_000);
    }
}
