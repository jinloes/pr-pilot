package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceHttpRetryTest {
    @Nested
    class IsRetryableHttpMethod {
        @Test
        void retriesOnlyIdempotentGetAndPutMethods() {
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("GET")).isTrue();
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("PUT")).isTrue();
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("DELETE")).isFalse();
            assertThat(DraftReviewMutationService.isRetryableHttpMethod("POST")).isFalse();
        }
    }

    @Nested
    class HttpGitHubRestClientBehavior {
        private HttpServer server;
        private String apiBase;
        private DraftReviewMutationService.HttpGitHubRestClient client;

        @BeforeEach
        void setUp() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
            client = new DraftReviewMutationService.HttpGitHubRestClient(millis -> {});
        }

        @AfterEach
        void tearDown() {
            if (server != null) {
                server.stop(0);
            }
        }

        @Test
        void getAndPutRetryTransient5xx429AndRateLimited403ThenSucceed() {
            AtomicInteger getAttempts = new AtomicInteger();
            server.createContext(
                    "/get-retry",
                    exchange -> {
                        int attempt = getAttempts.incrementAndGet();
                        if (attempt == 1) {
                            respond(exchange, 503, "{}");
                        } else if (attempt == 2) {
                            exchange.getResponseHeaders().set("x-ratelimit-remaining", "0");
                            respond(exchange, 403, "{\"message\":\"API rate limit exceeded\"}");
                        } else {
                            respond(exchange, 200, "{\"ok\":true}");
                        }
                    });
            AtomicInteger putAttempts = new AtomicInteger();
            server.createContext(
                    "/put-retry",
                    exchange -> {
                        int attempt = putAttempts.incrementAndGet();
                        if (attempt == 1) {
                            respond(exchange, 429, "{}");
                        } else {
                            respond(exchange, 200, "{\"updated\":true}");
                        }
                    });

            assertThat(client.get(apiBase, "tok", "/get-retry").statusCode()).isEqualTo(200);
            assertThat(getAttempts.get()).isEqualTo(3);

            assertThat(client.put(apiBase, "tok", "/put-retry", "{}").statusCode()).isEqualTo(200);
            assertThat(putAttempts.get()).isEqualTo(2);
        }

        @Test
        void nonRateLimited403FailsImmediatelyWithNotAuthenticated() {
            AtomicInteger attempts = new AtomicInteger();
            server.createContext(
                    "/forbidden",
                    exchange -> {
                        attempts.incrementAndGet();
                        respond(exchange, 403, "{\"message\":\"Resource not accessible\"}");
                    });

            assertThatThrownBy(() -> client.get(apiBase, "tok", "/forbidden"))
                    .isInstanceOf(DraftReviewMutationService.MutationException.class)
                    .satisfies(
                            err ->
                                    assertThat(
                                                    ((DraftReviewMutationService.MutationException)
                                                                    err)
                                                            .status())
                                            .isEqualTo("not_authenticated"));
            assertThat(attempts.get()).isEqualTo(1);
        }

        @Test
        void rateLimited403OnPostAndDeleteExecutesSingleAttemptAndMapsToRateLimited() {
            AtomicInteger postAttempts = new AtomicInteger();
            server.createContext(
                    "/post-rl",
                    exchange -> {
                        postAttempts.incrementAndGet();
                        exchange.getResponseHeaders().set("Retry-After", "30");
                        respond(
                                exchange,
                                403,
                                "{\"message\":\"You have exceeded a secondary rate limit\"}");
                    });
            AtomicInteger deleteAttempts = new AtomicInteger();
            server.createContext(
                    "/delete-rl",
                    exchange -> {
                        deleteAttempts.incrementAndGet();
                        exchange.getResponseHeaders().set("x-ratelimit-remaining", "0");
                        respond(exchange, 403, "{\"message\":\"API rate limit exceeded\"}");
                    });

            assertThatThrownBy(() -> client.post(apiBase, "tok", "/post-rl", "{}"))
                    .isInstanceOf(DraftReviewMutationService.MutationException.class)
                    .satisfies(
                            err ->
                                    assertThat(
                                                    ((DraftReviewMutationService.MutationException)
                                                                    err)
                                                            .status())
                                            .isEqualTo("rate_limited"));
            assertThat(postAttempts.get()).isEqualTo(1);

            assertThatThrownBy(() -> client.delete(apiBase, "tok", "/delete-rl"))
                    .isInstanceOf(DraftReviewMutationService.MutationException.class)
                    .satisfies(
                            err ->
                                    assertThat(
                                                    ((DraftReviewMutationService.MutationException)
                                                                    err)
                                                            .status())
                                            .isEqualTo("rate_limited"));
            assertThat(deleteAttempts.get()).isEqualTo(1);
        }

        @Test
        void deleteDoesNotRetryTransportFailure() {
            AtomicInteger deleteAttempts = new AtomicInteger();
            server.createContext(
                    "/delete-drop",
                    exchange -> {
                        deleteAttempts.incrementAndGet();
                        exchange.close();
                    });

            assertThatThrownBy(() -> client.delete(apiBase, "tok", "/delete-drop"))
                    .isInstanceOf(DraftReviewMutationService.MutationException.class)
                    .satisfies(
                            err ->
                                    assertThat(
                                                    ((DraftReviewMutationService.MutationException)
                                                                    err)
                                                            .status())
                                            .isEqualTo("network_error"));
            assertThat(deleteAttempts.get()).isEqualTo(1);
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }
}
