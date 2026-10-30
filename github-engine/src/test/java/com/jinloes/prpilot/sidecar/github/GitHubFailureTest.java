package com.jinloes.prpilot.sidecar.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GitHubFailureTest {

    @Nested
    class Of {
        @Test
        void returnsNullForEverySuccessStatus() {
            for (int status : new int[] {200, 201, 204, 299}) {
                assertThat(GitHubFailure.of(new GitHubResponse(status, "{}")))
                        .as("%d", status)
                        .isNull();
            }
        }

        @Test
        void mapsUnauthenticatedStatusesToAnActionableMessage() {
            for (int status : new int[] {401, 403}) {
                assertThat(GitHubFailure.of(new GitHubResponse(status, "")))
                        .as("%d", status)
                        .isEqualTo(GitHubFailure.NOT_AUTHENTICATED);
            }
            assertThat(GitHubFailure.NOT_AUTHENTICATED.message()).contains("gh auth login");
        }

        @Test
        void mapsRateLimitingSeparatelyFromGenericFailure() {
            assertThat(GitHubFailure.of(new GitHubResponse(429, "")))
                    .isEqualTo(GitHubFailure.RATE_LIMITED);
            assertThat(
                            GitHubFailure.of(
                                    new GitHubResponse(
                                            403,
                                            "",
                                            Map.of(
                                                    "x-ratelimit-remaining",
                                                    "0",
                                                    "x-ratelimit-reset",
                                                    "1900000000"))))
                    .isEqualTo(GitHubFailure.RATE_LIMITED);
            assertThat(GitHubFailure.of(new GitHubResponse(403, "", Map.of("retry-after", "60"))))
                    .isEqualTo(GitHubFailure.RATE_LIMITED);
            assertThat(
                            GitHubFailure.of(
                                    new GitHubResponse(
                                            403,
                                            "{\"message\":\"You have exceeded a secondary rate limit.\"}")))
                    .isEqualTo(GitHubFailure.RATE_LIMITED);
            assertThat(GitHubFailure.of(new GitHubResponse(500, "")))
                    .isEqualTo(GitHubFailure.API_FAILED);
        }

        @Test
        void keepsUnknownAndPermission403AsAuthorizationFailures() {
            assertThat(
                            GitHubFailure.of(
                                    new GitHubResponse(
                                            403,
                                            "{\"message\":\"Resource not accessible by integration\"}")))
                    .isEqualTo(GitHubFailure.NOT_AUTHENTICATED);
            assertThat(
                            GitHubFailure.of(
                                    new GitHubResponse(
                                            403, "", Map.of("x-ratelimit-remaining", "42"))))
                    .isEqualTo(GitHubFailure.NOT_AUTHENTICATED);
        }

        @Test
        void retainsOnlyNormalizedSafeRateLimitHeaders() {
            GitHubResponse response =
                    GitHubResponse.fromHeaders(
                            403,
                            "",
                            Map.of(
                                    "X-RateLimit-Remaining",
                                    List.of("0"),
                                    "Retry-After",
                                    List.of("30"),
                                    "Set-Cookie",
                                    List.of("private")));

            assertThat(response.rateLimitHeaders())
                    .containsOnly(
                            Map.entry("x-ratelimit-remaining", "0"),
                            Map.entry("retry-after", "30"));
        }

        @Test
        void mapsTheSyntheticTransportStatusToANetworkError() {
            assertThat(GitHubFailure.of(GitHubResponse.networkError()))
                    .isEqualTo(GitHubFailure.NETWORK_ERROR);
        }

        @Test
        void mapsACertificateFailureToTheTruststoreMessage() {
            assertThat(GitHubFailure.of(GitHubResponse.tlsError()))
                    .isEqualTo(GitHubFailure.TLS_UNTRUSTED);
        }

        @Test
        void mapsUnexpectedClientErrorsToGenericApiFailure() {
            assertThat(GitHubFailure.of(new GitHubResponse(404, "")))
                    .isEqualTo(GitHubFailure.API_FAILED);
        }
    }

    @Nested
    class Constants {
        @Test
        void exposeStableStatusStringsHostsBranchOn() {
            assertThat(GitHubFailure.INVALID_BASE_URL.status()).isEqualTo("invalid_base_url");
            assertThat(GitHubFailure.INVALID_REQUEST.status()).isEqualTo("invalid_request");
            assertThat(GitHubFailure.NOT_INSTALLED.status()).isEqualTo("not_installed");
            assertThat(GitHubFailure.NOT_AUTHENTICATED.status()).isEqualTo("not_authenticated");
            assertThat(GitHubFailure.RATE_LIMITED.status()).isEqualTo("rate_limited");
            assertThat(GitHubFailure.NETWORK_ERROR.status()).isEqualTo("network_error");
            assertThat(GitHubFailure.API_FAILED.status()).isEqualTo("api_failed");
        }

        @Test
        void reuseTheApiFailedStatusForMalformedBodies() {
            assertThat(GitHubFailure.INVALID_RESPONSE.status()).isEqualTo("api_failed");
            assertThat(GitHubFailure.INVALID_RESPONSE.message())
                    .isNotEqualTo(GitHubFailure.API_FAILED.message());
        }

        @Test
        void keepTheNetworkStatusForCertificateFailuresWithAPhraseHostsMatch() {
            assertThat(GitHubFailure.TLS_UNTRUSTED.status()).isEqualTo("network_error");
            assertThat(GitHubFailure.TLS_UNTRUSTED.message().toLowerCase())
                    .contains("tls certificate")
                    .contains("cacerts")
                    .doesNotContain("auth", "timeout", "unable to reach");
        }
    }

    @Nested
    class NetworkFailure {
        @Test
        void distinguishesCertificateFailuresFromOtherTransportErrors() {
            assertThat(GitHubFailure.networkFailure(GitHubResponse.tlsError()))
                    .isEqualTo(GitHubFailure.TLS_UNTRUSTED);
            assertThat(GitHubFailure.networkFailure(GitHubResponse.networkError()))
                    .isEqualTo(GitHubFailure.NETWORK_ERROR);
        }
    }

    @Nested
    class IsCertificateFailure {
        @Test
        void findsACertificateCauseNestedInAHandshakeFailure() {
            assertThat(
                            GitHubFailure.isCertificateFailure(
                                    GitHubHttpClientTest.untrustedCertificate()))
                    .isTrue();
        }

        @Test
        void recognizesEachCertificateValidationType() {
            assertThat(GitHubFailure.isCertificateFailure(new CertificateException("x"))).isTrue();
            assertThat(GitHubFailure.isCertificateFailure(new CertPathBuilderException("x")))
                    .isTrue();
            assertThat(GitHubFailure.isCertificateFailure(new CertPathValidatorException("x")))
                    .isTrue();
        }

        @Test
        void rejectsOtherTransportFailuresAndNull() {
            assertThat(GitHubFailure.isCertificateFailure(new IOException("connection reset")))
                    .isFalse();
            assertThat(
                            GitHubFailure.isCertificateFailure(
                                    new SSLHandshakeException("Received fatal alert")))
                    .isFalse();
            assertThat(GitHubFailure.isCertificateFailure(null)).isFalse();
        }
    }
}
