package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceSubmitBasicsTest extends DraftReviewMutationServiceTestSupport {
    @Nested
    class Submit {
        @Test
        void submitsReviewWithDefaultBodyWhenBlank() throws Exception {
            List<String> bodies = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            bodies.add(jsonBody);
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            return new DraftReviewMutationService.RestResponse(200, "{}");
                        }
                    };
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            client,
                            mapper);

            DraftReviewMutationResult result =
                    service.submit(
                            new DraftReviewMutationService.SubmitParams(
                                    "https://github.com",
                                    "acme",
                                    "repo",
                                    1,
                                    "42",
                                    "REQUEST_CHANGES",
                                    ""));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(bodies).hasSize(1);
            assertThat(mapper.readTree(bodies.get(0)).path("body").textValue())
                    .isEqualTo("Requesting changes.");
        }

        @Test
        void rejectsInvalidSubmitEvent() {
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            new DraftReviewMutationService.GitHubRestClient() {
                                @Override
                                public DraftReviewMutationService.RestResponse get(
                                        String apiBase, String token, String path) {
                                    throw new AssertionError("should not be called");
                                }

                                @Override
                                public DraftReviewMutationService.RestResponse post(
                                        String apiBase,
                                        String token,
                                        String path,
                                        String jsonBody) {
                                    throw new AssertionError("should not be called");
                                }

                                @Override
                                public DraftReviewMutationService.RestResponse put(
                                        String apiBase,
                                        String token,
                                        String path,
                                        String jsonBody) {
                                    throw new AssertionError("should not be called");
                                }

                                @Override
                                public DraftReviewMutationService.RestResponse delete(
                                        String apiBase, String token, String path) {
                                    throw new AssertionError("should not be called");
                                }
                            },
                            mapper);

            DraftReviewMutationResult result =
                    service.submit(
                            new DraftReviewMutationService.SubmitParams(
                                    "https://github.com", "acme", "repo", 1, "42", "BOGUS", "hi"));

            assertThat(result.status()).isEqualTo("invalid_request");
        }
    }
}
