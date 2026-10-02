package com.jinloes.prpilot.review;

record CoverageGap(
        String id, String targetId, String path, int newStart, String reason, int priority) {

    /** Priority of a high-risk changed hunk the primary review never inspected. */
    static final int HUNK_PRIORITY = 100;

    /** Priority of a changed file the primary review never mentioned at all. */
    static final int FILE_PRIORITY = 50;

    /** Whether this gap is a whole changed file rather than a single hunk. */
    boolean wholeFile() {
        return priority == FILE_PRIORITY;
    }
}
