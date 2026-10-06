package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.ReviewEngineApi.GenerateReviewParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.PrParams;
import com.jinloes.prpilot.engine.ReviewSessionService;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * Reviews ReviewBench pull requests with PR Pilot and writes one findings file per PR and round,
 * ready for ReviewBench's {@code npm run judge}. Scoring is deliberately left to ReviewBench's own
 * judge so results stay comparable with its leaderboard methodology.
 *
 * <p>Only the corpus's title, body, and diff reach the prompt. GitHub is never queried: the PRs are
 * public and their review threads, CI, and later commits could leak golden findings.
 */
public final class ReviewBenchRunner {
    private final ReviewBenchOptions options;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHubEngine github = new GitHubEngine();
    private final GitWorktreeService worktrees = new GitWorktreeService();
    private final PrintStream log;
    private ReviewSessionService reviews;

    ReviewBenchRunner(ReviewBenchOptions options, PrintStream log) {
        this.options = options;
        this.log = log;
    }

    public static void main(String[] args) {
        ReviewBenchOptions options;
        try {
            options = ReviewBenchOptions.parse(List.of(args));
        } catch (IllegalArgumentException invalid) {
            System.err.println(invalid.getMessage());
            System.err.println();
            System.err.println(ReviewBenchOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(ReviewBenchOptions.USAGE);
            return;
        }
        try {
            int failed = new ReviewBenchRunner(options, System.err).run();
            System.out.println("Findings: " + findingsRoot(options.out()).toAbsolutePath());
            if (failed > 0) System.exit(1);
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("ReviewBench run failed: " + failure.getMessage());
            System.exit(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("ReviewBench run interrupted.");
            System.exit(130);
        }
    }

    static Path findingsRoot(Path out) {
        return out.resolve("findings");
    }

    static Path findingsFile(Path out, int round, ReviewBenchTask task) {
        return findingsRoot(out).resolve("round-" + round).resolve(task.key() + ".json");
    }

    static Path diagnosticsFile(Path out, int round, ReviewBenchTask task) {
        return out.resolve("diagnostics").resolve("round-" + round).resolve(task.key() + ".json");
    }

    private record Reviewed(ReviewBenchFindings findings, ReviewBenchDiagnostics diagnostics) {}

    /** Returns how many reviews failed; their findings files are not written. */
    int run() throws IOException, InterruptedException {
        List<ReviewBenchTask> tasks =
                options.select(ReviewBenchTask.load(options.corpus(), options.set(), mapper));
        if (tasks.isEmpty()) throw new IOException("No ReviewBench tasks selected.");
        Files.createDirectories(options.out());
        System.setProperty(
                ReviewBenchmark.CALL_SITES_PROPERTY, Boolean.toString(options.callSites()));
        System.setProperty(ReviewBenchmark.REPORT_DROPPED_PROPERTY, "true");
        reviews =
                new ReviewSessionService(
                        new ReviewOutcomeLog(options.out().resolve("outcomes.jsonl")));
        List<String> failures = new ArrayList<>();
        for (int round = 1; round <= options.rounds(); round++) {
            for (int i = 0; i < tasks.size(); i++) {
                ReviewBenchTask task = tasks.get(i);
                String prefix =
                        "round "
                                + round
                                + "/"
                                + options.rounds()
                                + " ["
                                + (i + 1)
                                + "/"
                                + tasks.size()
                                + "] "
                                + task.key()
                                + ": ";
                Path file = findingsFile(options.out(), round, task);
                if (Files.exists(file)) {
                    log.println(prefix + "already reviewed");
                    continue;
                }
                try {
                    Reviewed reviewed = review(task, status -> log.println(prefix + status));
                    // Diagnostics first: an existing findings file marks the review complete.
                    reviewed.diagnostics()
                            .write(diagnosticsFile(options.out(), round, task), mapper);
                    reviewed.findings().write(file, mapper);
                    log.println(prefix + reviewed.findings().findings().size() + " findings");
                } catch (IOException | RuntimeException failure) {
                    String message =
                            StringUtils.defaultIfBlank(
                                    failure.getMessage(), failure.getClass().getSimpleName());
                    log.println(prefix + "FAILED — " + message);
                    failures.add("round-" + round + "\t" + task.key() + "\t" + message);
                }
            }
        }
        Path failureLog = options.out().resolve("failures.tsv");
        if (failures.isEmpty()) {
            Files.deleteIfExists(failureLog);
        } else {
            Files.write(failureLog, failures);
            log.println(failures.size() + " review(s) failed; see " + failureLog);
        }
        return failures.size();
    }

    private Reviewed review(ReviewBenchTask task, Consumer<String> say)
            throws IOException, InterruptedException {
        say.accept("preparing " + task.cloneName());
        File clone =
                ReviewBenchCheckout.ensureClone(
                        options.reposDir(), task.cloneName(), task.mirrorUrl());
        try (ReviewBenchCheckout checkout =
                ReviewBenchCheckout.open(
                        worktrees,
                        clone,
                        task.prNumber(),
                        task.base(),
                        task.head(),
                        task.repo(),
                        options.chunked())) {
            if (!"ok".equals(checkout.diff().status())) {
                throw new IOException("Diff unavailable: " + checkout.diff().message());
            }
            String worktree = checkout.worktreeDir().getAbsolutePath();
            String profile = github.getRepoProfile(worktree).summary();
            say.accept("reviewing");
            long start = System.nanoTime();
            List<String> stages = new ArrayList<>();
            List<Finding> dropped = new ArrayList<>();
            ReviewResult result =
                    reviews.generate(
                            params(task, worktree, checkout.diff().diff(), profile),
                            status -> {
                                Finding droppedFinding = ReviewBenchmark.parseDropped(status);
                                if (droppedFinding != null) {
                                    dropped.add(droppedFinding);
                                } else if (ReviewBenchmark.isStage(status)) {
                                    stages.add(status);
                                }
                                if (options.verbose()) say.accept(status);
                            },
                            (a, b) -> {});
            long millis = (System.nanoTime() - start) / 1_000_000;
            return new Reviewed(
                    ReviewBenchFindings.of(
                            task, result.getLineComments(), millis, options.includeNotes()),
                    ReviewBenchDiagnostics.of(stages, dropped));
        }
    }

    private GenerateReviewParams params(
            ReviewBenchTask task, String worktree, String diff, String profile) {
        return new GenerateReviewParams(
                "reviewbench-" + task.key(),
                options.provider(),
                worktree,
                options.model(),
                options.effort(),
                false,
                options.configDir(),
                options.selfCritique(),
                options.supervisor(),
                new PrParams(
                        StringUtils.defaultString(task.title()),
                        task.repo() + "/pull/" + task.prNumber(),
                        task.owner(),
                        task.name(),
                        task.prNumber(),
                        StringUtils.defaultString(task.body()),
                        "",
                        "",
                        false),
                diff,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                StringUtils.defaultString(profile),
                List.of(),
                options.chunked(),
                null,
                task.base(),
                options.secondReviewerModel(),
                List.of(),
                "");
    }
}
