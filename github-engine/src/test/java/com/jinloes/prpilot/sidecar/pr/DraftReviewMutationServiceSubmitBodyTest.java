package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DraftReviewMutationServiceSubmitBodyTest extends DraftReviewMutationServiceTestSupport {
    /** Stub that serves one pending review and records the submit calls on exact paths. */
    private static final class SubmitStub implements DraftReviewMutationService.GitHubRestClient {
        private static final String REVIEW_PATH = "/repos/acme/repo/pulls/1/reviews/42";
        private final List<DraftReviewMutationService.RestResponse> getResponses;
        private final List<String> gets = new ArrayList<>();
        private final List<String> posts = new ArrayList<>();
        private final List<String> postBodies = new ArrayList<>();

        SubmitStub(DraftReviewMutationService.RestResponse... getResponses) {
            this.getResponses = new ArrayList<>(List.of(getResponses));
        }

        static SubmitStub withPendingBody(ObjectMapper mapper, String pendingBody)
                throws Exception {
            return new SubmitStub(
                    new DraftReviewMutationService.RestResponse(
                            200,
                            mapper.writeValueAsString(
                                    mapper.createObjectNode()
                                            .put("id", 42)
                                            .put("state", "PENDING")
                                            .put("body", pendingBody))));
        }

        @Override
        public DraftReviewMutationService.RestResponse get(
                String apiBase, String token, String path) {
            gets.add(path);
            if (!REVIEW_PATH.equals(path) || getResponses.isEmpty()) {
                throw new AssertionError("unexpected GET " + path);
            }
            return getResponses.remove(0);
        }

        @Override
        public DraftReviewMutationService.RestResponse post(
                String apiBase, String token, String path, String jsonBody) {
            if (!(REVIEW_PATH + "/events").equals(path)) {
                throw new AssertionError("unexpected POST " + path);
            }
            posts.add(path);
            postBodies.add(jsonBody);
            return new DraftReviewMutationService.RestResponse(200, "{}");
        }

        @Override
        public DraftReviewMutationService.RestResponse put(
                String apiBase, String token, String path, String jsonBody) {
            throw new AssertionError("should not be called");
        }

        @Override
        public DraftReviewMutationService.RestResponse delete(
                String apiBase, String token, String path) {
            throw new AssertionError("should not be called");
        }
    }

    @Nested
    class Submit {
        private final DraftReviewCodec codec = new DraftReviewCodec(mapper);

        private DraftReviewMutationResult submit(SubmitStub stub, String event, String body) {
            return new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            stub,
                            mapper)
                    .submit(
                            new DraftReviewMutationService.SubmitParams(
                                    "https://github.com", "acme", "repo", 1, "42", event, body));
        }

        private JsonNode onlyPost(SubmitStub stub) throws Exception {
            assertThat(stub.posts).hasSize(1);
            return mapper.readTree(stub.postBodies.get(0));
        }

        private DraftReviewCodec.LineComment comment(String file, int line, String body) {
            return new DraftReviewCodec.LineComment(
                    file, line, "note", body, null, null, null, null, null);
        }

        private String pendingWithSections() {
            return codec.encodeBody(
                            "SUMMARY",
                            "COMMENT",
                            List.of(comment("", 0, "GENERAL"), comment("demo.txt", 2, "INLINE")))
                    + "\n\n"
                    + codec.buildOrphanSection(List.of(comment("demo.txt", 99, "ORPHAN")));
        }

        private static final String SECTIONS =
                "**General Notes:**\n- GENERAL\n\n"
                        + "**Comments not attached inline (invalid diff positions):**\n"
                        + "- `demo.txt:99`: ORPHAN";

        @Test
        void submitComposesReviewerTextWithRetainedSections() throws Exception {
            SubmitStub stub = SubmitStub.withPendingBody(mapper, pendingWithSections());

            DraftReviewMutationResult result = submit(stub, "COMMENT", "  Edited summary  ");

            assertThat(result.status()).isEqualTo("ok");
            assertThat(stub.gets).containsExactly("/repos/acme/repo/pulls/1/reviews/42");
            JsonNode payload = onlyPost(stub);
            assertThat(payload.path("event").textValue()).isEqualTo("COMMENT");
            assertThat(payload.path("body").textValue()).isEqualTo("Edited summary\n\n" + SECTIONS);
        }

        @ParameterizedTest
        @ValueSource(strings = {"APPROVE", "REQUEST_CHANGES", "COMMENT"})
        void submitPublishesRetainedSectionsWhenReviewerTextBlank(String event) throws Exception {
            SubmitStub stub = SubmitStub.withPendingBody(mapper, pendingWithSections());

            DraftReviewMutationResult result = submit(stub, event, "   ");

            assertThat(result.status()).isEqualTo("ok");
            JsonNode payload = onlyPost(stub);
            assertThat(payload.path("event").textValue()).isEqualTo(event);
            assertThat(payload.path("body").textValue()).isEqualTo(SECTIONS);
        }

        @ParameterizedTest
        @CsvSource({
            "APPROVE,Looks good to me!",
            "REQUEST_CHANGES,Requesting changes.",
            "COMMENT,Leaving comments."
        })
        void submitUsesFallbackOnlyWhenNothingElseToPublish(String event, String fallback)
                throws Exception {
            SubmitStub stub =
                    SubmitStub.withPendingBody(
                            mapper,
                            codec.encodeBody(
                                    "SUMMARY", event, List.of(comment("demo.txt", 2, "INLINE"))));

            DraftReviewMutationResult result = submit(stub, event, "");

            assertThat(result.status()).isEqualTo("ok");
            JsonNode payload = onlyPost(stub);
            assertThat(payload.has("body")).isTrue();
            assertThat(payload.path("body").textValue()).isEqualTo(fallback);
        }

        @Test
        void submitKeepsEveryDetachedCommentFromBodyOnlyFallbackDraft() throws Exception {
            List<DraftReviewCodec.LineComment> all =
                    List.of(comment("demo.txt", 2, "FIRST"), comment("demo.txt", 3, "SECOND"));
            String pending =
                    codec.encodeBody("SUMMARY", "COMMENT", all)
                            + "\n\n"
                            + codec.buildOrphanSection(all);
            SubmitStub stub = SubmitStub.withPendingBody(mapper, pending);

            submit(stub, "COMMENT", "Reviewer note");

            assertThat(onlyPost(stub).path("body").textValue())
                    .isEqualTo(
                            "Reviewer note\n\n"
                                    + "**Comments not attached inline (invalid diff positions):**\n"
                                    + "- `demo.txt:2`: FIRST\n"
                                    + "- `demo.txt:3`: SECOND");
        }

        @Test
        void submitPreservesImportedPlainBodyBelowReviewerText() throws Exception {
            SubmitStub stub =
                    SubmitStub.withPendingBody(mapper, "Text written on GitHub.\n\nSecond line.");

            submit(stub, "APPROVE", "LGTM");

            assertThat(onlyPost(stub).path("body").textValue())
                    .isEqualTo("LGTM\n\nText written on GitHub.\n\nSecond line.");
        }

        @Test
        void submitNeverPublishesHiddenPayload() throws Exception {
            String legacy =
                    "<!-- claude-summary: LEGACY SUMMARY -->\n"
                            + "<!-- claude-verdict: APPROVE -->\n"
                            + "<!-- claude-comments: [] -->\n\n"
                            + "**General Notes:**\n- LEGACY NOTE";
            for (String pending : List.of(pendingWithSections(), legacy)) {
                SubmitStub stub = SubmitStub.withPendingBody(mapper, pending);

                submit(stub, "COMMENT", "Body");

                String body = onlyPost(stub).path("body").textValue();
                assertThat(body)
                        .doesNotContain("<!--")
                        .doesNotContain("pr-pilot-review")
                        .doesNotContain("claude-")
                        .doesNotContain("SUMMARY");
            }
        }

        @Test
        void submitDoesNotPostEventWhenPendingReviewCannotBeRead() {
            for (int status : new int[] {404, 500}) {
                SubmitStub stub =
                        new SubmitStub(
                                new DraftReviewMutationService.RestResponse(
                                        status, "{\"message\":\"nope\"}"));

                DraftReviewMutationResult result = submit(stub, "COMMENT", "Body");

                assertThat(result.status()).isEqualTo("api_failed");
                assertThat(stub.gets).hasSize(1);
                assertThat(stub.posts).isEmpty();
            }
        }

        @Test
        void submitTreatsMissingOrNullPendingBodyAsEmpty() throws Exception {
            for (String response : List.of("{\"id\":42}", "{\"id\":42,\"body\":null}")) {
                SubmitStub stub =
                        new SubmitStub(new DraftReviewMutationService.RestResponse(200, response));

                submit(stub, "REQUEST_CHANGES", "");

                assertThat(onlyPost(stub).path("body").textValue())
                        .isEqualTo("Requesting changes.");
            }
        }
    }
}
