package com.jinloes.prpilot.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static com.jinloes.prpilot.ui.WebviewPrSupport.bridgePrKey;
import static com.jinloes.prpilot.ui.WebviewPrSupport.matchesPrRequest;

import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.review.ReviewPrompts;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.services.UserFacingErrors;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.settings.RepositoryReviewInstructions;
import com.jinloes.prpilot.sidecar.pr.IncrementalDiffResult;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.DraftSaveErrorMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.DraftSavedMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ErrorMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.GeneratedReview;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.PrDraftStatusMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ReviewChunkMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ReviewGeneratingMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ReviewGenerationSettings;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ReviewResultMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ReviewScopeDto;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.SimpleMsg;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs provider-backed review generation and draft mutations. */
final class WebviewReviewController {
    private static final Logger log = LoggerFactory.getLogger(WebviewReviewController.class);
    private final WebviewPanel panel;

    WebviewReviewController(WebviewPanel panel) {
        this.panel = panel;
    }

    /** What the model reviews; the published diffs stay the PR diff regardless. */
    record ReviewInput(
            String modelDiff, String incrementalBaselineSha, ReviewScopeDto reviewScope) {}

    interface IncrementalDiffFetcher {
        IncrementalDiffResult fetch() throws Exception;
    }

    /**
     * Picks the model diff for a generation. Only an incremental request asks for the incremental
     * diff; an engine fallback reviews the full PR diff and records why. A failed fetch propagates
     * so the caller reports it like a diff failure.
     */
    static ReviewInput resolveReviewInput(
            boolean incremental, String prDiff, IncrementalDiffFetcher fetcher) throws Exception {
        if (!incremental) return new ReviewInput(prDiff, null, null);
        IncrementalDiffResult scoped = fetcher.fetch();
        if (IncrementalDiffResult.SCOPE_INCREMENTAL.equals(scoped.scope())) {
            return new ReviewInput(
                    scoped.diff(),
                    scoped.baselineSha(),
                    ReviewScopeDto.incremental(scoped.baselineSha()));
        }
        return new ReviewInput(prDiff, null, ReviewScopeDto.full(scoped.fallbackReason()));
    }

    void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            boolean chunkedReview,
            String focus,
            String custom,
            String operationId) {
        handleGenerateReview(
                number, owner, repo, overrideDiff, chunkedReview, focus, custom, operationId, null);
    }

    void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            boolean chunkedReview,
            String overrideFocusAreas,
            String overrideCustomInstructions,
            String operationId,
            DeepReviewController.DeepInvocation deep) {
        handleGenerateReview(
                number,
                owner,
                repo,
                overrideDiff,
                chunkedReview,
                overrideFocusAreas,
                overrideCustomInstructions,
                operationId,
                deep,
                false);
    }

    void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            boolean chunkedReview,
            String overrideFocusAreas,
            String overrideCustomInstructions,
            String operationId,
            DeepReviewController.DeepInvocation deep,
            boolean incremental) {
        String key = bridgePrKey(number, owner, repo);
        final PullRequest pr;
        final long reviewRevision;
        synchronized (panel) {
            pr = panel.activePR;
            reviewRevision = panel.selectionRevision;
        }
        if (!matchesPrRequest(pr, number, owner, repo)) {
            panel.pushMessage(new ErrorMsg("reviewError", key, "PR not found."));
            return;
        }

        PluginSettings settings = PluginSettings.getInstance();
        ReviewGenerationSettings generationSettings =
                new ReviewGenerationSettings(
                        IntellijClaudeService.snapshotReviewRuntimeSettings(),
                        settings.getResolvedReviewFocusAreas(),
                        settings.getResolvedReviewCustomInstructions(),
                        List.copyOf(settings.getResolvedReviewGuidanceGlobs()),
                        settings.getReviewRulesDirectory(),
                        settings.getRepositoryReviewInstructions(owner, repo));
        long generationId;
        IntellijClaudeService previousReviewService;
        ReviewProvider previousReviewProvider;
        synchronized (panel) {
            if (!panel.isCurrentSelectionLocked(key, reviewRevision)) {
                return;
            }
            generationId = panel.generationSequence.incrementAndGet();
            previousReviewService = panel.activeReviewService;
            previousReviewProvider = panel.activeReviewProvider;
            panel.activeGenerationId = generationId;
            panel.activeReviewService = panel.claudeService;
            panel.activeReviewProvider = generationSettings.runtime().provider();
            panel.activeReviewOperationId = operationId;
        }
        previousReviewService.cancelCurrentRequest(previousReviewProvider);

        // Provider preflight: fail fast with actionable guidance instead of a raw CLI spawn error
        // when the configured review provider's binary isn't installed/resolvable.
        ReviewProvider provider = generationSettings.runtime().provider();
        if (!panel.isProviderBinaryAvailable(provider)) {
            panel.pushMessage(
                    new ErrorMsg(
                            "reviewError",
                            key,
                            UserFacingErrors.forProviderNotInstalled(provider)));
            synchronized (panel) {
                if (panel.isCurrentGenerationLocked(key, reviewRevision, generationId)) {
                    panel.activeReviewService = panel.claudeService;
                    panel.activeReviewProvider = ReviewProvider.CLAUDE;
                    panel.activeReviewOperationId = null;
                }
            }
            return;
        }

        // Dispatch all blocking work to a pooled thread so the JCEF bridge returns immediately
        // and status messages can flow during the network-fetch phase.
        getApplication()
                .executeOnPooledThread(
                        () -> {
                            if (!panel.isCurrentGeneration(key, reviewRevision, generationId)) {
                                return;
                            }
                            // Atomically snapshot prefetched data to prevent check-then-act
                            // races with a concurrent handleSelectPR on the JCEF bridge thread.
                            String snapshotDiff;
                            String snapshotValidationDiff;
                            String snapshotReviews;
                            PullRequest promptPr;
                            synchronized (panel) {
                                if (!panel.isCurrentGenerationLocked(
                                        key, reviewRevision, generationId)) {
                                    return;
                                }
                                promptPr = panel.activePR;
                                snapshotDiff = panel.prefetchedDiff;
                                snapshotValidationDiff = panel.prefetchedValidationDiff;
                                snapshotReviews = panel.prefetchedExistingReviews;
                            }

                            // Reuse prefetched diff; fall back to live fetch only if stale.
                            String diff;
                            if (StringUtils.isNotBlank(overrideDiff)) {
                                diff = overrideDiff;
                            } else if (StringUtils.isNotBlank(snapshotDiff)) {
                                diff = snapshotDiff;
                            } else {
                                panel.publishIfCurrentGeneration(
                                        key,
                                        reviewRevision,
                                        generationId,
                                        new ReviewGeneratingMsg(
                                                "reviewGenerating", key, "Fetching diff…"));
                                try {
                                    diff = panel.ghSvc.getPRDiff(owner, repo, number);
                                } catch (Exception e) {
                                    panel.publishIfCurrentGeneration(
                                            key,
                                            reviewRevision,
                                            generationId,
                                            new ErrorMsg(
                                                    "reviewError",
                                                    key,
                                                    UserFacingErrors.forGitHub(
                                                            e, "load the PR diff")));
                                    return;
                                }
                            }

                            String validationDiff;
                            if (StringUtils.isNotBlank(snapshotValidationDiff)) {
                                validationDiff = snapshotValidationDiff;
                            } else {
                                try {
                                    validationDiff = panel.ghSvc.getPRDiffFull(owner, repo, number);
                                } catch (Exception e) {
                                    log.warn(
                                            "getPRDiffFull failed; falling back to truncated diff: {}",
                                            e.getMessage());
                                    validationDiff = diff;
                                }
                            }

                            // The incremental diff only feeds the model: the published diff and
                            // validationDiff stay PR diffs so anchors validate against the whole
                            // PR.
                            ReviewInput reviewInput;
                            try {
                                reviewInput =
                                        resolveReviewInput(
                                                incremental && deep == null,
                                                diff,
                                                () ->
                                                        panel.ghSvc.getIncrementalDiff(
                                                                owner, repo, number));
                            } catch (Exception e) {
                                panel.publishIfCurrentGeneration(
                                        key,
                                        reviewRevision,
                                        generationId,
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                UserFacingErrors.forGitHub(
                                                        e,
                                                        "load the changes since your last review")));
                                return;
                            }

                            // Reuse prefetched existing reviews; fall back to live fetch only if
                            // stale.
                            String existingReviews;
                            if (snapshotReviews != null) {
                                existingReviews = snapshotReviews;
                            } else {
                                try {
                                    existingReviews =
                                            panel.ghSvc.getExistingReviewsSummary(
                                                    owner, repo, number);
                                } catch (Exception e) {
                                    log.warn(
                                            "getExistingReviewsSummary failed: {}", e.getMessage());
                                    existingReviews = "";
                                }
                            }

                            // Prompt context. Each of these is additive: a failure degrades the
                            // prompt by one section and must never fail the review, so unlike the
                            // diff none of them abort the flow.
                            String ciStatus = "";
                            List<CiAnnotation> ciAnnotations = List.of();
                            String baseSha = "";
                            try {
                                IntellijGitHubService.PRRevisions revisions =
                                        panel.ghSvc.getPRRevisions(owner, repo, number);
                                baseSha = revisions.baseSha();
                                String headSha = revisions.headSha();
                                if (StringUtils.isNotBlank(headSha)) {
                                    IntellijGitHubService.CheckContext checks =
                                            panel.ghSvc.getCheckContext(owner, repo, headSha);
                                    ciStatus = checks.summary();
                                    ciAnnotations = checks.annotations();
                                }
                            } catch (Exception e) {
                                log.warn("getCheckContext failed: {}", e.getMessage());
                            }
                            IntellijGitHubService.CommitContext commitContext =
                                    new IntellijGitHubService.CommitContext("", List.of());
                            try {
                                commitContext = panel.ghSvc.getCommitContext(owner, repo, number);
                            } catch (Exception e) {
                                log.warn("getCommitContext failed: {}", e.getMessage());
                            }
                            String linkedIssue = "";
                            try {
                                linkedIssue =
                                        panel.ghSvc.getLinkedIssueSummary(
                                                owner,
                                                repo,
                                                promptPr.getBody(),
                                                commitContext.closingIssueNumbers());
                            } catch (Exception e) {
                                log.warn("getLinkedIssueSummary failed: {}", e.getMessage());
                            }

                            panel.publishIfCurrentGeneration(
                                    key,
                                    reviewRevision,
                                    generationId,
                                    new ReviewGeneratingMsg(
                                            "reviewGenerating", key, "Preparing PR branch…"));
                            IntellijClaudeService reviewService;
                            try {
                                reviewService =
                                        deep == null
                                                ? panel.resolvePrClaudeService(promptPr)
                                                : new IntellijClaudeService(
                                                        deep.pending().preparation().worktree());
                            } catch (Exception e) {
                                log.warn(
                                        "Worktree resolution for PR #{} failed: {}",
                                        number,
                                        e.getMessage());
                                synchronized (panel) {
                                    if (panel.isCurrentGenerationLocked(
                                            key, reviewRevision, generationId)) {
                                        panel.activeReviewService = panel.claudeService;
                                        panel.activeReviewProvider = ReviewProvider.CLAUDE;
                                        panel.activeReviewOperationId = null;
                                    }
                                }
                                panel.publishIfCurrentGeneration(
                                        key,
                                        reviewRevision,
                                        generationId,
                                        new ErrorMsg(
                                                "reviewError",
                                                key,
                                                "Unable to create an isolated pull request worktree."
                                                        + " Open the PR repository and try again."));
                                return;
                            }

                            final IntellijClaudeService finalReviewService = reviewService;

                            // Kick off the review — callbacks fired on EDT
                            final String finalDiff = diff;
                            final String finalModelDiff = reviewInput.modelDiff();
                            final String finalIncrementalBaselineSha =
                                    reviewInput.incrementalBaselineSha();
                            final ReviewScopeDto finalReviewScope = reviewInput.reviewScope();
                            final String finalValidationDiff = validationDiff;
                            final String finalExisting = existingReviews;
                            java.io.File guidelinesDir;
                            ReviewResult priorResult;
                            synchronized (panel) {
                                if (!panel.isCurrentGenerationLocked(
                                        key, reviewRevision, generationId)) {
                                    return;
                                }
                                panel.activeReviewService = finalReviewService;
                                panel.activeReviewProvider =
                                        generationSettings.runtime().provider();
                                WebviewBridgeMessages.PrWorktree activeWorktree =
                                        panel.worktreeManager.activeValue();
                                guidelinesDir =
                                        deep != null
                                                ? new java.io.File(
                                                        deep.pending().preparation().worktree())
                                                : activeWorktree != null
                                                        ? activeWorktree.directory()
                                                        : (panel.project.getBasePath() != null
                                                                ? new java.io.File(
                                                                        panel.project.getBasePath())
                                                                : null);
                                priorResult = panel.lastResult;
                            }
                            panel.publishIfCurrentGeneration(
                                    key,
                                    reviewRevision,
                                    generationId,
                                    new ReviewGeneratingMsg(
                                            "reviewGenerating", key, "Sending review request…"));
                            // Guidance in the PR worktree is authored by the change under review,
                            // so the host never reads it. The engine resolves guidance and file
                            // history from the trusted base commit identified by baseSha.
                            final String finalGuidelines = "";
                            final String finalBaseSha = baseSha;
                            final String finalPriorReview =
                                    WebviewPanel.formatPriorReview(priorResult);
                            final String finalFocusAreas =
                                    StringUtils.isNotBlank(overrideFocusAreas)
                                            ? overrideFocusAreas
                                            : generationSettings.focusAreas();
                            final String finalCustomInstructions =
                                    RepositoryReviewInstructions.compose(
                                            owner + "/" + repo,
                                            generationSettings.repositoryInstructions(),
                                            StringUtils.isNotBlank(overrideCustomInstructions)
                                                    ? overrideCustomInstructions
                                                    : generationSettings.customInstructions());
                            final String finalCiStatus = ciStatus;
                            final List<CiAnnotation> finalCiAnnotations = ciAnnotations;
                            final String finalCommits = commitContext.summary();
                            final String finalLinkedIssue = linkedIssue;
                            final String finalRepoProfile =
                                    guidelinesDir == null
                                            ? ""
                                            : panel.ghSvc.getRepoProfileSummary(
                                                    guidelinesDir.getAbsolutePath());
                            finalReviewService.reviewPR(
                                    PRReviewRequest.builder(promptPr, finalModelDiff)
                                            .priorReview(finalPriorReview)
                                            .existingReviews(finalExisting)
                                            .repoGuidelines(finalGuidelines)
                                            .focusAreas(finalFocusAreas)
                                            .customInstructions(finalCustomInstructions)
                                            .ciStatus(finalCiStatus)
                                            .commits(finalCommits)
                                            .linkedIssue(finalLinkedIssue)
                                            .repoProfile(finalRepoProfile)
                                            .ciAnnotations(finalCiAnnotations)
                                            .baseSha(finalBaseSha)
                                            .guidanceGlobs(generationSettings.guidanceGlobs())
                                            .rulesDirectory(generationSettings.rulesDirectory())
                                            .incrementalBaselineSha(finalIncrementalBaselineSha)
                                            .build(),
                                    generationSettings.runtime(),
                                    chunkedReview && finalIncrementalBaselineSha == null,
                                    statusMsg ->
                                            panel.publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ReviewGeneratingMsg(
                                                            "reviewGenerating", key, statusMsg)),
                                    (kind, chunk) ->
                                            panel.publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ReviewChunkMsg(
                                                            "reviewChunk", key, kind, chunk)),
                                    result -> {
                                        if (result == null) {
                                            synchronized (panel) {
                                                if (panel.isCurrentGenerationLocked(
                                                        key, reviewRevision, generationId)) {
                                                    panel.activeReviewOperationId = null;
                                                    panel.activeReviewProvider =
                                                            ReviewProvider.CLAUDE;
                                                    panel.activeReviewService = panel.claudeService;
                                                }
                                            }
                                            panel.publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ErrorMsg(
                                                            "reviewError",
                                                            key,
                                                            UserFacingErrors.forProvider(
                                                                    provider,
                                                                    new Exception(
                                                                            "Provider produced no output"),
                                                                    "generate review")));
                                            return;
                                        }
                                        synchronized (panel) {
                                            if (!panel.isCurrentGenerationLocked(
                                                    key, reviewRevision, generationId)) {
                                                return;
                                            }
                                            panel.activeReviewService = panel.claudeService;
                                            panel.activeReviewOperationId = null;
                                            panel.activeReviewProvider = ReviewProvider.CLAUDE;
                                            panel.lastResult = result;
                                            panel.generatedReviews.put(
                                                    key,
                                                    new GeneratedReview(
                                                            generationId,
                                                            result,
                                                            generationMetadata(
                                                                    provider,
                                                                    generationSettings
                                                                            .runtime()
                                                                            .model(),
                                                                    generationSettings
                                                                            .runtime()
                                                                            .supervisorEnabled())));
                                            panel.pendingReviewId = null;
                                        }
                                        panel.publishIfCurrentGeneration(
                                                key,
                                                reviewRevision,
                                                generationId,
                                                new ReviewResultMsg(
                                                        "reviewResult",
                                                        key,
                                                        ReviewMapper.INSTANCE.toDto(result),
                                                        finalDiff,
                                                        finalValidationDiff,
                                                        finalReviewScope));
                                    },
                                    err -> {
                                        synchronized (panel) {
                                            if (!panel.isCurrentGenerationLocked(
                                                    key, reviewRevision, generationId)) {
                                                return;
                                            }
                                            panel.activeReviewService = panel.claudeService;
                                            panel.activeReviewOperationId = null;
                                            panel.activeReviewProvider = ReviewProvider.CLAUDE;
                                        }
                                        // Cancellations are user-initiated — don't surface as
                                        // errors.
                                        String lower = err.toLowerCase(java.util.Locale.ROOT);
                                        if (!lower.contains("cancel")
                                                && !lower.contains("interrupt")) {
                                            if (deep != null) {
                                                synchronized (panel) {
                                                    if (panel.isCurrentGenerationLocked(
                                                            key, reviewRevision, generationId)) {
                                                        panel.assistedReviews
                                                                .restoreAfterGenerationFailureLocked(
                                                                        deep.pending(),
                                                                        operationId,
                                                                        err);
                                                    }
                                                }
                                                return;
                                            }
                                            panel.publishIfCurrentGeneration(
                                                    key,
                                                    reviewRevision,
                                                    generationId,
                                                    new ErrorMsg("reviewError", key, err));
                                        }
                                    },
                                    deep == null
                                            ? null
                                            : new IntellijClaudeService.DeepReviewOperation(
                                                    panel.semanticReviews,
                                                    deep.pending().preparation().retainedId(),
                                                    deep.server(),
                                                    owner + "/" + repo + "#" + number,
                                                    operationId,
                                                    () ->
                                                            panel.assistedReviews.validateHead(
                                                                    deep.pending())));
                        });
    }

    void handleSaveDraft(
            int number,
            String owner,
            String repo,
            long saveId,
            ReviewResult bridgeResult,
            ReviewResult bridgeGeneratedResult,
            List<LineComment> orphans) {
        String key = bridgePrKey(number, owner, repo);
        boolean activeAtStart = panel.isActivePrKey(key);
        if (!canPersistDraft(activeAtStart, bridgeResult != null)) {
            panel.pushMessage(
                    new DraftSaveErrorMsg(
                            "draftSaveError",
                            key,
                            saveId,
                            "The selected pull request changed before the draft could be saved."));
            return;
        }
        long revision = panel.selectionRevision;
        ReviewResult result = bridgeResult != null ? bridgeResult : panel.lastResult;
        if (result == null) {
            panel.pushMessage(
                    new DraftSaveErrorMsg(
                            "draftSaveError", key, saveId, "No review result to save."));
            return;
        }

        IntellijGitHubService.SaveDraftResult saved;
        try {
            panel.draftRecoveryStore.save(key, result, orphans);
            saved = panel.ghSvc.saveDraftReview(owner, repo, number, result, orphans);
            panel.draftRecoveryStore.clear(key);
        } catch (Exception e) {
            panel.pushMessage(
                    new DraftSaveErrorMsg(
                            "draftSaveError",
                            key,
                            saveId,
                            UserFacingErrors.forGitHub(e, "save the draft review")));
            return;
        }

        String headSha = "";
        try {
            headSha = panel.ghSvc.getPRHeadSha(owner, repo, number);
        } catch (Exception e) {
            log.warn("getPRHeadSha failed during saveDraft: {}", e.getMessage());
        }

        PullRequest pr =
                panel.cachedPRs.stream()
                        .filter(
                                p ->
                                        p.getNumber() == number
                                                && p.getOwner().equals(owner)
                                                && p.getRepo().equals(repo))
                        .findFirst()
                        .orElse(null);
        String title = pr != null ? pr.getTitle() : "";
        PendingReviewIndex.MutationResult indexResult =
                panel.pendingIndex.add(owner, repo, number, title, headSha);
        panel.reportPendingIndexMutation("saving draft", indexResult);
        GeneratedReview generated = panel.generatedReviews.get(key);
        if (bridgeGeneratedResult != null && generated != null) {
            panel.generatedReviews.put(
                    key,
                    new GeneratedReview(
                            generated.generationId(), bridgeGeneratedResult, generated.metadata()));
        }
        if (!panel.isActivePrKey(key) || panel.selectionRevision != revision) {
            return;
        }
        panel.pendingReviewId = saved.reviewId();
        panel.pendingReviewKey = key;
        panel.lastResult = result;

        panel.pushMessage(
                new DraftSavedMsg(
                        "draftSaved", key, saveId, saved.reviewId(), saved.commentsDropped()));
        panel.pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, true));
    }

    static boolean canPersistDraft(boolean activePr, boolean hasExplicitResult) {
        return activePr || hasExplicitResult;
    }

    // --- submitReview ---

    void handleSubmitReview(int number, String owner, String repo, String verdict, String comment) {
        String key = bridgePrKey(number, owner, repo);
        String reviewId = panel.pendingReviewId;
        if (StringUtils.isBlank(reviewId)
                || !panel.isActivePrKey(key)
                || !StringUtils.equals(panel.pendingReviewKey, key)) {
            panel.pushMessage(
                    new ErrorMsg(
                            "reviewSubmitError",
                            key,
                            "No pending draft review belongs to the selected pull request."));
            return;
        }

        try {
            panel.ghSvc.submitDraftReview(owner, repo, number, reviewId, verdict, comment);
            panel.draftRecoveryStore.clear(key);
        } catch (Exception e) {
            panel.pushMessage(
                    new ErrorMsg(
                            "reviewSubmitError",
                            key,
                            UserFacingErrors.forGitHub(e, "submit the draft review")));
            return;
        }

        PendingReviewIndex.MutationResult indexResult =
                panel.pendingIndex.remove(owner, repo, number);
        panel.reportPendingIndexMutation("submitting draft", indexResult);
        GeneratedReview generated = panel.generatedReviews.remove(key);
        if (generated != null) {
            recordReviewOutcome(generated.result(), panel.lastResult, generated.metadata());
        }
        if (StringUtils.equals(panel.pendingReviewId, reviewId)
                && StringUtils.equals(panel.pendingReviewKey, key)) {
            panel.lastResult = null;
            panel.pendingReviewId = null;
            panel.pendingReviewKey = null;
        }

        panel.pushMessage(new SimpleMsg("reviewSubmitted", key));
        panel.pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    static ReviewOutcomeLog.Metadata generationMetadata(
            ReviewProvider provider, String model, boolean supervisorEnabled) {
        return new ReviewOutcomeLog.Metadata(
                ReviewPrompts.reviewPipelineVersion(supervisorEnabled),
                provider.name().toLowerCase(java.util.Locale.ROOT),
                model);
    }

    /**
     * Logs what the reviewer did with each generated comment. Runs off the EDT and swallows
     * everything: the review has already been submitted, so instrumentation must not report an
     * error or block the UI. A no-op when the generated review is unavailable (a draft loaded from
     * GitHub in a later session was never generated locally, so there is nothing to compare).
     */
    private void recordReviewOutcome(
            ReviewResult generated, ReviewResult submitted, ReviewOutcomeLog.Metadata metadata) {
        if (generated == null) return;
        List<LineComment> generatedComments = generated.getLineComments();
        List<LineComment> submittedComments =
                submitted == null ? List.of() : submitted.getLineComments();
        getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                panel.outcomeLog.record(
                                        generatedComments, submittedComments, metadata);
                            } catch (Exception e) {
                                log.warn("Review outcome logging failed: {}", e.getMessage());
                            }
                        });
    }

    // --- deleteDraft ---

    void handleDeleteDraft(int number, String owner, String repo) {
        String key = bridgePrKey(number, owner, repo);
        String reviewId = panel.pendingReviewId;
        if (StringUtils.isBlank(reviewId)
                || !panel.isActivePrKey(key)
                || !StringUtils.equals(panel.pendingReviewKey, key)) {
            panel.pushMessage(
                    new ErrorMsg(
                            "draftDeleteError",
                            key,
                            "No pending draft review belongs to the selected pull request."));
            return;
        }

        try {
            panel.ghSvc.deleteDraftReview(owner, repo, number, reviewId);
            panel.draftRecoveryStore.clear(key);
        } catch (Exception e) {
            panel.pushMessage(
                    new ErrorMsg(
                            "draftDeleteError",
                            key,
                            UserFacingErrors.forGitHub(e, "delete the draft review")));
            return;
        }

        PendingReviewIndex.MutationResult indexResult =
                panel.pendingIndex.remove(owner, repo, number);
        panel.reportPendingIndexMutation("deleting draft", indexResult);
        if (StringUtils.equals(panel.pendingReviewId, reviewId)
                && StringUtils.equals(panel.pendingReviewKey, key)) {
            panel.lastResult = null;
            panel.generatedReviews.remove(key);
            panel.pendingReviewId = null;
            panel.pendingReviewKey = null;
        }

        panel.pushMessage(new SimpleMsg("draftDeleted", key));
        panel.pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    /** Remembered instructions for the PR's repository, or null so the bridge field is omitted. */
}
