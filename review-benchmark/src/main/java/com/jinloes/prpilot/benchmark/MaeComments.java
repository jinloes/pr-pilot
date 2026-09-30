package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubApiClient;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Mae's inline review comments on a pull request, used as the benchmark's expected findings.
 *
 * <p>Mae reviews the commit that was the PR head when it ran. The benchmark reviews that same
 * commit, so only comments Mae left on its first reviewed commit count; later rounds reviewed code
 * PR Pilot never sees. Replies are ignored because they are conversation, not findings.
 */
final class MaeComments {
    static final String DEFAULT_LOGIN_PREFIX = "svc-mae";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 30;

    private final GitHubApiClient client;
    private final ObjectMapper mapper;

    MaeComments(GitHubApiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    record Comment(
            long id,
            String login,
            String path,
            int line,
            String commitId,
            String createdAt,
            String body) {}

    /** The commit Mae first reviewed and the findings it left there; empty when Mae never ran. */
    record Baseline(String commitId, List<Comment> comments) {
        boolean isEmpty() {
            return comments.isEmpty();
        }
    }

    List<Comment> fetch(String apiBaseUrl, String token, PrRef pr, String loginPrefix)
            throws IOException {
        List<Comment> comments = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String path =
                    "/repos/"
                            + pr.owner()
                            + "/"
                            + pr.repo()
                            + "/pulls/"
                            + pr.number()
                            + "/comments?per_page="
                            + PAGE_SIZE
                            + "&page="
                            + page;
            GitHubResponse response = client.get(apiBaseUrl, token, path);
            if (!response.isSuccess()) {
                throw new IOException(
                        "Fetching review comments failed with HTTP " + response.statusCode());
            }
            JsonNode array = mapper.readTree(response.body());
            if (!array.isArray()) throw new IOException("Unexpected review comments response.");
            comments.addAll(parse(array, loginPrefix));
            if (array.size() < PAGE_SIZE) break;
        }
        return comments;
    }

    static List<Comment> parse(JsonNode array, String loginPrefix) {
        String prefix = loginPrefix.toLowerCase(Locale.ROOT);
        List<Comment> comments = new ArrayList<>();
        for (JsonNode node : array) {
            String login = node.path("user").path("login").asText("");
            if (!login.toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
            if (node.hasNonNull("in_reply_to_id")) continue;
            // original_commit_id is the commit the comment was written on; commit_id moves forward.
            String commit =
                    node.path("original_commit_id").asText(node.path("commit_id").asText(""));
            String path = node.path("path").asText("");
            if (commit.isEmpty() || path.isEmpty()) continue;
            int line = positive(node.get("original_line"), positive(node.get("line"), 0));
            comments.add(
                    new Comment(
                            node.path("id").asLong(),
                            login,
                            path,
                            line,
                            commit,
                            node.path("created_at").asText(""),
                            node.path("body").asText("")));
        }
        return comments;
    }

    private static int positive(JsonNode value, int fallback) {
        return value != null && value.isInt() && value.intValue() > 0 ? value.intValue() : fallback;
    }

    static Baseline firstReview(List<Comment> comments) {
        return comments.stream()
                .min(Comparator.comparing(Comment::createdAt).thenComparing(Comment::id))
                .map(
                        first ->
                                new Baseline(
                                        first.commitId(),
                                        comments.stream()
                                                .filter(c -> c.commitId().equals(first.commitId()))
                                                .sorted(
                                                        Comparator.comparing(Comment::path)
                                                                .thenComparingInt(Comment::line))
                                                .toList()))
                .orElse(new Baseline("", List.of()));
    }
}
