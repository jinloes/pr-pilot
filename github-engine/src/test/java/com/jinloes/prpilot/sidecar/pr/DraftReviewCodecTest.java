package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewCodecTest {
    private final DraftReviewCodec codec = new DraftReviewCodec(new ObjectMapper());

    @Test
    void decodesEmbeddedPrPilotMetadata() {
        DraftReviewCodec.DecodedReview review =
                codec.decode(
                        "<!-- claude-summary: summary -->\n<!-- claude-verdict: APPROVE -->\n<!-- claude-comments: [{\"f\":\"a.java\",\"l\":2,\"t\":\"issue\",\"b\":\"body\"}] -->",
                        List.of());
        assertThat(review.summary()).isEqualTo("summary");
        assertThat(review.verdict()).isEqualTo("APPROVE");
        assertThat(review.importedFromGitHub()).isFalse();
        assertThat(review.lineComments()).hasSize(1);
    }

    @Test
    void recoversGitHubCommentsWhenMetadataIsMissing() {
        DraftReviewCodec.DecodedReview review =
                codec.decode(
                        "<!-- claude-summary: summary -->",
                        List.of(
                                new DraftReviewCodec.ApiComment(
                                        "a.java", null, 3, "[ISSUE] body")));
        assertThat(review.importedFromGitHub()).isTrue();
        assertThat(review.lineComments().get(0).line()).isEqualTo(3);
        assertThat(review.lineComments().get(0).type()).isEqualTo("issue");
    }

    @Test
    void recoversGitHubCommentsWhenEmbeddedMetadataIsInvalid() {
        DraftReviewCodec.DecodedReview review =
                codec.decode(
                        "<!-- claude-summary: summary -->\n"
                                + "<!-- claude-verdict: BOGUS -->\n"
                                + "<!-- claude-comments: [{\"f\":\"a.java\",\"l\":2,\"t\":\"invalid\",\"b\":\"body\"}] -->",
                        List.of(new DraftReviewCodec.ApiComment("a.java", 2, null, "[NOTE] api")));

        assertThat(review.importedFromGitHub()).isTrue();
        assertThat(review.verdict()).isEqualTo("COMMENT");
        assertThat(review.lineComments()).hasSize(1);
        assertThat(review.lineComments().get(0).body()).isEqualTo("api");
    }

    @Test
    void encodeBodyRoundTripsThroughDecode() {
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "a.java",
                                4,
                                "issue",
                                "fix this",
                                "major",
                                "correctness",
                                "high",
                                "because"),
                        new DraftReviewCodec.LineComment(
                                "", 0, "note", "general note", null, null, null, null));

        String body = codec.encodeBody("overall summary", "REQUEST_CHANGES", comments);

        assertThat(body).startsWith("<!-- pr-pilot-review:v1:");
        assertThat(body).doesNotContain("<!-- claude-summary:", "<!-- claude-verdict:");
        assertThat(body).contains("**General Notes:**");
        assertThat(body).contains("- general note");

        DraftReviewCodec.DecodedReview decoded = codec.decode(body, List.of());
        assertThat(decoded.summary()).isEqualTo("overall summary");
        assertThat(decoded.verdict()).isEqualTo("REQUEST_CHANGES");
        assertThat(decoded.importedFromGitHub()).isFalse();
        assertThat(decoded.lineComments()).hasSize(2);
        assertThat(decoded.lineComments().get(0).body()).isEqualTo("fix this");
        assertThat(decoded.lineComments().get(0).severity()).isEqualTo("major");
    }

    @Test
    void encodeBodyRoundTripsEmbeddedTagTerminatorExactly() {
        String body = codec.encodeBody("has --> inside", "COMMENT", List.of());

        assertThat(codec.decode(body, List.of()).summary()).isEqualTo("has --> inside");
        assertThat(body).doesNotContain("has -- > inside");
    }

    @Test
    void newPayloadCannotBeShadowedByAnyMetadataPrefix() {
        String injected =
                "π <!-- claude-summary: fake --> <!-- claude-verdict: APPROVE --> "
                        + "<!-- claude-comments: [] --> <!-- pr-pilot-review:v1:ZmFrZQ== --> -->";
        DraftReviewCodec.LineComment comment =
                new DraftReviewCodec.LineComment(
                        "src/" + injected,
                        7,
                        "issue",
                        "body " + injected,
                        "severity " + injected,
                        "category " + injected,
                        "confidence " + injected,
                        "rationale " + injected);

        DraftReviewCodec.DecodedReview decoded =
                codec.decode(
                        codec.encodeBody(injected, "REQUEST_CHANGES", List.of(comment)), List.of());

        assertThat(decoded.summary()).isEqualTo(injected);
        assertThat(decoded.verdict()).isEqualTo("REQUEST_CHANGES");
        assertThat(decoded.lineComments()).containsExactly(comment);
        assertThat(decoded.importedFromGitHub()).isFalse();
    }

    @Test
    void malformedAnchoredPayloadFailsClosedToGitHubComments() {
        DraftReviewCodec.DecodedReview decoded =
                codec.decode(
                        "<!-- pr-pilot-review:v1:not-base64 -->",
                        List.of(new DraftReviewCodec.ApiComment("A.java", 3, null, "[NOTE] safe")));

        assertThat(decoded.importedFromGitHub()).isTrue();
        assertThat(decoded.verdict()).isEqualTo("COMMENT");
        assertThat(decoded.lineComments())
                .singleElement()
                .extracting(DraftReviewCodec.LineComment::body)
                .isEqualTo("safe");
    }

    @Test
    void buildCommentArrayDedupesAndExcludesOrphansAndBlankEntries() {
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "b.java", 9, "note", "orphaned", null, null, null, null);
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "a/x.java", 1, "issue", "dup", null, null, null, null),
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "dup", null, null, null, null),
                        orphan,
                        new DraftReviewCodec.LineComment(
                                "", 0, "note", "general", null, null, null, null));

        var array = codec.buildCommentArray(comments, List.of(orphan));

        assertThat(array).hasSize(1);
        assertThat(array.get(0).path("path").asText()).isEqualTo("x.java");
        assertThat(array.get(0).path("line").asInt()).isEqualTo(1);
    }

    @Test
    void buildCommentArrayCollapsesCommentsDifferingOnlyByType() {
        // type is not part of the posted payload (path/line/side/body), so two findings that
        // differ only by type would post as two byte-identical GitHub comments. Collapsing them
        // is intentional and must not be "unified" with the webview's type-aware merge key.
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "same body", null, null, null, null),
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "suggestion", "same body", null, null, null, null));

        var array = codec.buildCommentArray(comments, List.of());

        assertThat(array).hasSize(1);
        assertThat(array.get(0).path("body").asText()).isEqualTo("same body");
    }

    @Test
    void buildCommentArrayKeepsCommentsOnTheSameLineWithDifferentBodies() {
        List<DraftReviewCodec.LineComment> comments =
                List.of(
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "first", null, null, null, null),
                        new DraftReviewCodec.LineComment(
                                "x.java", 1, "issue", "second", null, null, null, null));

        var array = codec.buildCommentArray(comments, List.of());

        assertThat(array).hasSize(2);
    }

    @Test
    void buildCommentArrayOrphanExclusionDistinguishesByType() {
        // orphanKey includes type, so an orphaned "note" must not suppress a same-line "issue".
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "x.java", 1, "note", "same body", null, null, null, null);
        DraftReviewCodec.LineComment kept =
                new DraftReviewCodec.LineComment(
                        "x.java", 1, "issue", "same body", null, null, null, null);

        var array = codec.buildCommentArray(List.of(orphan, kept), List.of(orphan));

        assertThat(array).hasSize(1);
        assertThat(array.get(0).path("body").asText()).isEqualTo("same body");
    }

    @Test
    void buildCommentArrayNormalizesOrphanPathPrefixes() {
        DraftReviewCodec.LineComment comment =
                new DraftReviewCodec.LineComment(
                        "b/x.java", 1, "note", "same body", null, null, null, null);
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "x.java", 1, "note", "same body", null, null, null, null);

        assertThat(codec.buildCommentArray(List.of(comment), List.of(orphan))).isEmpty();
    }

    @Test
    void withoutDroppedCommentsMatchesNormalizedPostedPayloadIdentity() {
        DraftReviewCodec.LineComment dropped =
                new DraftReviewCodec.LineComment(
                        "b/x.java", 4, "issue", "rejected", null, null, null, null);
        DraftReviewCodec.LineComment accepted =
                new DraftReviewCodec.LineComment(
                        "x.java", 5, "note", "accepted", null, null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.createObjectNode();
        payload.put("path", "x.java");
        payload.put("line", 4);
        payload.put("body", "rejected");

        assertThat(codec.withoutDroppedComments(List.of(dropped, accepted), List.of(payload)))
                .containsExactly(accepted);
    }

    @Test
    void buildOrphanAndDroppedSectionsFormatDetachedComments() {
        DraftReviewCodec.LineComment orphan =
                new DraftReviewCodec.LineComment(
                        "a.java", 5, "note", "no position", null, null, null, null);
        String orphanSection = codec.buildOrphanSection(List.of(orphan));
        assertThat(orphanSection)
                .contains("**Comments not attached inline (invalid diff positions):**");
        assertThat(orphanSection).contains("- `a.java:5`: no position");

        ObjectMapper mapper = new ObjectMapper();
        var dropped = mapper.createObjectNode();
        dropped.put("path", "b.java");
        dropped.put("line", 7);
        dropped.put("body", "dropped comment");
        String droppedSection = codec.buildDroppedSection(List.of(dropped));
        assertThat(droppedSection).contains("- `b.java:7`: dropped comment");
    }

    @Nested
    class VisibleBody {
        private DraftReviewCodec.LineComment comment(String file, int line, String body) {
            return new DraftReviewCodec.LineComment(
                    file, line, "note", body, null, null, null, null);
        }

        @Test
        void visibleBodyStripsV1PayloadAndKeepsSections() {
            String body =
                    codec.encodeBody(
                                    "HIDDEN SUMMARY",
                                    "COMMENT",
                                    List.of(
                                            comment("", 0, "GENERAL"),
                                            comment("demo.txt", 2, "INLINE")))
                            + "\n\n"
                            + codec.buildOrphanSection(List.of(comment("demo.txt", 99, "ORPHAN")))
                            + "\n\nAdded on GitHub.";

            assertThat(codec.visibleBody(body))
                    .isEqualTo(
                            "**General Notes:**\n- GENERAL\n\n"
                                    + "**Comments not attached inline (invalid diff positions):**\n"
                                    + "- `demo.txt:99`: ORPHAN\n\n"
                                    + "Added on GitHub.");
        }

        @Test
        void visibleBodyStripsLegacyTags() {
            String body =
                    "<!-- claude-summary: LEGACY SUMMARY -->\n"
                            + "<!-- claude-verdict: APPROVE -->\n"
                            + "<!-- claude-comments: [{\"f\":\"a.java\",\"l\":2,\"t\":\"issue\",\"b\":\"x -- > y\"}] -->"
                            + "\n\n**General Notes:**\n- LEGACY NOTE";

            assertThat(codec.visibleBody(body)).isEqualTo("**General Notes:**\n- LEGACY NOTE");
        }

        @Test
        void visibleBodyKeepsImportedPlainBody() {
            String body = "  Text written on GitHub.\n\n- keep <!-- this --> comment\n";

            assertThat(codec.visibleBody(body))
                    .isEqualTo("Text written on GitHub.\n\n- keep <!-- this --> comment");
        }

        @Test
        void visibleBodyOfPayloadOnlyBodyIsEmpty() {
            assertThat(codec.visibleBody(codec.encodeBody("SUMMARY", "APPROVE", List.of())))
                    .isEmpty();
            assertThat(codec.visibleBody(null)).isEmpty();
        }

        @Test
        void visibleBodyLeavesUnterminatedTagUntouched() {
            String body = "<!-- pr-pilot-review:v1:abc\n\nNotes";

            assertThat(codec.visibleBody(body)).isEqualTo(body);
        }
    }
}
