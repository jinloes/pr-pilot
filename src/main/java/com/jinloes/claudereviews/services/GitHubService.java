package com.jinloes.claudereviews.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

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
        List<PullRequest> prs = new ArrayList<>();
        JsonNode root = MAPPER.readTree(json);
        ArrayNode items = (ArrayNode) root.path("items");
        for (JsonNode el : items) {
            // repository_url: "https://api.github.com/repos/owner/repo"
            String repoUrl = el.path("repository_url").asText();
            String[] parts = repoUrl.split("/");
            String owner = parts[parts.length - 2];
            String repo = parts[parts.length - 1];
            prs.add(toPR(el, owner, repo));
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
            ArrayNode items = (ArrayNode) MAPPER.readTree(body);
            if (items.isEmpty()) break;
            for (JsonNode el : items) {
                repos.add(el.path("full_name").asText());
            }
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
        ArrayNode reviews =
                (ArrayNode) MAPPER.readTree(get(token, url, "application/vnd.github.v3+json"));
        for (JsonNode r : reviews) {
            if ("PENDING".equals(r.path("state").asText()))
                return String.valueOf(r.path("id").asLong());
        }
        return null;
    }

    /**
     * Saves a {@link ReviewResult} as a GitHub pending (draft) review. Any existing pending review
     * is deleted first so there is never more than one. Returns the new review ID.
     */
    public String saveDraftReview(
            String token, String owner, String repo, int number, ReviewResult review)
            throws IOException, InterruptedException {
        // Delete stale draft if present
        String existing = getPendingReviewId(token, owner, repo, number);
        if (existing != null) deleteDraftReview(token, owner, repo, number, existing);

        String headSha = getPRHeadSha(token, owner, repo, number);

        ArrayNode comments = MAPPER.createArrayNode();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (LineComment c : review.getLineComments()) {
            String file = c.getFile();
            if (file.startsWith("a/") || file.startsWith("b/")) file = file.substring(2);
            if (file.isBlank() || c.getLine() <= 0 || c.getBody().isBlank()) continue;
            // Deduplicate: skip exact duplicates (same file, line, and body) — prevents
            // Claude from posting the same comment twice, but allows multiple distinct
            // comments on the same line (e.g. an AI comment plus a user-added note).
            if (!seen.add(file + "\0" + c.getLine() + "\0" + c.getBody())) continue;
            ObjectNode comment = MAPPER.createObjectNode();
            comment.put("path", file);
            comment.put("line", c.getLine());
            comment.put("side", "RIGHT");
            comment.put("body", c.getBody());
            comments.add(comment);
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("commit_id", headSha);
        payload.put("body", encodeBody(review));
        // Omitting "event" is what creates a PENDING (draft) review.
        // Setting event:"PENDING" is invalid and causes a 422.
        payload.set("comments", comments);

        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number + "/reviews";
        String response;
        try {
            response = post(token, url, payload.toString());
        } catch (IOException ex) {
            // 422: one or more inline comments have an invalid path/line for this commit.
            // Retry with body only so the draft is always persisted.
            if (ex.getMessage() != null && ex.getMessage().contains("422")) {
                payload.set("comments", MAPPER.createArrayNode());
                response = post(token, url, payload.toString());
            } else {
                throw ex;
            }
        }
        return String.valueOf(MAPPER.readTree(response).path("id").asLong());
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
        JsonNode review = MAPPER.readTree(get(token, reviewUrl, "application/vnd.github.v3+json"));
        String body = review.path("body").asText("");

        ArrayNode commentsArr =
                (ArrayNode)
                        MAPPER.readTree(
                                get(
                                        token,
                                        reviewUrl + "/comments",
                                        "application/vnd.github.v3+json"));

        return new PendingReview(id, decodeReview(body, commentsArr));
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

    /** Returns {@code true} if the pull request has been merged. */
    public boolean isPRMerged(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number;
        return MAPPER.readTree(get(token, url, "application/vnd.github.v3+json"))
                .path("merged")
                .asBoolean(false);
    }

    /** Fetches the HEAD commit SHA for a pull request. */
    public String getPRHeadSha(String token, String owner, String repo, int number)
            throws IOException, InterruptedException {
        String url = apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + number;
        return MAPPER.readTree(get(token, url, "application/vnd.github.v3+json"))
                .path("head")
                .path("sha")
                .asText();
    }

    // --- Encode / decode ReviewResult ↔ GitHub review body + comments ---

    static String encodeBody(ReviewResult review) {
        // Summary is stored in a hidden HTML comment so it round-trips without appearing on GitHub
        String escapedSummary = review.getSummary().replace("-->", "-- >");
        StringBuilder sb = new StringBuilder(SUMMARY_TAG).append(escapedSummary).append(TAG_END);
        sb.append("\n").append(VERDICT_TAG).append(review.getVerdict()).append(TAG_END);

        // Embed all comments as JSON so line numbers survive GitHub's position remapping
        ArrayNode arr = MAPPER.createArrayNode();
        for (LineComment c : review.getLineComments()) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("f", c.getFile());
            o.put("l", c.getLine());
            o.put("t", c.getType());
            o.put("b", c.getBody());
            arr.add(o);
        }
        sb.append("\n").append(COMMENTS_TAG).append(arr).append(TAG_END);

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

    static ReviewResult decodeReview(String body, ArrayNode commentsArr) {
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
                    for (JsonNode el : arr) {
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
        for (JsonNode c : commentsArr) {
            String path = c.path("path").asText("");
            JsonNode lineNode = c.path("line");
            int line = lineNode.isNumber() ? lineNode.asInt() : c.path("original_line").asInt(0);
            String text = c.path("body").asText("");
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

    private PullRequest toPR(JsonNode obj, String owner, String repo) {
        String title = obj.path("title").asText();
        String htmlUrl = obj.path("html_url").asText();
        int number = obj.path("number").asInt();
        String body = obj.path("body").asText("");
        String author = obj.path("user").path("login").asText();
        String createdAt = obj.path("created_at").asText();
        return new PullRequest(title, htmlUrl, owner, repo, number, body, author, createdAt);
    }

    private String post(String token, String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
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
}
