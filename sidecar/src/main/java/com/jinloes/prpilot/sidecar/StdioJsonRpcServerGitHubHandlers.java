package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.sidecar.pr.CheckRunService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.IncrementalDiffService;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueService;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSupplementalService;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers and handles JSON-RPC methods backed by the GitHub engine. */
final class StdioJsonRpcServerGitHubHandlers {
    private final StdioJsonRpcServerSupport support;
    private final GitHubEngineApi github;

    StdioJsonRpcServerGitHubHandlers(StdioJsonRpcServerSupport support, GitHubEngineApi github) {
        this.support = support;
        this.github = github;
    }

    void register(Map<String, StdioJsonRpcServer.MethodHandler> handlers) {
        handlers.put("repo/detect", this::detectRepo);
        handlers.put("github/checkAuth", this::checkGitHubAuth);
        handlers.put("prs/list", this::listPullRequests);
        handlers.put("prs/search", this::searchPullRequests);
        handlers.put("repos/listStarred", this::listStarredRepositories);
        handlers.put("prs/getDetail", this::getPullRequestDetail);
        handlers.put("prs/getDiff", this::getPullRequestDiff);
        handlers.put("prs/getIncrementalDiff", this::getIncrementalDiff);
        handlers.put("prs/getExistingReviews", this::getExistingReviews);
        handlers.put("prs/getDraftReview", this::getDraftReview);
        handlers.put("prs/saveDraftReview", this::saveDraftReview);
        handlers.put("prs/submitReview", this::submitReview);
        handlers.put("prs/deleteDraftReview", this::deleteDraftReview);
        handlers.put("prs/getCheckStatus", this::getCheckStatus);
        handlers.put("prs/getCommits", this::getCommits);
        handlers.put("prs/getLinkedIssues", this::getLinkedIssues);
        handlers.put("repo/getProfile", this::getRepoProfile);
    }

    private ObjectNode detectRepo(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("path").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request), github.detectRepo(params.path("path").textValue()));
    }

    private ObjectNode checkGitHubAuth(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("githubBaseUrl").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.checkAuth(params.path("githubBaseUrl").textValue()));
    }

    private ObjectNode listPullRequests(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "state", "searchScope", "currentRepo"))
                || !support.hasOnlyTextValues(params)
                || !params.path("githubBaseUrl").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.listPullRequests(
                        new PrListService.PrListParams(
                                params.path("githubBaseUrl").textValue(),
                                support.optionalText(params, "state"),
                                support.optionalText(params, "searchScope"),
                                support.optionalText(params, "currentRepo"))));
    }

    private ObjectNode getPullRequestDetail(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.getPullRequestDetail(
                        new PrDetailService.PrDetailParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue())));
    }

    private ObjectNode getPullRequestDiff(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number", "mode"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isInt()
                || !params.path("mode").isTextual())
            return support.error(support.requestId(request), -32602, "Invalid params");
        return support.result(
                support.requestId(request),
                github.getPullRequestDiff(
                        new PrDiffService.Params(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("mode").textValue())));
    }

    private ObjectNode getIncrementalDiff(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isInt())
            return support.error(support.requestId(request), -32602, "Invalid params");
        return support.result(
                support.requestId(request),
                github.getIncrementalDiff(
                        new IncrementalDiffService.Params(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue())));
    }

    private ObjectNode searchPullRequests(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(params, Set.of("githubBaseUrl", "query", "limit"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("query").isTextual()
                || !params.path("limit").isIntegralNumber()
                || !params.path("limit").canConvertToInt()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.searchPullRequests(
                        new PrSupplementalService.SearchParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("query").textValue(),
                                params.path("limit").intValue())));
    }

    private ObjectNode listStarredRepositories(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("githubBaseUrl").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.listStarredRepositories(params.path("githubBaseUrl").textValue()));
    }

    private ObjectNode getExistingReviews(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.getExistingReviews(
                        new PrSupplementalService.IdentityParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue())));
    }

    private ObjectNode getDraftReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.getDraftReview(
                        params.path("githubBaseUrl").textValue(),
                        params.path("owner").textValue(),
                        params.path("repo").textValue(),
                        params.path("number").intValue()));
    }

    private ObjectNode saveDraftReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params,
                        Set.of(
                                "githubBaseUrl",
                                "owner",
                                "repo",
                                "number",
                                "summary",
                                "verdict",
                                "lineComments",
                                "orphans"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()
                || !params.path("summary").isTextual()
                || !params.path("verdict").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        List<DraftReviewMutationService.CommentInput> lineComments =
                support.parseComments(params.path("lineComments"));
        List<DraftReviewMutationService.CommentInput> orphans =
                params.has("orphans") ? support.parseComments(params.path("orphans")) : List.of();
        if (lineComments == null || orphans == null) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.saveDraftReview(
                        new DraftReviewMutationService.SaveParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("summary").textValue(),
                                params.path("verdict").textValue(),
                                lineComments,
                                orphans)));
    }

    private ObjectNode submitReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params,
                        Set.of(
                                "githubBaseUrl",
                                "owner",
                                "repo",
                                "number",
                                "reviewId",
                                "event",
                                "body"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()
                || !params.path("reviewId").isTextual()
                || !params.path("event").isTextual()
                || !params.path("body").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.submitReview(
                        new DraftReviewMutationService.SubmitParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("reviewId").textValue(),
                                params.path("event").textValue(),
                                params.path("body").textValue())));
    }

    private ObjectNode deleteDraftReview(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number", "reviewId"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()
                || !params.path("reviewId").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.deleteDraftReview(
                        new DraftReviewMutationService.DeleteParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue(),
                                params.path("reviewId").textValue())));
    }

    private ObjectNode getCheckStatus(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "headSha"))
                || !support.hasOnlyTextValues(params)
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("headSha").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.getCheckStatus(
                        new CheckRunService.Params(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("headSha").textValue())));
    }

    private ObjectNode getCommits(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params, Set.of("githubBaseUrl", "owner", "repo", "number"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("number").isIntegralNumber()
                || !params.path("number").canConvertToInt()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.getCommits(
                        new PrSupplementalService.IdentityParams(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("number").intValue())));
    }

    private ObjectNode getLinkedIssues(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || !support.hasOnlyFields(
                        params,
                        Set.of("githubBaseUrl", "owner", "repo", "prBody", "commitIssueNumbers"))
                || !params.path("githubBaseUrl").isTextual()
                || !params.path("owner").isTextual()
                || !params.path("repo").isTextual()
                || !params.path("prBody").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        List<Integer> commitIssueNumbers =
                support.parseCommitIssueNumbers(params.path("commitIssueNumbers"));
        if (commitIssueNumbers == null) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.getLinkedIssues(
                        new LinkedIssueService.Params(
                                params.path("githubBaseUrl").textValue(),
                                params.path("owner").textValue(),
                                params.path("repo").textValue(),
                                params.path("prBody").textValue(),
                                commitIssueNumbers)));
    }

    private ObjectNode getRepoProfile(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null
                || !params.isObject()
                || params.size() != 1
                || !params.path("projectDir").isTextual()) {
            return support.error(support.requestId(request), -32602, "Invalid params");
        }
        return support.result(
                support.requestId(request),
                github.getRepoProfile(params.path("projectDir").textValue()));
    }
}
