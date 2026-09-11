package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared provider-neutral review pipeline used by every host. Optional supervision is bounded to
 * one tool-free prioritization call and one targeted read-only follow-up.
 */
public final class ReviewPipelineService {
    private static final Logger log = LoggerFactory.getLogger(ReviewPipelineService.class);
    private static final long SUPERVISOR_TIMEOUT_MS = 90_000;
    private static final long FOLLOW_UP_TIMEOUT_MS = 6L * 60L * 1000L;
    private static final long CRITIQUE_TIMEOUT_MS = 30L * 60L * 1000L;

    private final ProviderExecutor provider;
    private final ChunkedReviewService chunkedReviewService;
    private final ReviewCoverageAnalyzer coverageAnalyzer;
    private final SemanticReviewService.Execution execution;

    private ReviewPipelineService(ProviderExecutor provider) {
        this(provider, new ChunkedReviewService(), new ReviewCoverageAnalyzer());
    }

    ReviewPipelineService(
            ProviderExecutor provider,
            ChunkedReviewService chunkedReviewService,
            ReviewCoverageAnalyzer coverageAnalyzer) {
        this(provider, chunkedReviewService, coverageAnalyzer, null);
    }

    private ReviewPipelineService(
            ProviderExecutor provider,
            ChunkedReviewService chunkedReviewService,
            ReviewCoverageAnalyzer coverageAnalyzer,
            SemanticReviewService.Execution execution) {
        this.provider = provider;
        this.chunkedReviewService = chunkedReviewService;
        this.coverageAnalyzer = coverageAnalyzer;
        this.execution = execution;
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
        return new ReviewPipelineService(
                        selected, chunkedReviewService, coverageAnalyzer, execution)
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
        ReviewPassResult primary =
                chunked
                        ? chunkedReviewService.reviewPass(
                                request,
                                onStatus,
                                passRequest -> primary(passRequest, onStatus, onChunk))
                        : primary(request, onStatus, onChunk);
        provider.checkCancelled();

        InspectionManifest manifest = InspectionManifest.fromDiff(request.getDiff());
        if (supervisorEnabled) {
            primary =
                    new ReviewPassResult(
                            ReviewAnchorValidator.validate(primary.review(), manifest),
                            primary.ledger());
        }
        ReviewResult candidate = primary.review();
        if (supervisorEnabled) {
            candidate = supervise(request, manifest, primary, onStatus);
        }
        provider.checkCancelled();

        if (selfCritique) {
            onStatus.accept(ClaudeService.STATUS_REFINING);
            validateAuthority();
            try {
                PRReviewRequest critiqueRequest =
                        chunked ? chunkedReviewService.finalValidationRequest(request) : request;
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
        }
        provider.checkCancelled();
        if (supervisorEnabled) {
            candidate = ReviewAnchorValidator.validate(candidate, manifest);
        }
        validateAuthority();
        return CiFindingSuppressor.suppress(candidate, request.getCiAnnotations());
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
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
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
    }
}
