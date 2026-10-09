package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared provider-neutral review pipeline used by every host. Optional supervision is bounded to
 * one tool-free prioritization call and one targeted read-only follow-up. An optional second
 * reviewer runs the primary pass concurrently on another Copilot model; its findings are merged and
 * cross-validated by the final critique.
 */
public final class ReviewPipelineService {
    private static final Logger log = LoggerFactory.getLogger(ReviewPipelineService.class);
    private static final long SUPERVISOR_TIMEOUT_MS = 90_000;
    private static final long FOLLOW_UP_TIMEOUT_MS = 6L * 60L * 1000L;
    // Whole uncovered files per follow-up call, and the most such calls one review makes.
    static final int FILES_PER_FOLLOW_UP = 6;
    static final int MAX_FILE_FOLLOW_UPS = 5;
    private static final long CRITIQUE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long HYGIENE_TIMEOUT_MS = 15L * 60L * 1000L;
    private static final int DROPPED_BODY_MAX_CHARS = 120;
    static final String REPORT_DROPPED_PROPERTY = "prpilot.review.reportDropped";
    static final String STATUS_HYGIENE_FAILED = "Hygiene pass failed; continuing without it";
    private static final long SECONDARY_POLL_MS = 250;
    private static final int MERGED_CANDIDATE_CAP = 40;
    // A validated finding this close to a hygiene finding, in the same category, already covers it.
    static final int HYGIENE_COVER_LINES = 2;
    static final String STATUS_COVERAGE_COMPLETE =
            "Coverage check: every changed file was reviewed; no follow-up findings needed";
    static final String STATUS_BASE_CONTEXT = "Reading base-commit guidance…";

    private final ProviderExecutor provider;
    private final ChunkedReviewService chunkedReviewService;
    private final ReviewCoverageAnalyzer coverageAnalyzer;
    private final SemanticReviewService.Execution execution;
    private final BaseContextResolver baseContextResolver;
    private final ProviderExecutor secondary;
    private final Runnable cancelSecondary;
    private final String secondaryModel;

    private ReviewPipelineService(ProviderExecutor provider) {
        this(provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());
    }

    ReviewPipelineService(
            ProviderExecutor provider,
            ChunkedReviewService chunkedReviewService,
            ReviewCoverageAnalyzer coverageAnalyzer) {
        this(provider, chunkedReviewService, coverageAnalyzer, defaultResolver());
    }

    ReviewPipelineService(
            ProviderExecutor provider,
            ChunkedReviewService chunkedReviewService,
            ReviewCoverageAnalyzer coverageAnalyzer,
            BaseContextResolver baseContextResolver) {
        this(
                provider,
                chunkedReviewService,
                coverageAnalyzer,
                null,
                baseContextResolver,
                null,
                null,
                "");
    }

    private ReviewPipelineService(
            ProviderExecutor provider,
            ChunkedReviewService chunkedReviewService,
            ReviewCoverageAnalyzer coverageAnalyzer,
            SemanticReviewService.Execution execution,
            BaseContextResolver baseContextResolver,
            ProviderExecutor secondary,
            Runnable cancelSecondary,
            String secondaryModel) {
        this.provider = provider;
        this.chunkedReviewService = chunkedReviewService;
        this.coverageAnalyzer = coverageAnalyzer;
        this.execution = execution;
        this.baseContextResolver = baseContextResolver;
        this.secondary = secondary;
        this.cancelSecondary = cancelSecondary;
        this.secondaryModel = StringUtils.defaultString(secondaryModel);
    }

    private static BaseContextResolver defaultResolver() {
        BaseCommitContext context = new BaseCommitContext();
        return context::resolve;
    }

    /**
     * Returns a pipeline that also runs the primary pass on a second Copilot model, in parallel,
     * against the same working directory. Returns this pipeline unchanged when {@code model} is
     * blank or the primary provider has no working directory to share.
     */
    public ReviewPipelineService withSecondReviewer(String model, String effort, String configDir) {
        if (StringUtils.isBlank(model)) return this;
        File projectDir = provider.projectDir();
        if (projectDir == null) {
            log.warn("Second reviewer skipped: the primary provider has no working directory");
            return this;
        }
        CopilotService service = new CopilotService(projectDir.getPath());
        return withSecondReviewer(
                new CopilotExecutor(service, model.trim(), effort, false, configDir),
                service::cancelCurrentRequest,
                model.trim());
    }

    ReviewPipelineService withSecondReviewer(
            ProviderExecutor reviewer, Runnable cancel, String model) {
        return new ReviewPipelineService(
                provider,
                chunkedReviewService,
                coverageAnalyzer,
                execution,
                baseContextResolver,
                reviewer,
                cancel,
                model);
    }

    public static ReviewPipelineService forClaude(ClaudeService service, String model) {
        return new ReviewPipelineService(new ClaudeExecutor(service, model));
    }

    public static ReviewPipelineService forCopilot(
            CopilotService service,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir) {
        return new ReviewPipelineService(
                new CopilotExecutor(service, model, effort, inheritMcp, configDir));
    }

    public ReviewResult review(
            PRReviewRequest request,
            boolean chunked,
            boolean selfCritique,
            boolean supervisorEnabled,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            SemanticReviewService.Execution execution)
            throws IOException, InterruptedException {
        if (execution == null)
            return review(request, chunked, selfCritique, supervisorEnabled, onStatus, onChunk);
        ProviderExecutor selected = provider;
        if (provider instanceof CopilotExecutor copilot) {
            selected =
                    new CopilotExecutor(
                            copilot.service(),
                            copilot.model(),
                            copilot.effort(),
                            false,
                            copilot.configDir());
        }
        // Deep reviews stay single-reviewer: the secondary cannot share semantic authority.
        return new ReviewPipelineService(
                        selected,
                        chunkedReviewService,
                        coverageAnalyzer,
                        execution,
                        baseContextResolver,
                        null,
                        null,
                        "")
                .review(
                        request.withSemanticContext(execution.context()),
                        chunked,
                        selfCritique,
                        supervisorEnabled,
                        onStatus,
                        onChunk);
    }

    private void validateAuthority() throws IOException, InterruptedException {
        provider.checkCancelled();
        if (execution != null) execution.validate();
    }

    private ReviewPassResult primary(
            PRReviewRequest request, Consumer<String> onStatus, BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        validateAuthority();
        return provider.primary(request, onStatus, onChunk);
    }

    public ReviewResult review(
            PRReviewRequest request,
            boolean chunked,
            boolean selfCritique,
            boolean supervisorEnabled,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        if (request.getSemanticContext() != null && execution == null)
            throw new IOException("Semantic prompt data cannot authorize deep review");
        provider.checkCancelled();
        InspectionManifest manifest = InspectionManifest.fromDiff(request.getDiff());
        request = withBaseCommitContext(request, manifest, onStatus);
        request = withLocalRules(request);
        boolean recall = selfCritique || secondary != null;
        PRReviewRequest reviewRequest = request.withCandidateRecall(recall);
        Passes passes = reviewPasses(reviewRequest, chunked, onStatus, onChunk);
        ReviewPassResult primary = passes.result();
        provider.checkCancelled();

        if (supervisorEnabled) {
            primary =
                    new ReviewPassResult(
                            ReviewAnchorValidator.validate(primary.review(), manifest),
                            primary.ledger());
        }
        ReviewResult candidate = primary.review();
        if (supervisorEnabled) {
            candidate = supervise(reviewRequest, manifest, primary, onStatus);
        }
        provider.checkCancelled();
        List<ReviewerAttribution.Evidence> attributed =
                ReviewerAttribution.Evidence.snapshot(candidate.getLineComments());

        if (recall) {
            ReviewResult hygiene = hygieneFindings(request, chunked, manifest, onStatus);
            candidate = ReviewResultMerger.merge(candidate, hygiene, MERGED_CANDIDATE_CAP);
            provider.checkCancelled();
            ReviewResult draft = candidate;
            int draftCount = candidate.getLineComments().size();
            onStatus.accept(draftStatus(draftCount));
            onStatus.accept(ClaudeService.STATUS_REFINING);
            validateAuthority();
            try {
                PRReviewRequest critiqueRequest =
                        chunkedReviewService.finalValidationRequest(request);
                String raw =
                        provider.complete(
                                ReviewPrompts.buildCritiquePrompt(critiqueRequest, candidate),
                                CRITIQUE_TIMEOUT_MS,
                                true,
                                true,
                                onStatus);
                candidate = ReviewResultParser.parseReview(raw);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException | IllegalArgumentException exception) {
                log.warn(
                        "Final self-critique failed; keeping the best pre-critique review",
                        exception);
            }
            // Unconfirmed recall candidates never reach the user, even if validation failed.
            candidate = ReviewResultMerger.withoutLowConfidence(candidate);
            int validatedCount = candidate.getLineComments().size();
            candidate = restoreHygieneFindings(candidate, hygiene, manifest);
            int restored = candidate.getLineComments().size() - validatedCount;
            if (restored > 0) {
                onStatus.accept("Kept " + findings(restored) + " from the hygiene pass");
            }
            if (Boolean.getBoolean(REPORT_DROPPED_PROPERTY)) {
                droppedStatuses(draft, candidate).forEach(onStatus);
            }
            onStatus.accept(validatedStatus(candidate.getLineComments().size(), draftCount));
        }
        if (passes.labels() != null) {
            candidate =
                    ReviewerAttribution.reattach(candidate, attributed, passes.labels().primary());
        }
        provider.checkCancelled();
        if (supervisorEnabled) {
            candidate = ReviewAnchorValidator.validate(candidate, manifest);
        }
        candidate = ReviewResultMerger.capFinal(candidate);
        validateAuthority();
        return CiFindingSuppressor.suppress(candidate, request.getCiAnnotations());
    }

    /**
     * Runs the recall-mode hygiene pass and returns its anchored findings, or an empty result when
     * it fails. The hygiene rules are left out of the recall first-pass prompt so the bug hunt
     * keeps its budget; this pass restores them as a focused, best-effort call whose failure never
     * loses the draft. Its rules inventory every changed log statement and comment, so it needs the
     * full diff; only a chunked review, whose diff may exceed one prompt, falls back to the
     * condensed index. Either way the log inventory is extracted from the full diff in {@code
     * manifest}.
     */
    private ReviewResult hygieneFindings(
            PRReviewRequest request,
            boolean chunked,
            InspectionManifest manifest,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        validateAuthority();
        try {
            String raw =
                    provider.complete(
                            ReviewPrompts.buildHygienePrompt(
                                    chunked
                                            ? chunkedReviewService.finalValidationRequest(request)
                                            : request,
                                    ChangedLogStatements.extract(manifest)),
                            HYGIENE_TIMEOUT_MS,
                            true,
                            false,
                            onStatus);
            ReviewResult hygiene =
                    ReviewResultParser.parseReview(
                            raw, ReviewResultParser.RECALL_MAX_LINE_COMMENTS);
            // Hygiene findings bypass validation, so an unanchored one must never survive.
            hygiene = ReviewAnchorValidator.validate(hygiene, manifest);
            onStatus.accept("Hygiene pass found " + findings(hygiene.getLineComments().size()));
            return hygiene;
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("Hygiene pass failed; continuing without it", exception);
            onStatus.accept(STATUS_HYGIENE_FAILED);
            return new ReviewResult("", "APPROVE", new ArrayList<>());
        }
    }

    /**
     * Re-adds confirmed hygiene findings that validation dropped. The hygiene rules are mechanical
     * (log level, attached exception, comment wording), and the validator reliably discards them as
     * low-value even when told to keep them, which loses exactly the findings production reviewers
     * such as Mae report. A hygiene finding is kept unless it is low confidence, does not anchor to
     * a changed line, or a validated finding of the same category already sits within {@link
     * #HYGIENE_COVER_LINES} lines of it in the same file (the validator reworded or merged it).
     */
    static ReviewResult restoreHygieneFindings(
            ReviewResult validated, ReviewResult hygiene, InspectionManifest manifest) {
        List<LineComment> kept = new ArrayList<>(validated.getLineComments());
        List<LineComment> restored = new ArrayList<>();
        for (LineComment comment : hygiene.getLineComments()) {
            if ("low".equals(comment.getConfidence())
                    || manifest.hunkFor(comment.getFile(), comment.getLine()).isEmpty()
                    || kept.stream().anyMatch(other -> covers(other, comment))) {
                continue;
            }
            kept.add(comment);
            restored.add(comment);
        }
        if (restored.isEmpty()) {
            return validated;
        }
        return ReviewResultMerger.merge(
                validated, new ReviewResult("", "COMMENT", restored), MERGED_CANDIDATE_CAP);
    }

    private static boolean covers(LineComment kept, LineComment hygiene) {
        return Objects.equals(kept.getFile(), hygiene.getFile())
                && Math.abs(kept.getLine() - hygiene.getLine()) <= HYGIENE_COVER_LINES
                && Objects.equals(kept.getCategory(), hygiene.getCategory());
    }

    /**
     * One diagnostic status per draft finding whose file and line no longer appear after
     * validation. Opt-in via {@link #REPORT_DROPPED_PROPERTY} so recall benchmarks can tell a
     * finding that was never produced from one validation discarded.
     */
    static List<String> droppedStatuses(ReviewResult draft, ReviewResult validated) {
        Set<String> kept = new HashSet<>();
        for (LineComment comment : validated.getLineComments()) {
            kept.add(comment.getFile() + ":" + comment.getLine());
        }
        List<String> statuses = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (LineComment comment : draft.getLineComments()) {
            String location = comment.getFile() + ":" + comment.getLine();
            if (kept.contains(location) || !reported.add(location)) continue;
            statuses.add(
                    "Validation dropped finding at "
                            + location
                            + " — "
                            + StringUtils.abbreviate(
                                    StringUtils.normalizeSpace(comment.getBody()),
                                    DROPPED_BODY_MAX_CHARS));
        }
        return statuses;
    }

    private PRReviewRequest withBaseCommitContext(
            PRReviewRequest request, InspectionManifest manifest, Consumer<String> onStatus)
            throws InterruptedException {
        File projectDir = provider.projectDir();
        if (StringUtils.isBlank(request.getBaseSha()) || projectDir == null) {
            log.warn("No base commit or project directory; reviewing without base-commit guidance");
            return request;
        }
        onStatus.accept(STATUS_BASE_CONTEXT);
        BaseCommitContext.Result resolved =
                baseContextResolver.resolve(
                        projectDir,
                        request.getBaseSha(),
                        manifest,
                        request.getGuidanceGlobs(),
                        provider::checkCancelled);
        String guidelines =
                StringUtils.isBlank(resolved.guidelines())
                        ? request.getRepoGuidelines()
                        : resolved.guidelines();
        return request.withBaseCommitContext(
                guidelines, resolved.fileHistory(), resolved.callSites());
    }

    /** Appends the reviewer's configured local rules folder to the repository guidance. */
    static PRReviewRequest withLocalRules(PRReviewRequest request) {
        String rules = LocalReviewRules.read(request.getRulesDirectory());
        if (rules.isEmpty()) return request;
        return request.toBuilder()
                .repoGuidelines(LocalReviewRules.appendTo(request.getRepoGuidelines(), rules))
                .build();
    }

    /** The merged first-pass result, with the reviewer labels when both reviewers succeeded. */
    private record Passes(ReviewPassResult result, ReviewerAttribution.Labels labels) {}

    private Passes reviewPasses(
            PRReviewRequest request,
            boolean chunked,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        if (secondary == null)
            return new Passes(runPrimary(request, chunked, onStatus, onChunk), null);

        ExecutorService executor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "pr-pilot-second-reviewer");
                            thread.setDaemon(true);
                            return thread;
                        });
        boolean primaryCompleted = false;
        Future<ReviewPassResult> future = null;
        try {
            future = executor.submit(() -> runSecondary(request, chunked));
            // Status is reported only from this thread: host status sinks are not thread-safe.
            onStatus.accept(secondReviewerStatus("started in parallel"));
            ReviewPassResult primary = runPrimary(request, chunked, onStatus, onChunk);
            primaryCompleted = true;
            onStatus.accept(
                    reviewerStatus(
                            "Primary",
                            provider.displayModel(),
                            "finished with " + findings(primary)));
            if (!future.isDone()) onStatus.accept(secondReviewerStatus("still running; waiting…"));
            ReviewPassResult second = awaitSecondary(future);
            if (second == null) {
                onStatus.accept(secondReviewerStatus("failed; using primary findings"));
                return new Passes(primary, null);
            }
            onStatus.accept(secondReviewerStatus("finished with " + findings(second)));
            ReviewerAttribution.Labels labels =
                    ReviewerAttribution.Labels.of(
                            provider.displayModel(),
                            StringUtils.defaultIfBlank(secondary.displayModel(), secondaryModel));
            ReviewerAttribution.Collapsed collapsed =
                    ReviewerAttribution.collapse(primary.review(), second.review(), labels);
            ReviewPassResult merged =
                    new ReviewPassResult(
                            ReviewResultMerger.merge(
                                    collapsed.primary(), collapsed.second(), MERGED_CANDIDATE_CAP),
                            InspectionLedger.merge(List.of(primary.ledger(), second.ledger())));
            onStatus.accept("Merged reviewers into " + findings(merged));
            return new Passes(merged, labels);
        } finally {
            if (!primaryCompleted) {
                if (cancelSecondary != null) cancelSecondary.run();
                if (future != null) future.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private static String findings(ReviewPassResult pass) {
        return findings(pass.review().getLineComments().size());
    }

    private static String findings(int count) {
        return count + (count == 1 ? " finding" : " findings");
    }

    static String coverageStatus(long files, int hunks, int found, int failed) {
        String status =
                "Coverage follow-ups re-reviewed %d %s and %d %s and found %s"
                        .formatted(
                                files,
                                files == 1 ? "file" : "files",
                                hunks,
                                hunks == 1 ? "hunk" : "hunks",
                                findings(found));
        return failed == 0
                ? status
                : status + " (" + failed + (failed == 1 ? " call" : " calls") + " failed)";
    }

    static String draftStatus(int count) {
        return "Draft review has " + findings(count) + " before validation";
    }

    static String validatedStatus(int kept, int draft) {
        return "Validation kept " + kept + " of " + findings(draft);
    }

    private String secondReviewerStatus(String state) {
        return reviewerStatus("Second", secondaryModel, state);
    }

    static String reviewerStatus(String role, String model, String state) {
        String name = StringUtils.isBlank(model) ? "" : " (" + model.trim() + ")";
        return role + " reviewer" + name + " " + state;
    }

    private ReviewPassResult runPrimary(
            PRReviewRequest request,
            boolean chunked,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        return chunked
                ? chunkedReviewService.reviewPass(
                        request, onStatus, passRequest -> primary(passRequest, onStatus, onChunk))
                : primary(request, onStatus, onChunk);
    }

    private ReviewPassResult runSecondary(PRReviewRequest request, boolean chunked)
            throws IOException, InterruptedException {
        Consumer<String> noStatus = ignored -> {};
        BiConsumer<String, String> noChunks = (ignoredType, ignoredText) -> {};
        return chunked
                ? chunkedReviewService.reviewPass(
                        request,
                        noStatus,
                        passRequest -> secondary.primary(passRequest, noStatus, noChunks))
                : secondary.primary(request, noStatus, noChunks);
    }

    /** Waits for the second reviewer, returning null when it failed; cancellation propagates. */
    private ReviewPassResult awaitSecondary(Future<ReviewPassResult> future)
            throws InterruptedException {
        try {
            while (true) {
                provider.checkCancelled();
                try {
                    return future.get(SECONDARY_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillRunning) {
                    // Poll again so a cancelled primary review stops waiting promptly.
                }
            }
        } catch (ExecutionException failed) {
            log.warn("Second reviewer failed; keeping the primary review", failed.getCause());
            return null;
        } catch (InterruptedException interrupted) {
            if (cancelSecondary != null) cancelSecondary.run();
            future.cancel(true);
            throw interrupted;
        }
    }

    private ReviewResult supervise(
            PRReviewRequest request,
            InspectionManifest manifest,
            ReviewPassResult primary,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        onStatus.accept("Checking review coverage…");
        List<CoverageGap> gaps = coverageAnalyzer.findGaps(manifest, primary.ledger());
        if (gaps.isEmpty()) {
            logCoverage(manifest, primary, 0, 0, 0, elapsedMillis(startedAt));
            onStatus.accept(STATUS_COVERAGE_COMPLETE);
            return primary.review();
        }

        List<CoverageGap> fileGaps = gaps.stream().filter(CoverageGap::wholeFile).toList();
        Set<String> wholeFilePaths = new HashSet<>();
        fileGaps.forEach(gap -> wholeFilePaths.add(gap.path()));
        // A hunk inside a file that is re-reviewed in full needs no separate follow-up.
        List<CoverageGap> hunkGaps =
                gaps.stream()
                        .filter(gap -> !gap.wholeFile() && !wholeFilePaths.contains(gap.path()))
                        .toList();

        List<List<FollowUpDirective>> batches = new ArrayList<>();
        List<FollowUpDirective> hunkDirectives =
                selectHunkDirectives(request, hunkGaps, primary, onStatus);
        if (!hunkDirectives.isEmpty()) batches.add(hunkDirectives);
        List<FollowUpDirective> fileDirectives = ReviewSupervisorPrompts.allDirectives(fileGaps);
        int fileBatches = 0;
        for (int i = 0; i < fileDirectives.size(); i += FILES_PER_FOLLOW_UP) {
            if (fileBatches == MAX_FILE_FOLLOW_UPS) {
                log.info(
                        "Review supervision: {} uncovered files exceed the re-review limit;"
                                + " skipping the rest",
                        fileDirectives.size() - i);
                break;
            }
            batches.add(
                    fileDirectives.subList(
                            i, Math.min(i + FILES_PER_FOLLOW_UP, fileDirectives.size())));
            fileBatches++;
        }
        if (batches.isEmpty()) {
            logCoverage(manifest, primary, gaps.size(), 0, 0, elapsedMillis(startedAt));
            onStatus.accept(coverageStatus(0, 0, 0, 0));
            return primary.review();
        }

        ReviewResult merged = primary.review();
        int directiveCount = 0;
        int followUpFindings = 0;
        int failedFollowUps = 0;
        for (int i = 0; i < batches.size(); i++) {
            List<FollowUpDirective> directives = batches.get(i);
            provider.checkCancelled();
            onStatus.accept(
                    batches.size() == 1
                            ? "Inspecting missed areas…"
                            : "Inspecting missed areas (%d/%d)…".formatted(i + 1, batches.size()));
            validateAuthority();
            try {
                PRReviewRequest followUpRequest =
                        ReviewSupervisorPrompts.followUpRequest(request, manifest, directives);
                InspectionManifest followUpManifest =
                        InspectionManifest.fromDiff(followUpRequest.getDiff());
                String raw =
                        provider.complete(
                                ReviewPrompts.buildPrompt(followUpRequest, followUpManifest),
                                FOLLOW_UP_TIMEOUT_MS,
                                true,
                                false,
                                onStatus);
                ReviewPassResult followUp = ReviewPassParser.parse(raw, followUpManifest, null);
                directiveCount += directives.size();
                followUpFindings += followUp.review().getLineComments().size();
                merged = ReviewResultMerger.merge(merged, followUp.review());
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException exception) {
                log.warn("Targeted review follow-up failed; keeping the review so far", exception);
                failedFollowUps++;
            }
        }
        onStatus.accept(
                coverageStatus(
                        Math.min(fileDirectives.size(), FILES_PER_FOLLOW_UP * MAX_FILE_FOLLOW_UPS),
                        hunkDirectives.size(),
                        followUpFindings,
                        failedFollowUps));
        logCoverage(
                manifest,
                primary,
                gaps.size(),
                directiveCount,
                followUpFindings,
                elapsedMillis(startedAt));
        return merged;
    }

    /**
     * Chooses which uncovered high-risk hunks to follow up on: all of them when there are at most
     * three, otherwise the ones a tool-free supervisor call ranks highest. A failed ranking call
     * yields no hunk directives rather than failing supervision, so uncovered files are still
     * re-reviewed.
     */
    private List<FollowUpDirective> selectHunkDirectives(
            PRReviewRequest request,
            List<CoverageGap> hunkGaps,
            ReviewPassResult primary,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        if (hunkGaps.isEmpty()) return List.of();
        if (hunkGaps.size() <= 3) return ReviewSupervisorPrompts.deterministicDirectives(hunkGaps);
        onStatus.accept("Prioritizing missed areas…");
        validateAuthority();
        try {
            String selected =
                    provider.complete(
                            ReviewSupervisorPrompts.selectionPrompt(
                                    request, hunkGaps, primary.review()),
                            SUPERVISOR_TIMEOUT_MS,
                            false,
                            false,
                            ignored -> {});
            return ReviewSupervisorPrompts.parseDirectives(selected, hunkGaps);
        } catch (IOException exception) {
            log.warn(
                    "Review supervisor prioritization failed; skipping hunk follow-ups", exception);
            return List.of();
        }
    }

    private static void logCoverage(
            InspectionManifest manifest,
            ReviewPassResult primary,
            int gaps,
            int directives,
            int followUpFindings,
            long elapsedMillis) {
        int hunkCount = manifest.files().stream().mapToInt(file -> file.hunks().size()).sum();
        log.info(
                "Review supervision: files={}, hunks={}, ledgerReported={}, inspectedTargets={},"
                        + " gaps={}, directives={}, followUpFindings={}, elapsedMs={}",
                manifest.files().size(),
                hunkCount,
                primary.ledger().reported(),
                primary.ledger().inspectedTargetIds().size(),
                gaps,
                directives,
                followUpFindings,
                elapsedMillis);
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    interface ProviderExecutor {
        ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException, InterruptedException;

        String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException, InterruptedException;

        void checkCancelled() throws InterruptedException;

        /** The working directory the provider reviews in, or null when it has none. */
        default File projectDir() {
            return null;
        }

        /** The model name shown in review progress, or blank when unknown. */
        default String displayModel() {
            return "";
        }
    }

    /** Resolves base-commit guidance and file history; injectable for tests. */
    @FunctionalInterface
    interface BaseContextResolver {
        BaseCommitContext.Result resolve(
                File repoDir,
                String baseSha,
                InspectionManifest manifest,
                List<String> guidanceGlobs,
                BaseCommitContext.CancellationCheck cancellation)
                throws InterruptedException;
    }

    private record ClaudeExecutor(ClaudeService service, String model) implements ProviderExecutor {
        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException, InterruptedException {
            return service.reviewPass(request, model, onStatus, onChunk);
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException, InterruptedException {
            return service.completeReviewPrompt(
                    prompt, model, onStatus, timeoutMillis, allowReadTools);
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            service.throwIfCancelled();
        }

        @Override
        public File projectDir() {
            return service.projectDir();
        }

        @Override
        public String displayModel() {
            return StringUtils.defaultIfBlank(model, "Claude default");
        }
    }

    private record CopilotExecutor(
            CopilotService service,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir)
            implements ProviderExecutor {
        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException, InterruptedException {
            return service.reviewPass(
                    request, model, effort, onStatus, onChunk, inheritMcp, configDir);
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException, InterruptedException {
            return service.completeReviewPrompt(
                    prompt,
                    model,
                    effort,
                    allowMcp && inheritMcp,
                    configDir,
                    allowReadTools,
                    timeoutMillis,
                    onStatus);
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            service.throwIfCancelled();
        }

        @Override
        public File projectDir() {
            return service.projectDir();
        }

        @Override
        public String displayModel() {
            return StringUtils.defaultIfBlank(model, "Copilot default");
        }
    }
}
