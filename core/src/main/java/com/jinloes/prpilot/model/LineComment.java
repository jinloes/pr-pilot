package com.jinloes.prpilot.model;

import java.util.List;
import java.util.Objects;

/**
 * A single inline comment on a diff line. Mutable setters are preserved for Jackson deserialization
 * and for Java interop from intellij-plugin.
 *
 * <p>The richer fields ({@link #getSeverity()}, {@link #getCategory()}, {@link #getConfidence()},
 * {@link #getRationale()}) are optional and default to empty/null so legacy drafts and older
 * provider output deserialize unchanged. They let the UI sort, filter, and explain findings beyond
 * the coarse {@link #getType()} bucket.
 *
 * <p>{@link #getSuggestedChange()} is the literal replacement text for the anchored line, published
 * as a GitHub suggestion block. The review engine validates it before it reaches any host.
 *
 * <p>{@link #getSources()} names the reviewer model(s) that reported the finding. The review engine
 * sets it; model output never does.
 */
public final class LineComment {

    private String file;
    private int line;
    private String type;
    private String body;
    private String severity;
    private String category;
    private String confidence;
    private String rationale;
    private String suggestedChange;
    private List<String> sources = List.of();

    public LineComment() {}

    public LineComment(String file, int line, String type, String body) {
        this.file = file;
        this.line = line;
        this.type = type;
        this.body = body;
    }

    public String getFile() {
        return file != null ? file : "";
    }

    public void setFile(String value) {
        this.file = value;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int value) {
        this.line = value;
    }

    public String getType() {
        return type != null ? type.toLowerCase(java.util.Locale.ROOT) : "note";
    }

    public void setType(String value) {
        this.type = value;
    }

    public String getBody() {
        return body != null ? body : "";
    }

    public void setBody(String value) {
        this.body = value;
    }

    /** Severity bucket (blocker|major|minor|nit), or empty when the model omitted it. */
    public String getSeverity() {
        return severity != null ? severity.toLowerCase(java.util.Locale.ROOT) : "";
    }

    public void setSeverity(String value) {
        this.severity = value;
    }

    /** Category (correctness|security|performance|tests|maintainability|style), or empty. */
    public String getCategory() {
        return category != null ? category.toLowerCase(java.util.Locale.ROOT) : "";
    }

    public void setCategory(String value) {
        this.category = value;
    }

    /** Model confidence (low|medium|high), or empty when omitted. */
    public String getConfidence() {
        return confidence != null ? confidence.toLowerCase(java.util.Locale.ROOT) : "";
    }

    public void setConfidence(String value) {
        this.confidence = value;
    }

    /** Short evidence/justification separate from the comment body, or empty. */
    public String getRationale() {
        return rationale != null ? rationale : "";
    }

    public void setRationale(String value) {
        this.rationale = value;
    }

    /** Literal replacement for the anchored line, or empty when the finding has no suggestion. */
    public String getSuggestedChange() {
        return suggestedChange != null ? suggestedChange : "";
    }

    public void setSuggestedChange(String value) {
        this.suggestedChange = value;
    }

    /** Reviewer labels that reported this finding; never null, empty when unattributed. */
    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> value) {
        this.sources = value != null ? value.stream().filter(Objects::nonNull).toList() : List.of();
    }
}
