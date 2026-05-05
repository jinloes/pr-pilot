package com.jinloes.claudereviews.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewResult {
    private String summary;
    private List<LineComment> lineComments;
    private String verdict; // "APPROVE", "REQUEST_CHANGES", "COMMENT"

    public ReviewResult() {}

    public ReviewResult(String summary, String verdict, List<LineComment> lineComments) {
        this.summary = summary;
        this.verdict = verdict;
        this.lineComments =
                lineComments != null
                        ? new java.util.ArrayList<>(lineComments)
                        : new java.util.ArrayList<>();
    }

    public String getSummary() {
        return summary != null ? summary : "";
    }

    public List<LineComment> getLineComments() {
        if (lineComments == null) lineComments = new java.util.ArrayList<>();
        return lineComments;
    }

    public String getVerdict() {
        return verdict != null ? verdict : "COMMENT";
    }
}
