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

class MaeCommentsTest {
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
            array.add(comment(1, "svc-mae-review-bot", "A.java", 10));
            array.add(comment(2, "someone", "A.java", 11));
            ObjectNode reply = comment(3, "SVC-MAE-REVIEW", "A.java", 12);
            reply.put("in_reply_to_id", 1);
            array.add(reply);
            array.add(comment(4, "svc-mae-review", "", 12));

            List<MaeComments.Comment> comments = MaeComments.parse(array, "svc-mae-review");

            assertThat(comments).extracting(MaeComments.Comment::id).containsExactly(1L);
            assertThat(comments.get(0).line()).isEqualTo(10);
            assertThat(comments.get(0).commitId()).isEqualTo(SHA_A);
        }

        @Test
        void fallsBackFromOriginalLineToLineThenZero() {
            ObjectNode usesLine = comment(1, "svc-mae-review", "A.java", null);
            usesLine.put("line", 5);
            ObjectNode fileLevel = comment(2, "svc-mae-review", "B.java", null);
            ArrayNode array = MAPPER.createArrayNode().add(usesLine).add(fileLevel);

            assertThat(MaeComments.parse(array, "svc-mae-review"))
                    .extracting(MaeComments.Comment::line)
                    .containsExactly(5, 0);
        }
    }

    @Nested
    class FirstReview {
        @Test
        void keepsOnlyTheEarliestReviewedCommitSortedByLocation() {
            List<MaeComments.Comment> comments =
                    List.of(
                            new MaeComments.Comment(3, "m", "B.java", 1, SHA_B, "2024-01-03", "x"),
                            new MaeComments.Comment(2, "m", "B.java", 9, SHA_A, "2024-01-02", "y"),
                            new MaeComments.Comment(1, "m", "A.java", 4, SHA_A, "2024-01-01", "z"));

            MaeComments.Baseline baseline = MaeComments.firstReview(comments);

            assertThat(baseline.commitId()).isEqualTo(SHA_A);
            assertThat(baseline.comments())
                    .extracting(MaeComments.Comment::id)
                    .containsExactly(1L, 2L);
        }

        @Test
        void returnsAnEmptyBaselineWithoutComments() {
            assertThat(MaeComments.firstReview(List.of()).comments()).isEmpty();
        }
    }

    @Nested
    class Fetch {
        @Test
        void followsPagesUntilAShortPage() throws Exception {
            ArrayNode full = MAPPER.createArrayNode();
            for (int i = 1; i < 100; i++) full.add(comment(i, "other", "A.java", i));
            full.add(comment(0, "svc-mae-review", "A.java", 1));
            ArrayNode last =
                    MAPPER.createArrayNode().add(comment(200, "svc-mae-review", "C.java", 3));
            List<String> paths = new ArrayList<>();
            MaeComments mae =
                    new MaeComments(
                            (base, token, path) -> {
                                paths.add(path);
                                return new GitHubResponse(
                                        200, (paths.size() == 1 ? full : last).toString());
                            },
                            MAPPER);

            List<MaeComments.Comment> comments =
                    mae.fetch("https://api.github.com", "t", PR, "svc-mae-review");

            assertThat(paths)
                    .containsExactly(
                            "/repos/org/repo/pulls/7/comments?per_page=100&page=1",
                            "/repos/org/repo/pulls/7/comments?per_page=100&page=2");
            assertThat(comments).extracting(MaeComments.Comment::id).containsExactly(0L, 200L);
        }

        @Test
        void failsOnAnHttpError() {
            MaeComments mae =
                    new MaeComments((base, token, path) -> new GitHubResponse(404, "{}"), MAPPER);
            assertThatThrownBy(() -> mae.fetch("https://api.github.com", "t", PR, "svc"))
                    .hasMessageContaining("HTTP 404");
        }
    }
}
