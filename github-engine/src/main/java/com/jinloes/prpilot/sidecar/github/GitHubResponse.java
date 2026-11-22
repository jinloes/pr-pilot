package com.jinloes.prpilot.sidecar.github;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A GitHub REST response reduced to the status, raw body, and the small safe subset of headers
 * needed to distinguish authorization failures from rate limiting.
 *
 * <p>Transport failures are represented as {@link #NETWORK_ERROR} rather than an exception so that
 * every caller maps one uniform shape onto its own domain result.
 */
public record GitHubResponse(int statusCode, String body, Map<String, String> rateLimitHeaders) {
    /** Synthetic status meaning the request never produced an HTTP response. */
    public static final int NETWORK_ERROR = 0;

    /**
     * Synthetic status for a transport failure caused by TLS certificate validation. Still a
     * network error to every caller; only the user-facing message differs.
     */
    public static final int TLS_ERROR = -1;

    private static final List<String> SAFE_RATE_LIMIT_HEADERS =
            List.of("x-ratelimit-remaining", "x-ratelimit-reset", "retry-after");
    private static final String SSO_HEADER = "x-github-sso";

    public GitHubResponse {
        body = body == null ? "" : body;
        rateLimitHeaders = rateLimitHeaders == null ? Map.of() : Map.copyOf(rateLimitHeaders);
    }

    public GitHubResponse(int statusCode, String body) {
        this(statusCode, body, Map.of());
    }

    /** Retains only normalized rate-limit and sanitized SSO requirement markers. */
    public static GitHubResponse fromHeaders(
            int statusCode, String body, Map<String, List<String>> headers) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                String first = entry.getValue().get(0);
                if (SAFE_RATE_LIMIT_HEADERS.contains(name)) {
                    safe.put(name, first);
                } else if (SSO_HEADER.equals(name)
                        && first != null
                        && first.toLowerCase(Locale.ROOT).contains("required")) {
                    // Strip org URL and authorization_request nonce; keep only the boolean signal.
                    safe.put(SSO_HEADER, "required");
                }
            }
        }
        return new GitHubResponse(statusCode, body, safe);
    }

    public static GitHubResponse networkError() {
        return new GitHubResponse(NETWORK_ERROR, "");
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public static GitHubResponse tlsError() {
        return new GitHubResponse(TLS_ERROR, "");
    }

    public boolean isNetworkError() {
        return statusCode == NETWORK_ERROR || statusCode == TLS_ERROR;
    }

    public boolean isTlsError() {
        return statusCode == TLS_ERROR;
    }

    /** True when the token is missing, invalid, or receives a non-rate-limit permission denial. */
    public boolean isUnauthenticated() {
        return statusCode == 401 || (statusCode == 403 && !isRateLimited());
    }

    /** True when GitHub rejected the request due to organization SAML SSO enforcement. */
    public boolean isSsoRequired() {
        if (statusCode != 403 || isRateLimited()) return false;
        if ("required".equals(rateLimitHeaders.get(SSO_HEADER))) return true;
        String normalizedBody = body.toLowerCase(Locale.ROOT);
        return normalizedBody.contains("saml enforcement")
                || normalizedBody.contains("organization saml");
    }

    public boolean isRateLimited() {
        if (statusCode == 429) return true;
        if (statusCode != 403) return false;
        if ("0".equals(rateLimitHeaders.get("x-ratelimit-remaining"))
                || hasText(rateLimitHeaders.get("retry-after"))) {
            return true;
        }
        String normalizedBody = body.toLowerCase(Locale.ROOT);
        return normalizedBody.contains("rate limit")
                && (normalizedBody.contains("exceed") || normalizedBody.contains("secondary"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
