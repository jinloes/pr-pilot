package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private static final long CRITIQUE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long HYGIENE_TIMEOUT_MS = 15L * 60L * 1000L;
    private static final int DROPPED_BODY_MAX_CHARS = 120;
    static final String REPORT_DROPPED_PROPERTY = "prpilot.review.reportDropped";
    static final String STATUS_HYGIENE_FAILED = "Hygiene pass failed; continuing without it";
    private static final long SECONDARY_POLL_MS = 250;
    private static final int MERGED_CANDIDATE_CAP = 40;
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
        boolean recall = selfCritique || secondary != null;
        PRReviewRequest reviewRequest = request.withCandidateRecall(recall);
        ReviewPassResult primary = reviewPasses(reviewRequest, chunked, onStatus, onChunk);
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

        if (recall) {
            candidate =
                    withHygieneFindings(
                            request, chunked, manifest, candidate, supervisorEnabled, onStatus);
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
                                ClaudeService.buildCritiquePrompt(critiqueRequest, candidate),
                                CRITIQUE_TIMEOUT_MS,
                                true,
                                true,
                                onStatus);
                candidate = ClaudeService.parseReview(raw);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException | IllegalArgumentException exception) {
                log.warn(
                        "Final self-critique failed; keeping the best pre-critique review",
                        exception);
            }
            // Unconfirmed recall candidates never reach the user, even if validation failed.
            candidate = ReviewResultMerger.withoutLowConfidence(candidate);
            if (Boolean.getBoolean(REPORT_DROPPED_PROPERTY)) {
                droppedStatuses(draft, candidate).forEach(onStatus);
            }
            onStatus.accept(validatedStatus(candidate.getLineComments().size(), draftCount));
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
     * Runs the recall-mode hygiene pass and merges its findings into the draft. The hygiene rules
     * are left out of the recall first-pass prompt so the bug hunt keeps its budget; this pass
     * restores them as a focused, best-effort call whose failure never loses the draft. Its rules
     * inventory every changed log statement and comment, so it needs the full diff; only a chunked
     * review, whose diff may exceed one prompt, falls back to the condensed index.
     */
    private ReviewResult withHygieneFindings(
            PRReviewRequest request,
            boolean chunked,
            InspectionManifest manifest,
            ReviewResult candidate,
            boolean supervisorEnabled,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        validateAuthority();
        try {
            String raw =
                    provider.complete(
                            ClaudeService.buildHygienePrompt(
                                    chunked
                                            ? chunkedReviewService.finalValidationRequest(request)
                                            : request),
                            HYGIENE_TIMEOUT_MS,
                            true,
                            false,
                            onStatus);
            ReviewResult hygiene =
                    ClaudeService.parseReview(raw, ClaudeService.RECALL_MAX_LINE_COMMENTS);
            if (supervisorEnabled) {
                hygiene = ReviewAnchorValidator.validate(hygiene, manifest);
            }
            onStatus.accept("Hygiene pass found " + findings(hygiene.getLineComments().size()));
            return ReviewResultMerger.merge(candidate, hygiene, MERGED_CANDIDATE_CAP);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("Hygiene pass failed; continuing without it", exception);
            onStatus.accept(STATUS_HYGIENE_FAILED);
            return candidate;
        }
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

    private ReviewPassResult reviewPasses(
            PRReviewRequest request,
            boolean chunked,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        if (secondary == null) return runPrimary(request, chunked, onStatus, onChunk);

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
                return primary;
            }
            onStatus.accept(secondReviewerStatus("finished with " + findings(second)));
            ReviewPassResult merged =
                    new ReviewPassResult(
                            ReviewResultMerger.merge(
                                    primary.review(), second.review(), MERGED_CANDIDATE_CAP),
                            InspectionLedger.merge(List.of(primary.ledger(), second.ledger())));
            onStatus.accept("Merged reviewers into " + findings(merged));
            return merged;
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
            return primary.review();
        }

        List<FollowUpDirective> directives;
        if (gaps.size() <= 3) {
            directives = ReviewSupervisorPrompts.deterministicDirectives(gaps);
        } else {
            onStatus.accept("Prioritizing missed areas…");
            validateAuthority();
            try {
                String selected =
                        provider.complete(
                                ReviewSupervisorPrompts.selectionPrompt(
                                        request, gaps, primary.review()),
                                SUPERVISOR_TIMEOUT_MS,
                                false,
                                false,
                                ignored -> {});
                directives = ReviewSupervisorPrompts.parseDirectives(selected, gaps);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException exception) {
                log.warn(
                        "Review supervisor prioritization failed; keeping baseline review",
                        exception);
                logCoverage(manifest, primary, gaps.size(), 0, 0, elapsedMillis(startedAt));
                return primary.review();
            }
        }
        if (directives.isEmpty()) {
            logCoverage(manifest, primary, gaps.size(), 0, 0, elapsedMillis(startedAt));
            return primary.review();
        }

        provider.checkCancelled();
        onStatus.accept("Inspecting missed areas…");
        validateAuthority();
        try {
            PRReviewRequest followUpRequest =
                    ReviewSupervisorPrompts.followUpRequest(request, manifest, directives);
            InspectionManifest followUpManifest =
                    InspectionManifest.fromDiff(followUpRequest.getDiff());
            String raw =
                    provider.complete(
                            ClaudeService.buildPrompt(followUpRequest, followUpManifest),
                            FOLLOW_UP_TIMEOUT_MS,
                            true,
                            false,
                            onStatus);
            ReviewPassResult followUp = ReviewPassParser.parse(raw, followUpManifest, null);
            logCoverage(
                    manifest,
                    primary,
                    gaps.size(),
                    directives.size(),
                    followUp.review().getLineComments().size(),
                    elapsedMillis(startedAt));
            return ReviewResultMerger.merge(primary.review(), followUp.review());
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (IOException exception) {
            log.warn("Targeted review follow-up failed; keeping baseline review", exception);
            logCoverage(
                    manifest, primary, gaps.size(), directives.size(), 0, elapsedMillis(startedAt));
            return primary.review();
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
