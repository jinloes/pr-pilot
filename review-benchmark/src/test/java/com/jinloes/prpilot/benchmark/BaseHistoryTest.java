package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BaseHistoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PrRef PR = new PrRef("https://github.com", "org", "repo", 7);
    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);
    private static final String C = "c".repeat(40);

    /** A GraphQL timeline page with one force-push event per before/after pair. */
    private static ObjectNode page(String nextCursor, String... beforeAfterPairs) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode items =
                root.putObject("data")
                        .putObject("repository")
                        .putObject("pullRequest")
                        .putObject("timelineItems");
        ObjectNode pageInfo = items.putObject("pageInfo");
        pageInfo.put("hasNextPage", nextCursor != null);
        pageInfo.put("endCursor", nextCursor);
        ArrayNode nodes = items.putArray("nodes");
        for (int i = 0; i + 1 < beforeAfterPairs.length; i += 2) {
            ObjectNode node = nodes.addObject();
            node.putObject("beforeCommit").put("oid", beforeAfterPairs[i]);
            node.putObject("afterCommit").put("oid", beforeAfterPairs[i + 1]);
        }
        return root;
    }

    @Nested
    class Parse {
        @Test
        void readsBeforeAndAfterTipsAndTheNextCursor() throws Exception {
            BaseHistory.Page page = BaseHistory.parse(page("cursor-1", A, B, B, C));

            assertThat(page.tips()).containsExactly(A, B, B, C);
            assertThat(page.nextCursor()).isEqualTo("cursor-1");
        }

        @Test
        void ignoresNodesWithoutCommitsAndStopsOnTheLastPage() throws Exception {
            ObjectNode root = page(null);
            ((ArrayNode)
                            root.path("data")
                                    .path("repository")
                                    .path("pullRequest")
                                    .path("timelineItems")
                                    .path("nodes"))
                    .addObject();

            BaseHistory.Page page = BaseHistory.parse(root);

            assertThat(page.tips()).isEmpty();
            assertThat(page.nextCursor()).isNull();
        }

        @Test
        void failsOnGraphqlErrorsOrAMissingPullRequest() {
            ObjectNode errors = JSON.createObjectNode();
            errors.putArray("errors").addObject().put("message", "Bad credentials");
            ObjectNode missing = JSON.createObjectNode();
            missing.putObject("data").putObject("repository").putNull("pullRequest");

            assertThatThrownBy(() -> BaseHistory.parse(errors))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Bad credentials");
            assertThatThrownBy(() -> BaseHistory.parse(missing))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no pull request");
        }
    }

    @Nested
    class BaseTips {
        @Test
        void followsPagesAndDeduplicatesInTimelineOrder() throws Exception {
            Deque<String> responses =
                    new ArrayDeque<>(
                            List.of(
                                    JSON.writeValueAsString(page("p2", A, B)),
                                    JSON.writeValueAsString(page(null, B, C))));
            List<JsonNode> requests = new ArrayList<>();
            BaseHistory history =
                    new BaseHistory(
                            (url, token, body) -> {
                                try {
                                    requests.add(JSON.readTree(body));
                                } catch (IOException e) {
                                    throw new IllegalStateException(e);
                                }
                                return new GitHubResponse(200, responses.pop());
                            },
                            JSON);

            List<String> tips = history.baseTips("https://api.github.com/graphql", "t", PR);

            assertThat(tips).containsExactly(A, B, C);
            assertThat(requests).hasSize(2);
            JsonNode first = requests.get(0).path("variables");
            assertThat(first.path("owner").asText()).isEqualTo("org");
            assertThat(first.path("repo").asText()).isEqualTo("repo");
            assertThat(first.path("number").asInt()).isEqualTo(7);
            assertThat(first.path("after").isNull()).isTrue();
            assertThat(requests.get(1).path("variables").path("after").asText()).isEqualTo("p2");
            assertThat(requests.get(0).path("query").asText())
                    .contains("BASE_REF_FORCE_PUSHED_EVENT");
        }

        @Test
        void stopsAfterThePageLimit() throws Exception {
            String endless = JSON.writeValueAsString(page("again", A, B));
            int[] calls = {0};
            BaseHistory history =
                    new BaseHistory(
                            (url, token, body) -> {
                                calls[0]++;
                                return new GitHubResponse(200, endless);
                            },
                            JSON);

            assertThat(history.baseTips("u", "t", PR)).containsExactly(A, B);
            assertThat(calls[0]).isEqualTo(BaseHistory.MAX_PAGES);
        }

        @Test
        void failsOnAnHttpError() {
            BaseHistory history =
                    new BaseHistory((url, token, body) -> new GitHubResponse(502, ""), JSON);

            assertThatThrownBy(() -> history.baseTips("u", "t", PR))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 502");
        }
    }
}
