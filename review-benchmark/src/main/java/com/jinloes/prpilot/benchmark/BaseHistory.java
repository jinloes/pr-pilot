package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Every commit a pull request's base branch has pointed at, from the {@code
 * BaseRefForcePushedEvent}s on its GraphQL timeline. GitHub only reports a PR's current base, so
 * for a stacked PR whose parent was later squash-merged, this history is the only record of the
 * base the reference reviewer reviewed against.
 */
final class BaseHistory {
    static final int MAX_PAGES = 10;
    private static final String QUERY =
            """
            query($owner: String!, $repo: String!, $number: Int!, $after: String) {
              repository(owner: $owner, name: $repo) {
                pullRequest(number: $number) {
                  timelineItems(first: 100, after: $after, itemTypes: [BASE_REF_FORCE_PUSHED_EVENT]) {
                    pageInfo { hasNextPage endCursor }
                    nodes { ... on BaseRefForcePushedEvent { beforeCommit { oid } afterCommit { oid } } }
                  }
                }
              }
            }
            """;

    /** Authenticated JSON POST, so tests can script GraphQL responses without sockets. */
    @FunctionalInterface
    interface Poster {
        GitHubResponse post(String url, String token, String body);
    }

    record Page(List<String> tips, String nextCursor) {}

    private final Poster poster;
    private final ObjectMapper mapper;

    BaseHistory(Poster poster, ObjectMapper mapper) {
        this.poster = poster;
        this.mapper = mapper;
    }

    static Poster http() {
        GitHubHttpClient client = new GitHubHttpClient();
        return client::postJson;
    }

    /** Distinct base-branch tips in timeline order; empty when the base was never force-pushed. */
    List<String> baseTips(String graphqlUrl, String token, PrRef pr) throws IOException {
        Set<String> tips = new LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            GitHubResponse response = poster.post(graphqlUrl, token, requestBody(pr, cursor));
            if (!response.isSuccess()) {
                throw new IOException(
                        "Fetching base history failed with HTTP " + response.statusCode());
            }
            Page parsed = parse(mapper.readTree(response.body()));
            tips.addAll(parsed.tips());
            cursor = parsed.nextCursor();
            if (cursor == null) break;
        }
        return List.copyOf(tips);
    }

    String requestBody(PrRef pr, String cursor) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("query", QUERY);
        ObjectNode variables = root.putObject("variables");
        variables.put("owner", pr.owner());
        variables.put("repo", pr.repo());
        variables.put("number", pr.number());
        if (cursor == null) {
            variables.putNull("after");
        } else {
            variables.put("after", cursor);
        }
        return mapper.writeValueAsString(root);
    }

    static Page parse(JsonNode root) throws IOException {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IOException(
                    "Base history query failed: " + errors.get(0).path("message").asText());
        }
        JsonNode items = root.path("data").path("repository").path("pullRequest");
        if (!items.isObject()) {
            throw new IOException("Base history response has no pull request.");
        }
        items = items.path("timelineItems");
        List<String> tips = new ArrayList<>();
        for (JsonNode node : items.path("nodes")) {
            for (String field : List.of("beforeCommit", "afterCommit")) {
                String oid = node.path(field).path("oid").asText("");
                if (StringUtils.isNotBlank(oid)) tips.add(oid);
            }
        }
        JsonNode pageInfo = items.path("pageInfo");
        String next =
                pageInfo.path("hasNextPage").asBoolean(false)
                        ? StringUtils.trimToNull(pageInfo.path("endCursor").asText(null))
                        : null;
        return new Page(tips, next);
    }
}
