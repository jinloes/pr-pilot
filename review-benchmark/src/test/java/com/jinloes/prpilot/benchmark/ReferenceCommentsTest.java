package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReferenceCommentsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final PrRef PR = new PrRef("https://github.com", "org", "repo", 7);

    private static ObjectNode comment(long id, String login, String path, Integer line) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.putObject("user").put("login", login);
        node.put("path", path);
        if (line != null) node.put("original_line", line);
        node.put("original_commit_id", SHA_A);
        node.put("commit_id", SHA_B);
        node.put("created_at", "2024-01-01T00:00:0" + (id % 10) + "Z");
        node.put("body", "body " + id);
        node.putNull("in_reply_to_id");
        return node;
    }

    @Nested
    class Parse {
        @Test
        void keepsOnlyTopLevelCommentsFromMatchingLogins() {
            ArrayNode array = MAPPER.createArrayNode();
            array.add(comment(1, "svc-review-bot-x", "A.java", 10));
            array.add(comment(2, "someone", "A.java", 11));
            ObjectNode reply = comment(3, "SVC-REFERENCE-REVIEW", "A.java", 12);
            reply.put("in_reply_to_id", 1);
            array.add(reply);
            array.add(comment(4, "svc-review-bot", "", 12));

            List<ReferenceComments.Comment> comments =
                    ReferenceComments.parse(array, "svc-review-bot");

            assertThat(comments).extracting(ReferenceComments.Comment::id).containsExactly(1L);
            assertThat(comments.get(0).line()).isEqualTo(10);
            assertThat(comments.get(0).commitId()).isEqualTo(SHA_A);
        }

        @Test
        void prefixMatchesEveryNumberedAccountName() {
            ArrayNode array = MAPPER.createArrayNode();
            array.add(comment(1, "svc-review-bot-1_Example", "A.java", 10));
            array.add(comment(2, "svc-reviewbot-10_Example", "A.java", 11));

            assertThat(ReferenceComments.parse(array, "svc-review"))
                    .extracting(ReferenceComments.Comment::id)
                    .containsExactly(1L, 2L);
        }

        @Test
        void fallsBackFromOriginalLineToLineThenZero() {
            ObjectNode usesLine = comment(1, "svc-review-bot", "A.java", null);
            usesLine.put("line", 5);
            ObjectNode fileLevel = comment(2, "svc-review-bot", "B.java", null);
            ArrayNode array = MAPPER.createArrayNode().add(usesLine).add(fileLevel);

            assertThat(ReferenceComments.parse(array, "svc-review-bot"))
                    .extracting(ReferenceComments.Comment::line)
                    .containsExactly(5, 0);
        }
    }

    @Nested
    class FirstReview {
        @Test
        void keepsOnlyTheEarliestReviewedCommitSortedByLocation() {
            List<ReferenceComments.Comment> comments =
                    List.of(
                            new ReferenceComments.Comment(
                                    3, "m", "B.java", 1, SHA_B, "2024-01-03", "x"),
                            new ReferenceComments.Comment(
                                    2, "m", "B.java", 9, SHA_A, "2024-01-02", "y"),
                            new ReferenceComments.Comment(
                                    1, "m", "A.java", 4, SHA_A, "2024-01-01", "z"));

            ReferenceComments.Baseline baseline = ReferenceComments.firstReview(comments);

            assertThat(baseline.commitId()).isEqualTo(SHA_A);
            assertThat(baseline.comments())
                    .extracting(ReferenceComments.Comment::id)
                    .containsExactly(1L, 2L);
        }

        @Test
        void returnsAnEmptyBaselineWithoutComments() {
            assertThat(ReferenceComments.firstReview(List.of()).comments()).isEmpty();
        }
    }

    @Nested
    class Fetch {
        @Test
        void followsPagesUntilAShortPage() throws Exception {
            ArrayNode full = MAPPER.createArrayNode();
            for (int i = 1; i < 100; i++) full.add(comment(i, "other", "A.java", i));
            full.add(comment(0, "svc-review-bot", "A.java", 1));
            ArrayNode last =
                    MAPPER.createArrayNode().add(comment(200, "svc-review-bot", "C.java", 3));
            List<String> paths = new ArrayList<>();
            ReferenceComments reference =
                    new ReferenceComments(
                            (base, token, path) -> {
                                paths.add(path);
                                return new GitHubResponse(
                                        200, (paths.size() == 1 ? full : last).toString());
                            },
                            MAPPER);

            List<ReferenceComments.Comment> comments =
                    reference.fetch("https://api.github.com", "t", PR, "svc-review-bot");

            assertThat(paths)
                    .containsExactly(
                            "/repos/org/repo/pulls/7/comments?per_page=100&page=1",
                            "/repos/org/repo/pulls/7/comments?per_page=100&page=2");
            assertThat(comments)
                    .extracting(ReferenceComments.Comment::id)
                    .containsExactly(0L, 200L);
        }

        @Test
        void failsOnAnHttpError() {
            ReferenceComments reference =
                    new ReferenceComments(
                            (base, token, path) -> new GitHubResponse(404, "{}"), MAPPER);
            assertThatThrownBy(() -> reference.fetch("https://api.github.com", "t", PR, "svc"))
                    .hasMessageContaining("HTTP 404");
        }
    }
}
