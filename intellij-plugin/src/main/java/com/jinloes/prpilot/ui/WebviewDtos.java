package com.jinloes.prpilot.ui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Outbound DTO records serialized to JSON and pushed to the webview via the bridge. Kept
 * package-private so they are visible to {@link ReviewMapper} and {@link WebviewPanel} but not
 * exposed beyond the {@code ui} package.
 */
record ReviewResultDto(
        String summary,
        String verdict,
        @JsonProperty("lineComments") List<LineCommentDto> lineComments) {}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
record LineCommentDto(
        String file,
        int line,
        String type,
        String body,
        String severity,
        String category,
        String confidence,
        String rationale,
        List<String> sources) {}
