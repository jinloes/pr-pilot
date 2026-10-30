package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One review finding anchored to a file, from either reviewer. {@code line} is 0 when the finding
 * is file-level. {@code reason} is set only on a finding validation dropped, and says why.
 */
record Finding(
        String id,
        String path,
        int line,
        String body,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

    Finding(String id, String path, int line, String body) {
        this(id, path, line, body, null);
    }
}
