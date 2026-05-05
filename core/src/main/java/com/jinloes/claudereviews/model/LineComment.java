package com.jinloes.claudereviews.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LineComment {
    private String file;
    private int line;
    private String type; // "issue", "suggestion", "note"
    private String body;

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

    public void setFile(String file) {
        this.file = file;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getType() {
        return type != null ? type.toLowerCase() : "note";
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBody() {
        return body != null ? body : "";
    }

    public void setBody(String body) {
        this.body = body;
    }
}
