package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Loads pull-request metadata without exposing GitHub credentials outside the sidecar process. */
public final class PrDetailService {
    private static final Pattern REPOSITORY_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern HEX_OBJECT_NAME = Pattern.compile("(?i)[0-9a-f]{40}|[0-9a-f]{64}");

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final DetailClient detailClient;

    public PrDetailService() {
        this(new GitHubAuthService.ProcessTokenResolver(), new HttpDetailClient());
    }

    PrDetailService(GitHubAuthService.TokenResolver tokenResolver, DetailClient detailClient) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.detailClient = Objects.requireNonNull(detailClient);
    }

    public PrDetailResult get(PrDetailParams params) {
        if (!isValidIdentity(params.owner(), params.repo(), params.number())) {
            return PrDetailResult.failure("invalid_request", "Pull request identity is invalid.");
        }

        GitHubApiBase baseUrls;
        try {
            baseUrls = GitHubApiBase.require(params.githubBaseUrl());
        } catch (IllegalArgumentException exception) {
            return PrDetailResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        }

        GitHubAuthService.TokenResolution tokenResolution =
                tokenResolver.resolve(baseUrls.hostnameArgument());
        if (tokenResolution.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED) {
            return PrDetailResult.failure("not_installed", "GitHub CLI is not installed.");
        }
        if (tokenResolution.status() != GitHubAuthService.TokenStatus.RESOLVED) {
            return PrDetailResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        }

        DetailResponse response =
                detailClient.get(
                        baseUrls.apiBaseUrl(),
                        tokenResolution.token(),
                        params.owner(),
                        params.repo(),
                        params.number());
        return switch (response.status()) {
            case OK -> PrDetailResult.success(response.detail());
            case NOT_AUTHENTICATED ->
                    PrDetailResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host.");
            case RATE_LIMITED ->
                    PrDetailResult.failure(
                            "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
            case NETWORK_ERROR ->
                    PrDetailResult.failure(
                            "network_error", "Unable to reach GitHub. Check your connection.");
            case API_FAILED -> PrDetailResult.failure("api_failed", "GitHub API request failed.");
        };
    }

    public record PrDetailParams(String githubBaseUrl, String owner, String repo, int number) {}

    interface DetailClient {
        DetailResponse get(String apiBaseUrl, String token, String owner, String repo, int number);
    }

    record DetailResponse(DetailStatus status, PrDetail detail) {
        static DetailResponse success(PrDetail detail) {
            return new DetailResponse(DetailStatus.OK, detail);
        }

        static DetailResponse of(DetailStatus status) {
            return new DetailResponse(status, null);
        }
    }

    enum DetailStatus {
        OK,
        NOT_AUTHENTICATED,
        RATE_LIMITED,
        NETWORK_ERROR,
        API_FAILED
    }

    private static final class HttpDetailClient implements DetailClient {
        private final GitHubHttpClient httpClient = new GitHubHttpClient();

        @Override
        public DetailResponse get(
                String apiBaseUrl, String token, String owner, String repo, int number) {
            String url =
                    apiBaseUrl
                            + "/repos/"
                            + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                            + "/"
                            + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                            + "/pulls/"
                            + number;
            GitHubResponse response = httpClient.get(url, token);
            if (response.isUnauthenticated()) {
                return DetailResponse.of(DetailStatus.NOT_AUTHENTICATED);
            }
            if (response.isRateLimited()) {
                return DetailResponse.of(DetailStatus.RATE_LIMITED);
            }
            if (response.isNetworkError()) {
                return DetailResponse.of(DetailStatus.NETWORK_ERROR);
            }
            if (!response.isSuccess()) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            return parseDetailResponse(response.body());
        }
    }

    static DetailResponse parseDetailResponse(String body) {
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            if (root == null || !root.isObject() || !root.path("merged").isBoolean()) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            String title = nullableText(root, "title");
            String description = nullableText(root, "body");
            if (title == null || description == null) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            PrDetail.Head head = parseHead(root.path("head"));
            if (!root.path("head").isNull() && head == null) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            String baseRepoFullName = parseBaseRepo(root.path("base"));
            if (!root.path("base").isNull() && baseRepoFullName == null) {
                return DetailResponse.of(DetailStatus.API_FAILED);
            }
            return DetailResponse.success(
                    new PrDetail(
                            root.path("merged").booleanValue(),
                            title,
                            description,
                            head,
                            baseRepoFullName,
                            parseBaseSha(root.path("base"))));
        } catch (IOException exception) {
            return DetailResponse.of(DetailStatus.API_FAILED);
        }
    }

    private static PrDetail.Head parseHead(JsonNode head) {
        if (head.isNull()) {
            return null;
        }
        if (!head.isObject() || !head.path("sha").isTextual() || !head.path("ref").isTextual()) {
            return null;
        }
        JsonNode repo = head.path("repo");
        if (!repo.isNull()
                && (!repo.isObject()
                        || !repo.path("full_name").isTextual()
                        || !repo.path("clone_url").isTextual())) {
            return null;
        }
        return new PrDetail.Head(
                head.path("sha").textValue(),
                head.path("ref").textValue(),
                repo.isNull() ? null : repo.path("full_name").textValue(),
                repo.isNull() ? null : repo.path("clone_url").textValue());
    }

    private static String parseBaseRepo(JsonNode base) {
        if (base.isNull()) {
            return null;
        }
        if (!base.isObject()) {
            return null;
        }
        JsonNode repo = base.path("repo");
        return repo.isNull()
                ? ""
                : repo.isObject() && repo.path("full_name").isTextual()
                        ? repo.path("full_name").textValue()
                        : null;
    }

    private static String parseBaseSha(JsonNode base) {
        JsonNode sha = base.path("sha");
        return sha.isTextual() && HEX_OBJECT_NAME.matcher(sha.textValue()).matches()
                ? sha.textValue()
                : null;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() ? "" : value.isTextual() ? value.textValue() : null;
    }

    private static boolean isValidIdentity(String owner, String repo, int number) {
        return number > 0
                && owner != null
                && repo != null
                && REPOSITORY_SEGMENT.matcher(owner).matches()
                && REPOSITORY_SEGMENT.matcher(repo).matches();
    }
}
