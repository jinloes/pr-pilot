package com.jinloes.prpilot.ui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.services.IntellijClaudeService;
import java.util.List;

/** Outbound bridge records (Java → JS) and small value types shared by the webview panel. */
final class WebviewBridgeMessages {

    private WebviewBridgeMessages() {}

    // ReviewResultDto and LineCommentDto live in WebviewDtos.java (package-private);
    // see ReviewMapper for compile-time-verified model→DTO mapping.

    record WebviewPr(
            int number,
            String title,
            String owner,
            String repo,
            String author,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("htmlUrl") String htmlUrl,
            @JsonProperty("isDraft") boolean isDraft,
            @JsonProperty("hasReviewDraft") boolean hasReviewDraft,
            @JsonProperty("reviewStatus") ReviewStatus reviewStatus) {}

    record PrListStatus(
            String searchScope,
            String currentRepo,
            int resultLimit,
            boolean limited,
            boolean reviewStatusAvailable) {}

    record PrListMessage(
            String type,
            List<WebviewPr> prs,
            @JsonProperty("defaultRepo") String defaultRepo,
            @JsonProperty("listStatus") PrListStatus listStatus,
            @JsonProperty("providerReadiness") ProviderReadinessDto providerReadiness,
            @JsonProperty("intellijAssistedEnabled") boolean intellijAssistedEnabled) {}

    record DraftLoadingMsg(String type, @JsonProperty("prKey") String prKey) {}

    record ThemeChangedMsg(String type, String theme) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DraftLoadedMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            String prState,
            @JsonProperty("reviewId") String reviewId,
            @JsonProperty("result") ReviewResultDto result,
            String diff,
            @JsonProperty("validationDiff") String validationDiff,
            boolean staleCommits,
            boolean importedFromGitHub,
            boolean recoveryPending,
            String status,
            @JsonProperty("providerReadiness") ProviderReadinessDto providerReadiness,
            @JsonProperty("intellijAssistedEnabled") boolean intellijAssistedEnabled,
            @JsonProperty("repositoryInstructions") String repositoryInstructions) {}

    record RepositoryInstructionsSavedMsg(
            String type, @JsonProperty("prKey") String prKey, String instructions) {}

    record ReviewGeneratingMsg(String type, @JsonProperty("prKey") String prKey, String message) {}

    record ReviewChunkMsg(
            String type, @JsonProperty("prKey") String prKey, String kind, String chunk) {}

    record ReviewResultMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            ReviewResultDto result,
            String diff,
            @JsonProperty("validationDiff") String validationDiff) {}

    record ErrorMsg(String type, @JsonProperty("prKey") String prKey, String message) {}

    record DraftSavedMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            long saveId,
            String reviewId,
            boolean commentsDropped) {}

    record DraftSaveErrorMsg(
            String type, @JsonProperty("prKey") String prKey, long saveId, String message) {}

    record SimpleMsg(String type, @JsonProperty("prKey") String prKey) {}

    record ChatChunkMsg(String type, @JsonProperty("prKey") String prKey, String chunk) {}

    record ChatResponseMsg(String type, @JsonProperty("prKey") String prKey, String response) {}

    record PrDraftStatusMsg(
            String type,
            int number,
            String owner,
            String repo,
            @JsonProperty("hasReviewDraft") boolean hasReviewDraft) {}

    record ProviderReadinessDto(
            String provider,
            boolean available,
            String detail,
            @JsonProperty("binaryStatus") String binaryStatus,
            @JsonProperty("authenticationStatus") String authenticationStatus,
            @JsonProperty("authCommand") String authCommand) {
        ProviderReadinessDto(String provider, boolean available, String detail) {
            this(
                    provider,
                    available,
                    detail,
                    available ? "ready" : "missing",
                    available ? "unverified" : "unavailable",
                    "copilot".equals(provider) ? "copilot login" : "claude auth login");
        }
    }

    record ActivatePrMsg(String type, @JsonProperty("pr") WebviewPr pr, String source) {}

    record SetupRequiredMsg(
            String type,
            String reason,
            String detail,
            @JsonProperty("providerReadiness") ProviderReadinessDto providerReadiness) {}

    record ReviewGenerationSettings(
            IntellijClaudeService.ReviewRuntimeSettings runtime,
            String focusAreas,
            String customInstructions,
            List<String> guidanceGlobs,
            String rulesDirectory,
            String repositoryInstructions) {}

    record GeneratedReview(
            long generationId, ReviewResult result, ReviewOutcomeLog.Metadata metadata) {}

    record PrWorktree(java.io.File directory, java.io.File gitRoot) {}
}
