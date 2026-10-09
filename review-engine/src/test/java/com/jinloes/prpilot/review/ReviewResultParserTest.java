package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link ReviewResultParser}: review JSON parsing, repair and comment caps. */
class ReviewResultParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PullRequest fakePr() {
        return new PullRequest(
                "T", "https://github.com/o/r/pull/1", "o", "r", 1, "", "a", "2024-01-01");
    }

    private static PRReviewRequest fakeRequest() {
        return new PRReviewRequest(fakePr(), "");
    }

    @Nested
    class ParseReview {

        @Test
        void plainJsonParsedCorrectly() throws Exception {
            String json = "{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getVerdict()).isEqualTo("APPROVE");
            assertThat(result.getSummary()).isEqualTo("s");
        }

        @Test
        void jsonWrappedInMarkdownFenceFenceStripped() throws Exception {
            String json =
                    "```json\n{\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[]}\n```";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        @Test
        void jsonEmbeddedInSurroundingProseBracesExtracted() throws Exception {
            String json =
                    "Here is the review: {\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[]} done.";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        @Test
        void invalidJsonThrowsException() {
            assertThatThrownBy(() -> ReviewResultParser.parseReview("not json at all"))
                    .isInstanceOf(Exception.class);
        }

        @Test
        void jsonWithLineCommentRoundTripsCorrectly() throws Exception {
            String json =
                    "{\"summary\":\"overview\",\"verdict\":\"REQUEST_CHANGES\",\"lineComments\":[{\"file\":\"src/Foo.java\",\"line\":10,\"type\":\"issue\",\"severity\":\"major\",\"category\":\"correctness\",\"confidence\":\"high\",\"rationale\":\"The diff dereferences the nullable value.\",\"body\":\"Guard the nullable value before dereferencing it.\"}]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getLineComments()).hasSize(1);
            assertThat(result.getLineComments().get(0).getFile()).isEqualTo("src/Foo.java");
        }

        @Test
        void jsonWithSeverityCategoryConfidenceRationalePreserved() throws Exception {
            String json =
                    "{\"summary\":\"s\",\"verdict\":\"REQUEST_CHANGES\",\"lineComments\":[{\"file\":\"src/Foo.java\",\"line\":10,\"type\":\"issue\",\"body\":\"b\",\"severity\":\"major\",\"category\":\"security\",\"confidence\":\"high\",\"rationale\":\"read the schema\"}]}";
            LineComment c = ReviewResultParser.parseReview(json).getLineComments().get(0);
            assertThat(c.getSeverity()).isEqualTo("major");
            assertThat(c.getCategory()).isEqualTo("security");
            assertThat(c.getConfidence()).isEqualTo("high");
            assertThat(c.getRationale()).isEqualTo("read the schema");
        }

        @Test
        void compatibilityCategoryIsPreservedAndUnknownBoundaryCategoryIsDropped()
                throws Exception {
            String compatibility =
                    reviewWithComment(
                            Map.of(
                                    "type", "issue",
                                    "severity", "major",
                                    "category", "compatibility",
                                    "confidence", "high",
                                    "rationale", "Caller.java still invokes the removed API.",
                                    "body", "Update the caller to the new API contract."));
            String unknown =
                    reviewWithComment(
                            Map.of(
                                    "type", "issue",
                                    "severity", "major",
                                    "category", "boundary",
                                    "confidence", "high",
                                    "rationale", "Caller.java still invokes the removed API.",
                                    "body", "Update the caller to the new API contract."));

            assertThat(ReviewResultParser.parseReview(compatibility).getLineComments())
                    .singleElement()
                    .extracting(LineComment::getCategory)
                    .isEqualTo("compatibility");
            assertThat(ReviewResultParser.parseReview(unknown).getLineComments()).isEmpty();
        }

        @Test
        void jsonWithoutRequiredCommentFieldsCommentDroppedRestKept() throws Exception {
            String json =
                    "{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[{\"file\":\"a\",\"line\":1,\"type\":\"note\",\"body\":\"b\"}]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getLineComments()).isEmpty();
            assertThat(result.getVerdict()).isEqualTo("APPROVE");
        }

        @Test
        void jsonWithUnexpectedTopLevelFieldsIgnoredRatherThanRejected() throws Exception {
            String json =
                    "{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[],\"extra\":true}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getVerdict()).isEqualTo("APPROVE");
        }

        /**
         * Builds a one-comment review from the supplied fields. Omitting a key leaves the field
         * absent, which is how "no rationale supplied" is expressed.
         */
        private String reviewWithComment(Map<String, String> fields) throws Exception {
            ObjectNode review =
                    JSON.createObjectNode().put("summary", "s").put("verdict", "REQUEST_CHANGES");
            ObjectNode comment = review.putArray("lineComments").addObject();
            comment.put("file", "a").put("line", 1);
            fields.forEach(comment::put);
            return JSON.writeValueAsString(review);
        }

        /**
         * The prompt forbids a low-confidence "issue". Downgrading it to "suggestion" made that
         * rule free to break — the violation became an accepted comment — so it is now dropped.
         */
        @Test
        void lowConfidenceIssueDroppedRatherThanDowngradedToSuggestion() throws Exception {
            String json =
                    reviewWithComment(
                            Map.of(
                                    "type", "issue",
                                    "severity", "major",
                                    "category", "correctness",
                                    "confidence", "low",
                                    "rationale", "The line returns null.",
                                    "body", "Handle the null return value."));

            ReviewResult result = ReviewResultParser.parseReview(json);

            assertThat(result.getLineComments()).isEmpty();
            // The only comment backing REQUEST_CHANGES is gone, so the verdict degrades with it.
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        /**
         * A bare low-confidence "note" was the cheapest comment the model could emit: the parser
         * exempted notes from the rationale requirement and the webview quality check exempts
         * low-confidence comments from its own, so nothing removed it.
         */
        @Test
        void lowConfidenceNoteWithoutRationaleDropped() throws Exception {
            String json =
                    reviewWithComment(
                            Map.of(
                                    "type", "note",
                                    "severity", "minor",
                                    "category", "correctness",
                                    "confidence", "low",
                                    "body", "This might be a problem."));

            assertThat(ReviewResultParser.parseReview(json).getLineComments()).isEmpty();
        }

        @Test
        void lowConfidenceNoteWithRationaleKept() throws Exception {
            String json =
                    reviewWithComment(
                            Map.of(
                                    "type", "note",
                                    "severity", "minor",
                                    "category", "correctness",
                                    "confidence", "low",
                                    "rationale", "Line 1 reassigns the parameter.",
                                    "body", "Confirm the reassignment is intended."));

            assertThat(ReviewResultParser.parseReview(json).getLineComments())
                    .singleElement()
                    .extracting(LineComment::getType)
                    .isEqualTo("note");
        }

        /** The rationale requirement is tightened only for low confidence, not for every note. */
        @Test
        void mediumConfidenceNoteWithoutRationaleStillKept() throws Exception {
            String json =
                    reviewWithComment(
                            Map.of(
                                    "type", "note",
                                    "severity", "minor",
                                    "category", "correctness",
                                    "confidence", "medium",
                                    "body", "Worth a second look before merge."));

            assertThat(ReviewResultParser.parseReview(json).getLineComments()).hasSize(1);
        }

        /** Only "issue" is gated on confidence; a justified low-confidence suggestion survives. */
        @Test
        void lowConfidenceSuggestionWithRationaleKept() throws Exception {
            String json =
                    reviewWithComment(
                            Map.of(
                                    "type", "suggestion",
                                    "severity", "minor",
                                    "category", "maintainability",
                                    "confidence", "low",
                                    "rationale", "The literal 900 appears twice.",
                                    "body", "Extract the TTL into a named constant."));

            assertThat(ReviewResultParser.parseReview(json).getLineComments()).hasSize(1);
        }

        @Test
        void verdictIssueMismatchVerdictSelfHealsInsteadOfRejected() throws Exception {
            String json =
                    "{\"summary\":\"s\",\"verdict\":\"APPROVE\",\"lineComments\":[{\"file\":\"a\",\"line\":1,\"type\":\"issue\",\"severity\":\"major\",\"category\":\"correctness\",\"confidence\":\"high\",\"rationale\":\"The line returns null.\",\"body\":\"Handle the null return value.\"}]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getVerdict()).isEqualTo("REQUEST_CHANGES");
            assertThat(result.getLineComments().get(0).getType()).isEqualTo("issue");
        }

        @Test
        void minorSeverityIssueDoesNotForceRequestChanges() throws Exception {
            String json =
                    "{\"summary\":\"s\",\"verdict\":\"REQUEST_CHANGES\",\"lineComments\":[{\"file\":\"a\",\"line\":1,\"type\":\"issue\",\"severity\":\"minor\",\"category\":\"correctness\",\"confidence\":\"high\",\"rationale\":\"Small clarity fix on the changed line.\",\"body\":\"Rename the local for clarity.\"}]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getLineComments()).hasSize(1);
            assertThat(result.getLineComments().get(0).getType()).isEqualTo("issue");
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        @Test
        void nitSeverityIssueDowngradedToSuggestionAndDoesNotBlock() throws Exception {
            String json =
                    "{\"summary\":\"s\",\"verdict\":\"REQUEST_CHANGES\",\"lineComments\":[{\"file\":\"a\",\"line\":1,\"type\":\"issue\",\"severity\":\"nit\",\"category\":\"maintainability\",\"confidence\":\"high\",\"rationale\":\"Trivial nit on the changed line.\",\"body\":\"Drop the extra blank line.\"}]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getLineComments()).hasSize(1);
            assertThat(result.getLineComments().get(0).getType()).isEqualTo("suggestion");
            assertThat(result.getVerdict()).isEqualTo("COMMENT");
        }

        @Test
        void overLongSummaryTruncatedInsteadOfRejected() throws Exception {
            String json =
                    "{\"summary\":\""
                            + "s".repeat(900)
                            + "\",\"verdict\":\"APPROVE\",\"lineComments\":[]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getSummary()).hasSize(800);
        }

        @Test
        void bodyWithEmbeddedNewlineCollapsedInsteadOfRejected() throws Exception {
            String json =
                    "{\"summary\":\"s\",\"verdict\":\"COMMENT\",\"lineComments\":[{\"file\":\"a\",\"line\":1,\"type\":\"note\",\"severity\":\"minor\",\"category\":\"tests\",\"confidence\":\"medium\",\"body\":\"line one\\nline two\"}]}";
            ReviewResult result = ReviewResultParser.parseReview(json);
            assertThat(result.getLineComments()).hasSize(1);
            assertThat(result.getLineComments().get(0).getBody()).isEqualTo("line one line two");
        }

        @Test
        void longLineCommentBodyPreservedInsteadOfCutOff() throws Exception {
            String body = "a".repeat(300) + " Complete finding with the required remediation.";
            ObjectNode review =
                    JSON.createObjectNode().put("summary", "s").put("verdict", "COMMENT");
            review.putArray("lineComments")
                    .addObject()
                    .put("file", "a")
                    .put("line", 1)
                    .put("type", "note")
                    .put("severity", "minor")
                    .put("category", "tests")
                    .put("confidence", "medium")
                    .put("body", body);

            ReviewResult result = ReviewResultParser.parseReview(JSON.writeValueAsString(review));

            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getBody)
                    .isEqualTo(body);
        }
    }

    @Nested
    class RecallAndHistory {
        private String reviewJsonWith(int comments) throws Exception {
            ObjectNode root = JSON.createObjectNode();
            root.put("summary", "s");
            root.put("verdict", "COMMENT");
            var array = root.putArray("lineComments");
            for (int i = 1; i <= comments; i++) {
                ObjectNode comment = array.addObject();
                comment.put("file", "A.java");
                comment.put("line", i);
                comment.put("type", "suggestion");
                comment.put("severity", "minor");
                comment.put("category", "maintainability");
                comment.put("confidence", "medium");
                comment.put("body", "Fix " + i + ".");
                comment.put("rationale", "Line " + i + ".");
            }
            return JSON.writeValueAsString(root);
        }

        @Test
        void parseReviewHonorsAnExplicitCommentCap() throws Exception {
            String raw = reviewJsonWith(35);

            assertThat(ReviewResultParser.parseReview(raw, 30).getLineComments()).hasSize(30);
            assertThat(ReviewResultParser.parseReview(raw).getLineComments()).hasSize(20);
        }

        @Test
        void maxCommentsWidensOnlyForRecall() {
            assertThat(ReviewResultParser.maxComments(fakeRequest())).isEqualTo(20);
            assertThat(
                            ReviewResultParser.maxComments(
                                    PRReviewRequest.builder(fakePr(), "")
                                            .candidateRecall(true)
                                            .build()))
                    .isEqualTo(30);
        }
    }

    @Nested
    class ServiceAndModuleBoundaries {
        @Test
        void updatedCallerEvidenceDoesNotProduceCompatibilityFinding() throws Exception {
            String fixture =
                    "diff --git a/src/Contract.java b/src/Contract.java\n"
                            + "--- a/src/Contract.java\n"
                            + "+++ b/src/Contract.java\n"
                            + "@@ -1,3 +1,3 @@\n"
                            + "-String fetch();\n"
                            + "+String fetchV2();\n"
                            + "diff --git a/src/UpdatedCaller.java b/src/UpdatedCaller.java\n"
                            + "--- a/src/UpdatedCaller.java\n"
                            + "+++ b/src/UpdatedCaller.java\n"
                            + "@@ -1,2 +1,2 @@\n"
                            + "-return contract.fetch();\n"
                            + "+return contract.fetchV2();\n";
            String prompt = ReviewPrompts.buildPrompt(new PRReviewRequest(fakePr(), fixture));
            assertThat(prompt)
                    .contains("src/Contract.java", "fetchV2", "src/UpdatedCaller.java")
                    .contains("Service and module boundaries:");

            ObjectNode review =
                    JSON.createObjectNode()
                            .put("summary", "updated caller")
                            .put("verdict", "APPROVE");
            review.putArray("lineComments");
            assertThat(
                            ReviewResultParser.parseReview(JSON.writeValueAsString(review))
                                    .getLineComments())
                    .isEmpty();
        }

        @Test
        void unupdatedCallerEvidenceProducesCompatibilityFinding() throws Exception {
            String fixture =
                    "diff --git a/src/Contract.java b/src/Contract.java\n"
                            + "--- a/src/Contract.java\n"
                            + "+++ b/src/Contract.java\n"
                            + "@@ -1,1 +1,1 @@\n"
                            + "-String fetch();\n"
                            + "+String fetchV2();\n"
                            + "diff --git a/src/LegacyCaller.java b/src/LegacyCaller.java\n"
                            + "--- a/src/LegacyCaller.java\n"
                            + "+++ b/src/LegacyCaller.java\n"
                            + "@@ -1,1 +1,1 @@\n"
                            + " return contract.fetch();\n";
            String prompt = ReviewPrompts.buildPrompt(new PRReviewRequest(fakePr(), fixture));
            assertThat(prompt)
                    .contains("src/Contract.java", "fetchV2", "src/LegacyCaller.java", "fetch()")
                    .contains("Service and module boundaries:");

            ObjectNode review =
                    JSON.createObjectNode()
                            .put("summary", "legacy caller remains incompatible")
                            .put("verdict", "REQUEST_CHANGES");
            ObjectNode comment = review.putArray("lineComments").addObject();
            comment.put("file", "src/Contract.java")
                    .put("line", 2)
                    .put("type", "issue")
                    .put("severity", "major")
                    .put("category", "compatibility")
                    .put("confidence", "high")
                    .put("rationale", "src/LegacyCaller.java still invokes contract.fetch().")
                    .put("body", "Update src/LegacyCaller.java to invoke fetchV2().");
            String reviewJson = JSON.writeValueAsString(review);
            assertThat(ReviewResultParser.parseReview(reviewJson).getLineComments()).hasSize(1);
            LineComment issue = ReviewResultParser.parseReview(reviewJson).getLineComments().get(0);
            assertThat(issue.getCategory()).isEqualTo("compatibility");
            assertThat(issue.getRationale()).contains("src/LegacyCaller.java");
            assertThat(issue.getBody()).contains("src/LegacyCaller.java");
        }

        @Test
        void noCallerEvidenceProducesNoCompatibilityFinding() throws Exception {
            String fixture =
                    "diff --git a/src/Contract.java b/src/Contract.java\n"
                            + "--- a/src/Contract.java\n"
                            + "+++ b/src/Contract.java\n"
                            + "@@ -1,1 +1,1 @@\n"
                            + "-String fetch();\n"
                            + "+String fetchV2();\n";
            String prompt = ReviewPrompts.buildPrompt(new PRReviewRequest(fakePr(), fixture));
            assertThat(prompt)
                    .contains("src/Contract.java", "fetchV2")
                    .contains("Service and module boundaries:");

            ObjectNode review =
                    JSON.createObjectNode()
                            .put("summary", "no caller located")
                            .put("verdict", "APPROVE");
            review.putArray("lineComments");
            assertThat(
                            ReviewResultParser.parseReview(JSON.writeValueAsString(review))
                                    .getLineComments())
                    .isEmpty();
        }
    }

    @Nested
    class LineCommentSources {

        private ObjectNode modelComment() {
            ObjectNode comment = JSON.createObjectNode();
            comment.put("file", "src/Foo.java");
            comment.put("line", 10);
            comment.put("type", "issue");
            comment.put("severity", "major");
            comment.put("category", "correctness");
            comment.put("confidence", "high");
            comment.put("rationale", "The diff dereferences the nullable value.");
            comment.put("body", "Guard the nullable value.");
            return comment;
        }

        @Test
        void modelOutputCannotSetSourcesOrCorroboration() throws Exception {
            ObjectNode comment = modelComment();
            comment.putArray("sources").add("forged-model").add("other");
            comment.put("corroborated", true);
            ObjectNode review = JSON.createObjectNode();
            review.put("summary", "s");
            review.put("verdict", "REQUEST_CHANGES");
            review.putArray("lineComments").add(comment);

            ReviewResult result = ReviewResultParser.parseReview(JSON.writeValueAsString(review));

            assertThat(result.getLineComments()).singleElement();
            assertThat(result.getLineComments().get(0).getSources()).isEmpty();
        }

        @Test
        void jacksonRoundTripsSources() throws Exception {
            LineComment comment = new LineComment("src/Foo.java", 10, "issue", "b");
            comment.setSources(java.util.List.of("claude", "gpt"));

            LineComment copy = JSON.readValue(JSON.writeValueAsString(comment), LineComment.class);

            assertThat(copy.getSources()).containsExactly("claude", "gpt");
        }

        @Test
        void legacyJsonWithoutSourcesDeserializesToEmpty() throws Exception {
            LineComment copy =
                    JSON.readValue(JSON.writeValueAsString(modelComment()), LineComment.class);

            assertThat(copy.getSources()).isEmpty();
            assertThat(copy.getBody()).isEqualTo("Guard the nullable value.");
        }
    }

    @Nested
    class LineCommentSuggestedChange {

        private ObjectNode modelComment() {
            ObjectNode comment = JSON.createObjectNode();
            comment.put("file", "src/Foo.java");
            comment.put("line", 10);
            comment.put("type", "issue");
            comment.put("severity", "major");
            comment.put("category", "correctness");
            comment.put("confidence", "high");
            comment.put("rationale", "The diff dereferences the nullable value.");
            comment.put("body", "Guard the nullable value.");
            return comment;
        }

        private ReviewResult parse(ObjectNode comment) throws Exception {
            ObjectNode review = JSON.createObjectNode();
            review.put("summary", "s");
            review.put("verdict", "REQUEST_CHANGES");
            review.putArray("lineComments").add(comment);
            return ReviewResultParser.parseReview(JSON.writeValueAsString(review));
        }

        @Test
        void readsAStringSuggestedChange() throws Exception {
            ObjectNode comment = modelComment();
            comment.put("suggestedChange", "    if (value != null) use(value);");

            ReviewResult result = parse(comment);

            assertThat(result.getLineComments())
                    .singleElement()
                    .extracting(LineComment::getSuggestedChange)
                    .isEqualTo("    if (value != null) use(value);");
        }

        @Test
        void ignoresANonStringValueWithoutDroppingTheComment() throws Exception {
            ObjectNode comment = modelComment();
            comment.putArray("suggestedChange").add("x");

            ReviewResult result = parse(comment);

            assertThat(result.getLineComments()).singleElement();
            assertThat(result.getLineComments().get(0).getSuggestedChange()).isEmpty();
        }

        @Test
        void absentFieldLeavesItEmpty() throws Exception {
            ReviewResult result = parse(modelComment());

            assertThat(result.getLineComments().get(0).getSuggestedChange()).isEmpty();
        }

        @Test
        void jacksonRoundTripsSuggestedChange() throws Exception {
            LineComment comment = new LineComment("src/Foo.java", 10, "issue", "b");
            comment.setSuggestedChange("    use(value);");

            LineComment copy = JSON.readValue(JSON.writeValueAsString(comment), LineComment.class);

            assertThat(copy.getSuggestedChange()).isEqualTo("    use(value);");
        }

        @Test
        void legacyJsonWithoutSuggestedChangeDeserializesToEmpty() throws Exception {
            LineComment copy =
                    JSON.readValue(JSON.writeValueAsString(modelComment()), LineComment.class);

            assertThat(copy.getSuggestedChange()).isEmpty();
        }
    }
}
