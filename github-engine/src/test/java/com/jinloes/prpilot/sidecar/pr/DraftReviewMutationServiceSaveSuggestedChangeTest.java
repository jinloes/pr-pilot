package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceSaveSuggestedChangeTest
        extends DraftReviewMutationServiceTestSupport {
    @Nested
    class Save {
        @Nested
        class SuggestedChange {
            private final List<String> calls = new ArrayList<>();
            private final List<String> postedBodies = new ArrayList<>();

            private DraftReviewMutationService service(String pendingBody) {
                DraftReviewMutationService.GitHubRestClient client =
                        new DraftReviewMutationService.GitHubRestClient() {
                            @Override
                            public DraftReviewMutationService.RestResponse get(
                                    String apiBase, String token, String path) {
                                if (isReviewList(path)) {
                                    try {
                                        return new DraftReviewMutationService.RestResponse(
                                                200,
                                                pendingBody == null
                                                        ? "[]"
                                                        : mapper.writeValueAsString(
                                                                List.of(
                                                                        Map.of(
                                                                                "id",
                                                                                7,
                                                                                "state",
                                                                                "PENDING",
                                                                                "commit_id",
                                                                                "abc123",
                                                                                "body",
                                                                                pendingBody))));
                                    } catch (Exception exception) {
                                        throw new AssertionError(exception);
                                    }
                                }
                                return new DraftReviewMutationService.RestResponse(
                                        200, "{\"head\":{\"sha\":\"abc123\"}}");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse post(
                                    String apiBase, String token, String path, String jsonBody) {
                                calls.add("POST");
                                postedBodies.add(jsonBody);
                                return new DraftReviewMutationService.RestResponse(
                                        200, "{\"id\":42}");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse put(
                                    String apiBase, String token, String path, String jsonBody) {
                                calls.add("PUT");
                                return new DraftReviewMutationService.RestResponse(200, "{}");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse delete(
                                    String apiBase, String token, String path) {
                                calls.add("DELETE");
                                return new DraftReviewMutationService.RestResponse(200, "{}");
                            }
                        };
                return new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);
            }

            private DraftReviewMutationService.CommentInput input(String suggestion) {
                return new DraftReviewMutationService.CommentInput(
                        "a.java", 3, "issue", "fix", null, null, "high", null, suggestion);
            }

            private String pendingBody(String suggestion) {
                return new DraftReviewCodec(mapper)
                        .encodeBody(
                                "summary",
                                "APPROVE",
                                List.of(
                                        new DraftReviewCodec.LineComment(
                                                "a.java",
                                                3,
                                                "issue",
                                                "fix",
                                                null,
                                                null,
                                                "high",
                                                null,
                                                suggestion)));
            }

            @Test
            void postsTheSuggestionFenceInTheInlineComment() throws Exception {
                DraftReviewMutationResult result =
                        service(null).save(saveParams(List.of(input("return a;\n")), List.of()));

                assertThat(result.status()).isEqualTo("ok");
                assertThat(
                                mapper.readTree(postedBodies.get(0))
                                        .path("comments")
                                        .get(0)
                                        .path("body")
                                        .asText())
                        .isEqualTo("fix\n\n```suggestion\nreturn a;\n```");
            }

            @Test
            void anUnchangedResaveIsANoOp() {
                DraftReviewMutationResult result =
                        service(pendingBody("return a;"))
                                .save(saveParams(List.of(input("return a;")), List.of()));

                assertThat(result.status()).isEqualTo("ok");
                assertThat(result.reviewId()).isEqualTo("7");
                assertThat(calls).doesNotContain("POST", "DELETE");
            }

            @Test
            void aBlankSuggestionMatchesADraftWithoutOne() {
                DraftReviewMutationResult result =
                        service(pendingBody(null))
                                .save(saveParams(List.of(input("  ")), List.of()));

                assertThat(result.reviewId()).isEqualTo("7");
                assertThat(calls).doesNotContain("POST", "DELETE");
            }

            @Test
            void aChangedSuggestionRecreatesTheDraft() {
                service(pendingBody("return a;"))
                        .save(saveParams(List.of(input("return b;")), List.of()));

                assertThat(calls).containsExactly("DELETE", "POST");
            }
        }
    }
}
