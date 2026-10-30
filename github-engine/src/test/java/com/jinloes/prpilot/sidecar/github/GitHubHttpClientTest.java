package com.jinloes.prpilot.sidecar.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GitHubHttpClientTest {

    /** Records each attempt so retry behavior is asserted without sleeping. */
    private static final class RecordingBackoff implements GitHubHttpClient.Backoff {
        private final List<Integer> pauses = new ArrayList<>();

        @Override
        public void pause(int attempt) {
            pauses.add(attempt);
        }
    }

    /**
     * Replays a scripted sequence of outcomes: a {@link GitHubResponse} is returned, a {@link
     * Throwable} is thrown. The last entry repeats once the script is exhausted, so a one-element
     * script models "always fails this way".
     */
    private static final class ScriptedTransport implements GitHubHttpClient.Transport {
        private final Deque<Object> script;
        private final Object last;
        private final List<HttpRequest> requests = new ArrayList<>();
        private InputStream streamBody = InputStream.nullInputStream();
        private final int streamStatus = 200;

        ScriptedTransport(Object... outcomes) {
            this.script = new ArrayDeque<>(List.of(outcomes));
            this.last = outcomes[outcomes.length - 1];
        }

        @Override
        public GitHubResponse send(HttpRequest request) throws IOException, InterruptedException {
            requests.add(request);
            Object outcome = script.isEmpty() ? last : script.poll();
            if (outcome instanceof IOException ioException) throw ioException;
            if (outcome instanceof InterruptedException interrupted) throw interrupted;
            return (GitHubResponse) outcome;
        }

        @Override
        public <T> T stream(HttpRequest request, GitHubHttpClient.BodyReader<T> reader)
                throws IOException {
            requests.add(request);
            return reader.read(streamStatus, streamBody);
        }
    }

    private static GitHubResponse ok(String body) {
        return new GitHubResponse(200, body);
    }

    /** The shape HttpClient throws when the truststore lacks the server's root CA. */
    static IOException untrustedCertificate() {
        SSLHandshakeException handshake = new SSLHandshakeException("PKIX path building failed");
        handshake.initCause(
                new CertificateException(
                        "PKIX path building failed",
                        new CertPathBuilderException(
                                "unable to find valid certification path to requested target")));
        return handshake;
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        void returnsStatusAndBodyOnSuccess() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(new ScriptedTransport(ok("{\"a\":1}")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).isEqualTo("{\"a\":1}");
            assertThat(result.isSuccess()).isTrue();
            assertThat(backoff.pauses).as("a successful call must not back off").isEmpty();
        }

        @Test
        void doesNotRetryUnauthorized() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(401, "")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isUnauthenticated()).isTrue();
            assertThat(backoff.pauses).isEmpty();
        }

        @Test
        void doesNotRetryNotFound() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(404, "")), backoff);

            assertThat(client.get("https://api.github.com/user", "t").statusCode()).isEqualTo(404);
            assertThat(backoff.pauses).isEmpty();
        }

        @Test
        void retriesRateLimitingThenSucceeds() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(429, ""), ok("done")),
                            backoff);

            assertThat(client.get("https://api.github.com/user", "t").body()).isEqualTo("done");
            assertThat(backoff.pauses).containsExactly(1);
        }

        @Test
        void retriesHeaderConfirmedRateLimit403ButNotPermission403() {
            RecordingBackoff limitedBackoff = new RecordingBackoff();
            GitHubHttpClient limitedClient =
                    new GitHubHttpClient(
                            new ScriptedTransport(
                                    new GitHubResponse(
                                            403, "", Map.of("x-ratelimit-remaining", "0")),
                                    ok("done")),
                            limitedBackoff);

            assertThat(limitedClient.get("https://api.github.com/user", "t").body())
                    .isEqualTo("done");
            assertThat(limitedBackoff.pauses).containsExactly(1);

            RecordingBackoff permissionBackoff = new RecordingBackoff();
            GitHubHttpClient permissionClient =
                    new GitHubHttpClient(
                            new ScriptedTransport(
                                    new GitHubResponse(
                                            403, "{\"message\":\"Resource not accessible\"}")),
                            permissionBackoff);
            assertThat(permissionClient.get("https://api.github.com/user", "t").statusCode())
                    .isEqualTo(403);
            assertThat(permissionBackoff.pauses).isEmpty();
        }

        @Test
        void retriesServerErrorsThenSucceeds() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(503, ""), ok("done")),
                            backoff);

            assertThat(client.get("https://api.github.com/user", "t").body()).isEqualTo("done");
            assertThat(backoff.pauses).containsExactly(1);
        }

        @Test
        void surfacesTheLastStatusWhenRetriesAreExhausted() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(429, "")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isRateLimited())
                    .as("caller still learns it was rate limited")
                    .isTrue();
            assertThat(backoff.pauses)
                    .as("backs off between attempts but not after the final one")
                    .containsExactly(1, 2);
        }

        @Test
        void retriesIoFailuresThenSucceeds() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new IOException("boom"), ok("done")), backoff);

            assertThat(client.get("https://api.github.com/user", "t").body()).isEqualTo("done");
            assertThat(backoff.pauses).containsExactly(1);
        }

        @Test
        void reportsNetworkErrorWhenEveryAttemptThrows() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(new ScriptedTransport(new IOException("boom")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isNetworkError()).isTrue();
            assertThat(result.statusCode()).isEqualTo(GitHubResponse.NETWORK_ERROR);
            assertThat(backoff.pauses).containsExactly(1, 2);
        }

        @Test
        void reportsATlsErrorWithoutRetryingWhenCertificateValidationFails() {
            RecordingBackoff backoff = new RecordingBackoff();
            ScriptedTransport transport =
                    new ScriptedTransport(untrustedCertificate(), ok("unexpected"));

            GitHubResponse result =
                    new GitHubHttpClient(transport, backoff)
                            .get("https://api.github.com/user", "t");

            assertThat(result.isTlsError()).isTrue();
            assertThat(result.isNetworkError()).isTrue();
            assertThat(transport.requests).hasSize(1);
            assertThat(backoff.pauses).isEmpty();
        }

        @Test
        void getOnceReportsATlsErrorForCertificateFailures() {
            GitHubResponse result =
                    new GitHubHttpClient(
                                    new ScriptedTransport(untrustedCertificate()),
                                    new RecordingBackoff())
                            .getOnce("https://api.github.com/user", "t", Duration.ofSeconds(3));

            assertThat(result.isTlsError()).isTrue();
        }

        @Test
        void stopsAtTheConfiguredAttemptCeiling() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(500, "")), backoff);

            client.get("https://api.github.com/user", "t");

            assertThat(backoff.pauses).hasSize(GitHubHttpClient.MAX_ATTEMPTS - 1);
        }

        @Test
        void reportsNetworkErrorForAMalformedUrlRatherThanThrowing() {
            GitHubHttpClient client =
                    new GitHubHttpClient(new ScriptedTransport(ok("x")), new RecordingBackoff());

            assertThat(client.get("not a url", "t").isNetworkError()).isTrue();
        }

        @Test
        void stopsImmediatelyAndRestoresTheFlagWhenInterrupted() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new InterruptedException("stop")), backoff);

            GitHubResponse result = client.get("https://api.github.com/user", "t");

            assertThat(result.isNetworkError()).isTrue();
            assertThat(backoff.pauses).isEmpty();
            assertThat(Thread.interrupted()).as("interrupt flag is restored").isTrue();
        }

        @Test
        void getOnceUsesTheRequestedDeadlineWithoutRetrying() {
            RecordingBackoff backoff = new RecordingBackoff();
            ScriptedTransport transport =
                    new ScriptedTransport(new GitHubResponse(503, ""), ok("unexpected"));

            GitHubResponse result =
                    new GitHubHttpClient(transport, backoff)
                            .getOnce("https://api.github.com/user", "token", Duration.ofSeconds(3));

            assertThat(result.statusCode()).isEqualTo(503);
            assertThat(transport.requests).hasSize(1);
            assertThat(transport.requests.get(0).timeout()).contains(Duration.ofSeconds(3));
            assertThat(backoff.pauses).isEmpty();
        }
    }

    @Nested
    @DisplayName("postJson")
    class PostJson {

        @Test
        void sendsAnAuthenticatedJsonPost() {
            ScriptedTransport transport = new ScriptedTransport(ok("{\"data\":{}}"));

            GitHubResponse result =
                    new GitHubHttpClient(transport, new RecordingBackoff())
                            .postJson(
                                    "https://api.github.com/graphql",
                                    "secret-token",
                                    "{\"query\":\"query { viewer { login } }\"}");

            assertThat(result.isSuccess()).isTrue();
            HttpRequest request = transport.requests.get(0);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.headers().firstValue("Authorization"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith("Bearer "));
            assertThat(request.headers().firstValue("Accept"))
                    .contains(GitHubHttpClient.ACCEPT_JSON);
            assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(request.bodyPublisher()).isPresent();
        }

        @Test
        void retriesTransientFailures() {
            RecordingBackoff backoff = new RecordingBackoff();
            GitHubHttpClient client =
                    new GitHubHttpClient(
                            new ScriptedTransport(new GitHubResponse(503, ""), ok("{\"data\":{}}")),
                            backoff);

            assertThat(
                            client.postJson(
                                            "https://api.github.com/graphql",
                                            "token",
                                            "{\"query\":\"query { viewer { login } }\"}")
                                    .isSuccess())
                    .isTrue();
            assertThat(backoff.pauses).containsExactly(1);
        }

        @Test
        void postJsonOnceUsesTheRequestedDeadlineWithoutRetrying() {
            RecordingBackoff backoff = new RecordingBackoff();
            ScriptedTransport transport =
                    new ScriptedTransport(new IOException("timeout"), ok("{\"data\":{}}"));

            GitHubResponse result =
                    new GitHubHttpClient(transport, backoff)
                            .postJsonOnce(
                                    "https://api.github.com/graphql",
                                    "token",
                                    "{\"query\":\"query { viewer { login } }\"}",
                                    Duration.ofSeconds(3));

            assertThat(result.isNetworkError()).isTrue();
            assertThat(transport.requests).hasSize(1);
            assertThat(transport.requests.get(0).timeout()).contains(Duration.ofSeconds(3));
            assertThat(backoff.pauses).isEmpty();
        }
    }

    @Nested
    @DisplayName("request")
    class Request {

        @Test
        void sendsBearerTokenAndJsonAcceptByDefault() {
            ScriptedTransport transport = new ScriptedTransport(ok("x"));
            new GitHubHttpClient(transport, new RecordingBackoff())
                    .get("https://api.github.com/user", "secret-token");

            HttpRequest request = transport.requests.get(0);
            assertThat(request.headers().firstValue("Authorization"))
                    .contains("Bearer secret-token");
            assertThat(request.headers().firstValue("Accept"))
                    .contains(GitHubHttpClient.ACCEPT_JSON);
            assertThat(request.headers().firstValue("X-GitHub-Api-Version")).contains("2022-11-28");
            assertThat(request.headers().firstValue("User-Agent")).isPresent();
            assertThat(request.method()).isEqualTo("GET");
        }

        @Test
        void honoursAnExplicitAcceptMediaType() {
            ScriptedTransport transport = new ScriptedTransport(ok("diff"));
            new GitHubHttpClient(transport, new RecordingBackoff())
                    .get("https://api.github.com/x", "t", GitHubHttpClient.ACCEPT_DIFF);

            assertThat(transport.requests.get(0).headers().firstValue("Accept"))
                    .contains(GitHubHttpClient.ACCEPT_DIFF);
        }
    }

    @Nested
    @DisplayName("stream")
    class Stream {

        @Test
        void handsTheStatusAndBodyToTheReader() throws Exception {
            ScriptedTransport transport = new ScriptedTransport(ok("unused"));
            transport.streamBody =
                    new ByteArrayInputStream("diff --git a b".getBytes(StandardCharsets.UTF_8));

            String read =
                    new GitHubHttpClient(transport, new RecordingBackoff())
                            .stream(
                                    "https://api.github.com/x",
                                    "t",
                                    GitHubHttpClient.ACCEPT_DIFF,
                                    (status, body) ->
                                            status
                                                    + ":"
                                                    + new String(
                                                            body.readAllBytes(),
                                                            StandardCharsets.UTF_8));

            assertThat(read).isEqualTo("200:diff --git a b");
        }

        @Test
        void letsTheReaderBoundHowManyBytesItConsumes() throws Exception {
            ScriptedTransport transport = new ScriptedTransport(ok("unused"));
            transport.streamBody =
                    new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8));

            String read =
                    new GitHubHttpClient(transport, new RecordingBackoff())
                            .stream(
                                    "https://api.github.com/x",
                                    "t",
                                    GitHubHttpClient.ACCEPT_DIFF,
                                    (status, body) ->
                                            new String(body.readNBytes(4), StandardCharsets.UTF_8));

            assertThat(read).isEqualTo("0123");
        }
    }
}
