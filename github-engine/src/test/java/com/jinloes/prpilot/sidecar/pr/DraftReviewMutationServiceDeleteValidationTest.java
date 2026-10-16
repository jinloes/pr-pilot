package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceDeleteValidationTest extends DraftReviewMutationServiceTestSupport {
    @Nested
    class Delete {
        @Test
        void deletesDraftReview() {
            List<String> paths = new ArrayList<>();
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            throw new AssertionError("should not be called");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("should not be called");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse put(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("should not be called");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse delete(
                                String apiBase, String token, String path) {
                            paths.add(path);
                            return new DraftReviewMutationService.RestResponse(204, "");
                        }
                    };
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            client,
                            mapper);

            DraftReviewMutationResult result =
                    service.delete(
                            new DraftReviewMutationService.DeleteParams(
                                    "https://github.com", "acme", "repo", 1, "42"));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(paths).containsExactly("/repos/acme/repo/pulls/1/reviews/42");
        }

        @Test
        void mapsNotAuthenticatedWithoutCallingClient() {
            DraftReviewMutationService.GitHubRestClient client =
                    new DraftReviewMutationService.GitHubRestClient() {
                        @Override
                        public DraftReviewMutationService.RestResponse get(
                                String apiBase, String token, String path) {
                            throw new AssertionError("should not be called");
                        }

                        @Override
                        public DraftReviewMutationService.RestResponse post(
                                String apiBase, String token, String path, String jsonBody) {
                            throw new AssertionError("should not be called");
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
                    };
            DraftReviewMutationService service =
                    new DraftReviewMutationService(
                            ignored -> GitHubAuthService.TokenResolution.notAuthenticated(),
                            client,
                            mapper);

            DraftReviewMutationResult result =
                    service.delete(
                            new DraftReviewMutationService.DeleteParams(
                                    "https://github.com", "acme", "repo", 1, "42"));

            assertThat(result.status()).isEqualTo("not_authenticated");
        }
    }

    @Nested
    class Save {
        @Test
        void rejectsInvalidPullRequestIdentity() {
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
                    service.save(
                            new DraftReviewMutationService.SaveParams(
                                    "https://github.com",
                                    "acme",
                                    "repo",
                                    0,
                                    "s",
                                    "APPROVE",
                                    List.of(),
                                    List.of()));

            assertThat(result.status()).isEqualTo("invalid_request");
        }
    }
}
