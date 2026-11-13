package com.jinloes.prpilot.model;

import java.util.List;

/**
 * What a PR chat question is asked about, as structured fields. Hosts fill in what they hold and
 * the review engine decides how much of each part fits in the prompt, so both hosts give the model
 * the same context.
 *
 * @param owner repository owner of the pull request
 * @param repo repository name of the pull request
 * @param number pull request number; 0 when no pull request is selected
 * @param title pull request title
 * @param author pull request author login
 * @param body pull request description
 * @param diff unified diff of the pull request, optionally ending in a {@link DiffCoverage} trailer
 * @param reviewVerdict verdict of the generated review, when one exists
 * @param reviewSummary summary of the generated review, when one exists
 * @param reviewComments line comments of the generated review
 * @param attachment text the reviewer selected or a draft comment they asked about, when present
 */
public record ChatContext(
        String owner,
        String repo,
        int number,
        String title,
        String author,
        String body,
        String diff,
        String reviewVerdict,
        String reviewSummary,
        List<ReviewComment> reviewComments,
        Attachment attachment) {

    public ChatContext {
        reviewComments = reviewComments == null ? List.of() : List.copyOf(reviewComments);
    }

    /** True when the context names a pull request, so the provider runs in its worktree. */
    public boolean hasPullRequest() {
        return number > 0;
    }

    /** Returns this context with {@code attachment} in place of the current one. */
    public ChatContext withAttachment(Attachment attachment) {
        return new ChatContext(
                owner,
                repo,
                number,
                title,
                author,
                body,
                diff,
                reviewVerdict,
                reviewSummary,
                reviewComments,
                attachment);
    }

    /** One generated review comment, reduced to what a chat answer can refer to. */
    public record ReviewComment(String file, int line, String type, String severity, String body) {
        /** Converts a review line comment. */
        public static ReviewComment of(LineComment comment) {
            return new ReviewComment(
                    comment.getFile(),
                    comment.getLine(),
                    comment.getType(),
                    comment.getSeverity(),
                    comment.getBody());
        }
    }

    /**
     * Text attached to one question: a selection from the review pane, or a draft comment with its
     * diff excerpt. {@code file} and the lines are present when the text came from one place in the
     * diff.
     *
     * @param text the attached text
     * @param file repository-relative path the text came from, or null
     * @param startLine first line of the text in {@code file}, or 0 when unknown
     * @param endLine last line of the text in {@code file}, or 0 when unknown
     * @param oldSide true when the lines are pre-change line numbers (deleted lines)
     */
    public record Attachment(
            String text, String file, int startLine, int endLine, boolean oldSide) {
        /** Location as {@code path:start-end}, {@code path:line}, {@code path}, or empty. */
        public String location() {
            if (file == null || file.isBlank()) return "";
            if (startLine <= 0) return file;
            String side = oldSide ? " (pre-change lines)" : "";
            if (endLine <= startLine) return file + ":" + startLine + side;
            return file + ":" + startLine + "-" + endLine + side;
        }
    }
}
