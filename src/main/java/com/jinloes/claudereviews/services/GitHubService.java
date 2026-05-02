package com.jinloes.claudereviews.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.jinloes.claudereviews.model.LineComment;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubService {

    private static final int MAX_DIFF_BYTES = 80_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Markers embedded in the GitHub review body to round-trip our metadata
    private static final String VERDICT_TAG = "<!-- claude-verdict: ";
    private static final String SUMMARY_TAG = "<!-- claude-summary: ";
    private static final String COMMENTS_TAG = "<!-- claude-comments: ";
    private static final String TAG_END = " -->";
    private static final Pattern TYPE_PREFIX = Pattern.compile("^\\[([A-Z]+)]\\s*");

    /** Carries a pending review ID together with its decoded {@link ReviewResult}. */
    public record PendingReview(String id, ReviewResult result) {}

    /**
     * Result of saving a draft review. {@code commentsDropped} is {@code true} when inline comments
     * were omitted because GitHub rejected them (422 — invalid path or line number).
     */
    public record SaveDraftResult(String reviewId, boolean commentsDropped) {}

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public static GitHubService getInstance() {
        return ApplicationManager.getApplication().getService(GitHubService.class);
    }

    private String apiBase() {
        return com.jinloes.claudereviews.settings.PluginSettings.getInstance().getApiBaseUrl();
    }

    // --- PR search (assigned / review-requested / authored) ---

    public List<PullRequest> searchPRs(String token, String query)
            throws IOException, InterruptedException {
        String url =
                apiBase()
                        + "/search/issues?q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&per_page=50&sort=updated";
        String body = get(token, url, "application/vnd.github.v3+json");
        return parseSearchResults(body);
    }

    private List<PullRequest> parseSearchResults(String json) throws IOException {
        SearchResult result = MAPPER.readValue(json, SearchResult.class);
        List<PullRequest> prs = new ArrayList<>();
        for (PrItem el : result.items()) {
            String repoUrl = el.repositoryUrl() != null ? el.repositoryUrl() : "";
            String[] parts = repoUrl.split("/");
            String owner = parts.length >= 2 ? parts[parts.length - 2] : "";
            String repo = parts.length >= 1 ? parts[parts.length - 1] : "";
            String login = el.user() != null ? el.user().login() : "";
            String body = el.body() != null ? el.body() : "";
            prs.add(
                    new PullRequest(
                            el.title(),
                            el.htmlUrl(),
                            owner,
                            repo,
                            el.number(),
                            body,
                            login,
                            el.createdAt() != null ? el.createdAt() : ""));
        }
        return prs;
    }

    // --- Starred repos ---

    public List<String> getStarredRepos(String token) throws IOException, InterruptedException {
        List<String> repos = new ArrayList<>();
        int page = 1;
        while (repos.size() < 200) {
            String url = apiBase() + "/user/starred?per_page=100&sort=updated&page=" + page;
            String body = get(token, url, "application/vnd.github.v3+json");
            List<StarredRepo> items =
                    MAPPER.readValue(
                            body,
                            MAPPER.getTypeFactory()
                                    .constructCollectionType(List.class, StarredRepo.class));
            if (items.isEmpty()) break;
            items.stream().map(StarredRepo::fullName).forEach(repos::add);
            if (items.size() < 100) break;
            page++;
        }
        return repos;
    }

    // --- PR diff ---

    public String getPRDiff(String token, String owner, String repo, int prNumber)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        String diff = get(token, url, "application/vnd.github.v3.diff");
        if (diff.length() > MAX_DIFF_BYTES) {
            diff = diff.substring(0, MAX_DIFF_BYTES) + "\n\n[... diff truncated at 80 KB ...]";
        }
        return diff;
    }

    // --- Draft review API ---

    /**
     * Returns the review ID of any PENDING (draft) review for this PR created by the authenticated
     * user, or {@code null} if none exists.
     */
    public String getPendingReviewId(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number + "/reviews";
        List<GhReview> reviews =
                MAPPER.readValue(
                        get(token, url, "application/vnd.github.v3+json"),
                        MAPPER.getTypeFactory()
                                .constructCollectionType(List.class, GhReview.class));
        for (GhReview r : reviews) {
            if ("PENDING".equals(r.state())) return String.valueOf(r.id());
        }
        return null;
    }

    /**
     * Saves a {@link ReviewResult} as a GitHub pending (draft) review. Any existing pending review
     * is deleted first so there is never more than one. Returns the new review ID.
     */
    public SaveDraftResult saveDraftReview(
            String token, String owner, String repo, int number, ReviewResult review)
            throws IOException, InterruptedException {
        // Delete stale draft if present. Ignore failures: the review may have just been submitted
        // (its state transitions from PENDING to APPROVED/etc. asynchronously on GitHub's side),
        // in which case getPendingReviewId still returns the old ID but the delete is rejected.
        String existing = getPendingReviewId(token, owner, repo, number);
        if (existing != null) {
            try {
                deleteDraftReview(token, owner, repo, number, existing);
            } catch (IOException ignored) {
                // non-fatal: the review is already gone or in a non-deletable state
            }
        }

        String headSha = getPRHeadSha(token, owner, repo, number);

        ArrayNode comments = buildCommentArray(review);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("commit_id", headSha);
        payload.put("body", encodeBody(review));
        // Omitting "event" is what creates a PENDING (draft) review.
        // Setting event:"PENDING" is invalid and causes a 422.
        payload.set("comments", comments);

        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number + "/reviews";
        String response;
        boolean commentsDropped = false;
        try {
            response = post(token, url, payload.toString());
        } catch (IOException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("422")) {
                // One or more inline comments have an invalid path/line for this commit.
                // Try to save each comment individually so only the bad ones are dropped.
                ArrayNode valid = tryCommentsIndividually(token, url, payload, comments);
                commentsDropped = valid.size() < comments.size();
                payload.set("comments", valid);
                response = post(token, url, payload.toString());
            } else {
                throw ex;
            }
        }
        String reviewId = String.valueOf(MAPPER.readTree(response).path("id").asLong());
        return new SaveDraftResult(reviewId, commentsDropped);
    }

    /** Builds the deduplicated inline-comment array from a {@link ReviewResult}. */
    static ArrayNode buildCommentArray(ReviewResult review) {
        ArrayNode comments = MAPPER.createArrayNode();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (LineComment c : review.getLineComments()) {
            String file = c.getFile();
            if (file.startsWith("a/") || file.startsWith("b/")) file = file.substring(2);
            if (file.isBlank() || c.getLine() <= 0 || c.getBody().isBlank()) continue;
            // Deduplicate: skip exact duplicates (same file, line, and body).
            if (!seen.add(file + "\0" + c.getLine() + "\0" + c.getBody())) continue;
            ObjectNode comment = MAPPER.createObjectNode();
            comment.put("path", file);
            comment.put("line", c.getLine());
            comment.put("side", "RIGHT");
            comment.put("body", c.getBody());
            comments.add(comment);
        }
        return comments;
    }

    /**
     * Probes each comment individually to find the valid subset. Creates and immediately deletes a
     * temporary single-comment review per probe, keeping only comments that GitHub accepts. Used
     * when the full batch fails with 422.
     */
    private ArrayNode tryCommentsIndividually(
            String token, String url, ObjectNode basePayload, ArrayNode comments)
            throws IOException, InterruptedException {
        ArrayNode valid = MAPPER.createArrayNode();
        for (int i = 0; i < comments.size(); i++) {
            ArrayNode single = MAPPER.createArrayNode();
            single.add(comments.get(i));
            ObjectNode probe = basePayload.deepCopy();
            probe.set("comments", single);
            try {
                String resp = post(token, url, probe.toString());
                // Probe succeeded — immediately delete the temp review, mark comment valid.
                String tempId = String.valueOf(MAPPER.readTree(resp).path("id").asLong());
                try {
                    delete(token, url + "/" + tempId);
                } catch (IOException ignored) {
                    // best-effort cleanup; orphan will be removed on next saveDraftReview call
                }
                valid.add(comments.get(i));
            } catch (IOException ignored) {
                // This comment is invalid for the current commit — skip it.
            }
        }
        return valid;
    }

    /**
     * Loads the pending draft review for a PR, returning a {@link PendingReview} that contains both
     * the review ID and the decoded {@link ReviewResult}, or {@code null} if no pending review
     * exists.
     */
    public PendingReview loadDraftReview(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        String id = getPendingReviewId(token, owner, repo, number);
        if (id == null) return null;

        String reviewUrl =
                apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number + "/reviews/" + id;
        GhReview review =
                MAPPER.readValue(
                        get(token, reviewUrl, "application/vnd.github.v3+json"), GhReview.class);
        String body = review.body() != null ? review.body() : "";

        List<GhReviewComment> ghComments =
                MAPPER.readValue(
                        get(token, reviewUrl + "/comments", "application/vnd.github.v3+json"),
                        MAPPER.getTypeFactory()
                                .constructCollectionType(List.class, GhReviewComment.class));

        return new PendingReview(id, decodeReview(body, ghComments));
    }

    /**
     * Submits (publishes) a pending review. {@code event} must be one of {@code "APPROVE"}, {@code
     * "REQUEST_CHANGES"}, or {@code "COMMENT"}. {@code body} is an optional top-level comment
     * included with the submission (empty string means no comment).
     */
    public void submitDraftReview(
            String token,
            String owner,
            String repo,
            int number,
            String reviewId,
            String event,
            String body)
            throws IOException, InterruptedException {
        String url =
                apiBase()
                        + "/repos/"
                        + owner
                        + "/"
                        + repo
                        + "/pulls/"
                        + number
                        + "/reviews/"
                        + reviewId
                        + "/events";
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("event", event);
        payload.put("body", body);
        post(token, url, payload.toString());
    }

    /** Deletes a pending (unsubmitted) review. */
    public void deleteDraftReview(
            String token, String owner, String repo, int number, String reviewId)
            throws IOException, InterruptedException {
        String url =
                apiBase()
                        + "/repos/"
                        + owner
                        + "/"
                        + repo
                        + "/pulls/"
                        + number
                        + "/reviews/"
                        + reviewId;
        delete(token, url);
    }

    /**
     * Returns a formatted plain-text summary of all submitted (non-pending) reviews for a PR,
     * including each reviewer's verdict, overall comment, and inline comments. Returns an empty
     * string if there are no submitted reviews. Non-fatal: inline comment fetches are best-effort.
     */
    public String getExistingReviewsSummary(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number + "/reviews";
        List<GhSubmittedReview> reviews =
                MAPPER.readValue(
                        get(token, url, "application/vnd.github.v3+json"),
                        MAPPER.getTypeFactory()
                                .constructCollectionType(List.class, GhSubmittedReview.class));
        List<GhSubmittedReview> submitted =
                reviews.stream().filter(r -> !"PENDING".equals(r.state())).toList();
        if (submitted.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (GhSubmittedReview r : submitted) {
            String reviewer = r.user() != null ? "@" + r.user().login() : "unknown";
            String state = r.state() != null ? r.state() : "COMMENTED";
            String date =
                    r.submittedAt() != null && r.submittedAt().length() >= 10
                            ? r.submittedAt().substring(0, 10)
                            : "";
            sb.append("Review by ")
                    .append(reviewer)
                    .append(" (")
                    .append(state)
                    .append(date.isBlank() ? "" : ", " + date)
                    .append("):\n");
            String body = r.body() != null ? r.body().strip() : "";
            if (!body.isBlank()) {
                String oneLine = body.replace("\n", " ");
                sb.append("  Overall: \"").append(truncate(oneLine, 300)).append("\"\n");
            }
            // Fetch inline comments for this review (best-effort)
            try {
                List<GhReviewComment> comments =
                        MAPPER.readValue(
                                get(
                                        token,
                                        url + "/" + r.id() + "/comments",
                                        "application/vnd.github.v3+json"),
                                MAPPER.getTypeFactory()
                                        .constructCollectionType(
                                                List.class, GhReviewComment.class));
                for (GhReviewComment c : comments) {
                    String path = c.path() != null ? c.path() : "";
                    int line =
                            c.line() != null
                                    ? c.line()
                                    : (c.originalLine() != null ? c.originalLine() : 0);
                    String text = c.body() != null ? c.body().strip().replace("\n", " ") : "";
                    if (text.isBlank()) continue;
                    sb.append("  - ").append(path);
                    if (line > 0) sb.append(":").append(line);
                    sb.append(": \"").append(truncate(text, 200)).append("\"\n");
                }
            } catch (Exception ignored) {
                // Non-fatal: inline comments are optional context
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    /** Returns {@code true} if the pull request has been merged. */
    public boolean isPRMerged(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number;
        return MAPPER.readValue(get(token, url, "application/vnd.github.v3+json"), PrDetail.class)
                .merged();
    }

    /** Fetches the HEAD commit SHA for a pull request. */
    public String getPRHeadSha(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number;
        PrDetail detail =
                MAPPER.readValue(get(token, url, "application/vnd.github.v3+json"), PrDetail.class);
        return detail.head() != null ? detail.head().sha() : "";
    }

    // --- Encode / decode ReviewResult ↔ GitHub review body + comments ---

    static String encodeBody(ReviewResult review) {
        // Summary is stored in a hidden HTML comment so it round-trips without appearing on GitHub
        StringBuilder sb =
                new StringBuilder(SUMMARY_TAG)
                        .append(escapeComment(review.getSummary()))
                        .append(TAG_END);
        sb.append("\n")
                .append(VERDICT_TAG)
                .append(escapeComment(review.getVerdict()))
                .append(TAG_END);

        // Embed all comments as JSON so line numbers survive GitHub's position remapping.
        // Escape "-->" in the serialized JSON to prevent early termination of the HTML comment.
        ArrayNode arr = MAPPER.createArrayNode();
        for (LineComment c : review.getLineComments()) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("f", c.getFile());
            o.put("l", c.getLine());
            o.put("t", c.getType());
            o.put("b", c.getBody());
            arr.add(o);
        }
        sb.append("\n")
                .append(COMMENTS_TAG)
                .append(arr.toString().replace("-->", "-- >"))
                .append(TAG_END);

        // Human-readable general notes for GitHub UI (no type prefix — type is in the JSON above)
        List<LineComment> general =
                review.getLineComments().stream()
                        .filter(c -> c.getFile().isBlank() || c.getLine() <= 0)
                        .toList();
        if (!general.isEmpty()) {
            sb.append("\n\n**General Notes:**");
            for (LineComment c : general) sb.append("\n- ").append(c.getBody());
        }
        return sb.toString();
    }

    /** Escapes {@code -->} so it cannot prematurely terminate an HTML comment tag. */
    private static String escapeComment(String s) {
        return s.replace("-->", "-- >");
    }

    static ReviewResult decodeReview(String body, List<GhReviewComment> commentsArr) {
        String verdict = "COMMENT";

        // Extract verdict
        int vi = body.indexOf(VERDICT_TAG);
        if (vi >= 0) {
            int ei = body.indexOf(TAG_END, vi + VERDICT_TAG.length());
            if (ei >= 0) verdict = body.substring(vi + VERDICT_TAG.length(), ei).strip();
        }

        // Extract summary — prefer the hidden tag (new format); fall back to leading visible text
        String summary = "";
        int si = body.indexOf(SUMMARY_TAG);
        if (si >= 0) {
            int se = body.indexOf(TAG_END, si + SUMMARY_TAG.length());
            if (se >= 0) summary = body.substring(si + SUMMARY_TAG.length(), se).strip();
        } else if (vi > 0) {
            // Legacy: summary was the visible text before the verdict tag
            summary = body.substring(0, vi).strip();
            int ci2 = summary.indexOf(COMMENTS_TAG);
            if (ci2 >= 0) summary = summary.substring(0, ci2).strip();
        }

        List<LineComment> comments = new ArrayList<>();

        // Prefer embedded JSON (survives GitHub position remapping)
        int embeddedIdx = body.indexOf(COMMENTS_TAG);
        if (embeddedIdx >= 0) {
            int endIdx = body.indexOf(TAG_END, embeddedIdx + COMMENTS_TAG.length());
            if (endIdx >= 0) {
                String json = body.substring(embeddedIdx + COMMENTS_TAG.length(), endIdx).strip();
                try {
                    ArrayNode arr = (ArrayNode) MAPPER.readTree(json);
                    for (com.fasterxml.jackson.databind.JsonNode el : arr) {
                        String file = el.path("f").asText("");
                        int line = el.path("l").asInt(0);
                        String type = el.path("t").asText("note");
                        String text = el.path("b").asText("");
                        comments.add(new LineComment(file, line, type, text));
                    }
                    return new ReviewResult(summary, verdict, comments);
                } catch (Exception ignored) {
                    // fall through to legacy parsing
                    comments.clear();
                }
            }
        }

        // Legacy: general notes embedded in the review body
        final String NOTES_HEADER = "\n\n**General Notes:**";
        int ni = summary.indexOf(NOTES_HEADER);
        if (ni >= 0) {
            String notes = summary.substring(ni + NOTES_HEADER.length());
            summary = summary.substring(0, ni).strip();
            for (String line : notes.split("\n")) {
                line = line.strip();
                if (line.startsWith("- ")) line = line.substring(2);
                if (line.isBlank()) continue;
                String type = "note", text = line;
                Matcher m = TYPE_PREFIX.matcher(line);
                if (m.find()) {
                    type = m.group(1).toLowerCase();
                    text = line.substring(m.end());
                }
                comments.add(new LineComment("", 0, type, text));
            }
        }

        // Legacy: inline comments from GitHub API (line may be null for outdated positions)
        for (GhReviewComment c : commentsArr) {
            String path = c.path() != null ? c.path() : "";
            int line =
                    c.line() != null ? c.line() : (c.originalLine() != null ? c.originalLine() : 0);
            String text = c.body() != null ? c.body() : "";
            String type = "note";
            Matcher m = TYPE_PREFIX.matcher(text);
            if (m.find()) {
                type = m.group(1).toLowerCase();
                text = text.substring(m.end());
            }
            comments.add(new LineComment(path, line, type, text));
        }

        return new ReviewResult(summary, verdict, comments);
    }

    // --- Helpers ---

    private String post(String token, String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("Content-Type", "application/json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "intellij-claude-reviews/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException(
                    "GitHub API POST "
                            + response.statusCode()
                            + ": "
                            + truncate(response.body(), 300));
        return response.body();
    }

    private void delete(String token, String url) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "intellij-claude-reviews/1.0")
                        .DELETE()
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException(
                    "GitHub API DELETE "
                            + response.statusCode()
                            + ": "
                            + truncate(response.body(), 300));
    }

    private String get(String token, String url, String accept)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", accept)
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "intellij-claude-reviews/1.0")
                        .GET()
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "GitHub API " + response.statusCode() + ": " + truncate(response.body(), 200));
        }
        return response.body();
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // --- Jackson DTOs for GitHub API responses ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResult(@JsonProperty("items") java.util.List<PrItem> items) {
        SearchResult() {
            this(java.util.List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrItem(
            String title,
            @JsonProperty("html_url") String htmlUrl,
            int number,
            String body,
            GhUser user,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("repository_url") String repositoryUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GhUser(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StarredRepo(@JsonProperty("full_name") String fullName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GhReview(long id, String state, String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GhSubmittedReview(
            long id,
            GhUser user,
            String state,
            String body,
            @JsonProperty("submitted_at") String submittedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GhReviewComment(
            String path,
            Integer line,
            @JsonProperty("original_line") Integer originalLine,
            String body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrDetail(boolean merged, HeadRef head) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HeadRef(String sha) {}
}
