package com.jinloes.claudereviews.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A saved-draft snapshot: PR metadata + the (possibly user-edited) review result + raw diff.
 * Persisted as JSON by {@link com.jinloes.claudereviews.services.DraftService}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewDraft {
    public String owner;
    public String repo;
    public int number;
    public String title;
    public String author;
    public String prBody;
    public String rawDiff;
    public String savedAt; // ISO-8601 local datetime, e.g. "2026-04-01T14:32:00"
    public ReviewResult review;

    /** Display label used in the load-draft list. */
    public String label() {
        return owner + "/" + repo + " #" + number + " — " + title;
    }
}
