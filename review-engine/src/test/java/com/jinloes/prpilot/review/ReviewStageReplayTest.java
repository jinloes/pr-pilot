package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewStageReplay.RecordedStages;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewStageReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SAMPLE_DIFF =
            "diff --git a/src/Service.java b/src/Service.java\n"
                    + "index 1111111..2222222 100644\n"
                    + "--- a/src/Service.java\n"
                    + "+++ b/src/Service.java\n"
                    + "@@ -10,2 +10,4 @@ class Service {\n"
                    + "     int unused = 0;\n"
                    + "+    String query = \"SELECT * FROM items WHERE id = \" + input;\n"
                    + "+    ids.forEach(id -> CompletableFuture.runAsync(() -> fetch(id)));\n"
                    + " }\n"
                    + "diff --git a/src/AuditLog.java b/src/AuditLog.java\n"
                    + "index 3333333..4444444 100644\n"
                    + "--- a/src/AuditLog.java\n"
                    + "+++ b/src/AuditLog.java\n"
                    + "@@ -20,2 +20,3 @@ class AuditLog {\n"
                    + "     void onFailure(Exception e) {\n"
                    + "+        log.warn(\"Audit failed: {}\", e.getMessage());\n"
                    + "     }\n";

    private static PullRequest samplePr() {
        return new PullRequest(
                "Add query lookup",
                "https://github.com/acme/widgets/pull/42",
                "acme",
                "widgets",
                42,
                "Adds item lookup",
                "alice",
                "2026-11-19T00:00:00Z",
                false);
    }

    @Nested
    class ReplayDeterministic {

        @Test
        void replaysCritiqueHygieneRulesAndCiSuppressionEndToEnd() throws Exception {
            PRReviewRequest request =
                    PRReviewRequest.builder(samplePr(), SAMPLE_DIFF)
                            .builtInRules(true)
                            .ciAnnotations(
                                    List.of(
                                            new CiAnnotation(
                                                    "src/Service.java",
                                                    10,
                                                    "warning",
                                                    "Unused local variable unused declared in method")))
                            .build();

            String primaryJson =
                    reviewJson(
                            "Primary summary",
                            "REQUEST_CHANGES",
                            commentNode(
                                    "src/Service.java",
                                    11,
                                    "issue",
                                    "blocker",
                                    "security",
                                    "high",
                                    "SQL string concatenation allows injection; bind `input` as a parameter.",
                                    "PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM items WHERE id = ?\");"),
                            commentNode(
                                    "src/Service.java",
                                    10,
                                    "suggestion",
                                    "minor",
                                    "maintainability",
                                    "high",
                                    "Unused local variable unused declared in method",
                                    ""),
                            commentNode(
                                    "src/Service.java",
                                    999,
                                    "issue",
                                    "major",
                                    "correctness",
                                    "high",
                                    "Hallucinated off-diff line",
                                    ""));

            String critiqueJson =
                    reviewJson(
                            "Validated summary",
                            "REQUEST_CHANGES",
                            commentNode(
                                    "src/Service.java",
                                    11,
                                    "issue",
                                    "blocker",
                                    "security",
                                    "high",
                                    "SQL string concatenation allows injection; bind `input` as a parameter.",
                                    "11| PreparedStatement badPrefix;"),
                            commentNode(
                                    "src/Service.java",
                                    10,
                                    "suggestion",
                                    "minor",
                                    "maintainability",
                                    "high",
                                    "Unused local variable unused declared in method",
                                    ""));

            String hygieneJson =
                    reviewJson(
                            "",
                            "COMMENT",
                            commentNode(
                                    "src/AuditLog.java",
                                    21,
                                    "suggestion",
                                    "minor",
                                    "maintainability",
                                    "high",
                                    "Pass exception `e` as the final logger argument to preserve stack trace.",
                                    ""));

            ObjectNode selectionNode = JSON.createObjectNode();
            selectionNode.putArray("triggered").add("unbounded-fanout");
            String selectionJson = JSON.writeValueAsString(selectionNode);
            String ruleAgentJson =
                    reviewJson(
                            "",
                            "COMMENT",
                            commentNode(
                                    "src/Service.java",
                                    12,
                                    "issue",
                                    "major",
                                    "performance",
                                    "high",
                                    "Avoid unbounded query fan-out inside request loop.",
                                    ""));

            List<String> statuses = new ArrayList<>();
            ReviewResult result =
                    ReviewStageReplay.replayDeterministic(
                            request,
                            new RecordedStages(
                                    primaryJson,
                                    critiqueJson,
                                    hygieneJson,
                                    selectionJson,
                                    Map.of("unbounded-fanout", ruleAgentJson)),
                            true,
                            true,
                            statuses::add);

            assertThat(result.getVerdict()).isEqualTo("REQUEST_CHANGES");
            assertThat(result.getLineComments())
                    .extracting(c -> c.getFile() + ":" + c.getLine())
                    .containsExactlyInAnyOrder(
                            "src/Service.java:11", "src/Service.java:12", "src/AuditLog.java:21");
            // Line-numbered suggestedChange is stripped by ReviewSuggestionGuard.
            assertThat(result.getLineComments())
                    .allSatisfy(c -> assertThat(c.getSuggestedChange()).isEmpty());
            assertThat(result.getLineComments())
                    .anySatisfy(
                            c -> assertThat(c.getRationale()).contains("rule: unbounded-fanout"));
            assertThat(statuses).anyMatch(s -> s.contains("Validation kept"));
        }
    }

    @Nested
    class ExtractRuleName {

        @Test
        void extractsNameFromRuleSectionHeader() {
            String prompt =
                    ReviewRulePrompts.RULE_REVIEW_PREAMBLE
                            + "<review_rule>\nname: unbounded-fanout\n\nCheck fanout\n</review_rule>";
            assertThat(ReviewStageReplay.extractRuleName(prompt)).isEqualTo("unbounded-fanout");
            assertThat(ReviewStageReplay.extractRuleName("no rule section")).isEmpty();
        }
    }

    private static ObjectNode commentNode(
            String file,
            int line,
            String type,
            String severity,
            String category,
            String confidence,
            String body,
            String suggestedChange) {
        ObjectNode node = JSON.createObjectNode();
        node.put("file", file);
        node.put("line", line);
        node.put("type", type);
        node.put("severity", severity);
        node.put("category", category);
        node.put("confidence", confidence);
        node.put("body", body);
        node.put("rationale", "anchored in diff");
        if (!suggestedChange.isEmpty()) {
            node.put("suggestedChange", suggestedChange);
        }
        return node;
    }

    private static String reviewJson(String summary, String verdict, ObjectNode... comments)
            throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("summary", summary);
        root.put("verdict", verdict);
        ArrayNode arr = root.putArray("lineComments");
        for (ObjectNode comment : comments) {
            arr.add(comment);
        }
        return JSON.writeValueAsString(root);
    }
}
