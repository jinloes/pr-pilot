package com.jinloes.prpilot.sidecar.github;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * The single authenticated GitHub HTTP transport for {@code github-engine}.
 *
 * <p>Owns the header set, timeout, and retry/backoff policy so services do not build {@link
 * HttpRequest}s themselves. Required GitHub calls use the default retry policy; optional enrichment
 * calls can select a shorter single-attempt deadline.
 *
 * <p>The token is a parameter rather than state: it is resolved per call from {@code gh} and is
 * never logged, returned, or stored here.
 *
 * <p>Retries {@code 429} and {@code 5xx} responses and I/O failures up to {@link #MAX_ATTEMPTS}
 * times with linear backoff. Exhausted retries and transport failures surface as {@link
 * GitHubResponse#NETWORK_ERROR} (status {@code 0}) rather than an exception, so callers handle one
 * uniform failure shape.
 */
public final class GitHubHttpClient {
    /** GitHub's versioned JSON media type, used for all metadata reads. */
    public static final String ACCEPT_JSON = "application/vnd.github.v3+json";

    /** Media type that makes GitHub return a unified diff instead of JSON. */
    public static final String ACCEPT_DIFF = "application/vnd.github.v3.diff";

    static final int MAX_ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "pr-pilot-engine/0.1";
    private static final String API_VERSION = "2022-11-28";

    private final Transport transport;
    private final Backoff backoff;

    public GitHubHttpClient() {
        this(new JdkTransport(), new ThreadBackoff());
    }

    GitHubHttpClient(Transport transport, Backoff backoff) {
        this.transport = Objects.requireNonNull(transport);
        this.backoff = Objects.requireNonNull(backoff);
    }

    /** Issues an authenticated JSON GET against an absolute URL, retrying transient failures. */
    public GitHubResponse get(String url, String token) {
        return get(url, token, ACCEPT_JSON);
    }

    /** Issues an authenticated GET with an explicit {@code Accept} media type. */
    public GitHubResponse get(String url, String token, String accept) {
        HttpRequest request;
        try {
            request = getRequest(url, token, accept, TIMEOUT);
        } catch (IllegalArgumentException exception) {
            return GitHubResponse.networkError();
        }
        return send(request);
    }

    /** Issues one authenticated JSON GET with an explicit request deadline and no retries. */
    public GitHubResponse getOnce(String url, String token, Duration timeout) {
        HttpRequest request;
        try {
            request = getRequest(url, token, ACCEPT_JSON, timeout);
        } catch (IllegalArgumentException exception) {
            return GitHubResponse.networkError();
        }
        return sendOnce(request);
    }

    /** Issues an authenticated JSON POST, retrying with the same bounded policy as JSON GETs. */
    public GitHubResponse postJson(String url, String token, String body) {
        HttpRequest request;
        try {
            request = postRequest(url, token, body, TIMEOUT);
        } catch (IllegalArgumentException exception) {
            return GitHubResponse.networkError();
        }
        return send(request);
    }

    /** Issues one authenticated JSON POST with an explicit request deadline and no retries. */
    public GitHubResponse postJsonOnce(String url, String token, String body, Duration timeout) {
        HttpRequest request;
        try {
            request = postRequest(url, token, body, timeout);
        } catch (IllegalArgumentException exception) {
            return GitHubResponse.networkError();
        }
        return sendOnce(request);
    }

    private GitHubResponse send(HttpRequest request) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                GitHubResponse response = transport.send(request);
                if (isRetryable(response) && attempt < MAX_ATTEMPTS) {
                    backoff.pause(attempt);
                    continue;
                }
                return response;
            } catch (IOException exception) {
                if (GitHubFailure.isCertificateFailure(exception)) {
                    return GitHubResponse.tlsError();
                }
                if (attempt == MAX_ATTEMPTS) {
                    return GitHubResponse.networkError();
                }
                backoff.pause(attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return GitHubResponse.networkError();
            }
        }
        return GitHubResponse.networkError();
    }

    private GitHubResponse sendOnce(HttpRequest request) {
        try {
            return transport.send(request);
        } catch (IOException exception) {
            return GitHubFailure.isCertificateFailure(exception)
                    ? GitHubResponse.tlsError()
                    : GitHubResponse.networkError();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return GitHubResponse.networkError();
        }
    }

    /**
     * Streams an authenticated GET body through {@code reader} so the caller can bound how many
     * bytes it materializes. Single attempt: the diff path orchestrates its own retries because it
     * distinguishes transient from terminal API failures differently than {@link #get}.
     */
    public <T> T stream(String url, String token, String accept, BodyReader<T> reader)
            throws IOException, InterruptedException {
        return transport.stream(getRequest(url, token, accept, TIMEOUT), reader);
    }

    private static HttpRequest.Builder requestBuilder(
            String url, String token, String accept, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", USER_AGENT);
    }

    private static HttpRequest getRequest(
            String url, String token, String accept, Duration timeout) {
        return requestBuilder(url, token, accept, timeout).GET().build();
    }

    private static HttpRequest postRequest(
            String url, String token, String body, Duration timeout) {
        return requestBuilder(url, token, ACCEPT_JSON, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static boolean isRetryable(GitHubResponse response) {
        return response.isRateLimited() || response.statusCode() >= 500;
    }

    /** Consumes a streamed response body together with its status code. */
    @FunctionalInterface
    public interface BodyReader<T> {
        T read(int statusCode, InputStream body) throws IOException;
    }

    /** The raw send operation. Injected so tests exercise retry policy without opening sockets. */
    interface Transport {
        GitHubResponse send(HttpRequest request) throws IOException, InterruptedException;

        <T> T stream(HttpRequest request, BodyReader<T> reader)
                throws IOException, InterruptedException;
    }

    /** Pause between retry attempts; overridable so tests do not sleep. */
    interface Backoff {
        void pause(int attempt);
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public GitHubResponse send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<String> response =
                    httpClient.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return GitHubResponse.fromHeaders(
                    response.statusCode(), response.body(), response.headers().map());
        }

        @Override
        public <T> T stream(HttpRequest request, BodyReader<T> reader)
                throws IOException, InterruptedException {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return reader.read(response.statusCode(), body);
            }
        }
    }

    private static final class ThreadBackoff implements Backoff {
        @Override
        public void pause(int attempt) {
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
