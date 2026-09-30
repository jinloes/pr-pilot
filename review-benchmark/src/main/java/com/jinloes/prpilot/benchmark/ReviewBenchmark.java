package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.ReviewEngineApi.CiAnnotationParam;
import com.jinloes.prpilot.engine.ReviewEngineApi.GenerateReviewParams;
import com.jinloes.prpilot.engine.ReviewEngineApi.PrParams;
import com.jinloes.prpilot.engine.ReviewSessionService;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.PromptCompleter;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import com.jinloes.prpilot.sidecar.github.GitHubApiClient;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubSession;
import com.jinloes.prpilot.sidecar.pr.CheckRunService;
import com.jinloes.prpilot.sidecar.pr.CheckStatusResult;
import com.jinloes.prpilot.sidecar.pr.LinkedIssueService;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import com.jinloes.prpilot.sidecar.pr.PrDetailResult;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;

/**
 * Measures how many of Mae's inline review findings PR Pilot also raises.
 *
 * <p>Each PR is reviewed at the exact commit Mae first reviewed. Existing reviews, the prior
 * review, and the commit list are withheld from the prompt: they can contain Mae's own comments or
 * replies to them, which would leak the answers.
 */
public final class ReviewBenchmark {
    /** Mirrors {@code BaseCommitContext.CALL_SITES_PROPERTY}, which is package-private. */
    static final String CALL_SITES_PROPERTY = "prpilot.review.callSites";

    private static final DateTimeFormatter STEM =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final BenchmarkOptions options;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHubEngine github = new GitHubEngine();
    private final GitWorktreeService worktrees = new GitWorktreeService();
    private final PrintStream log;
    private final Map<String, GitHubSession> sessions = new HashMap<>();
    private ReviewSessionService reviews;

    ReviewBenchmark(BenchmarkOptions options, PrintStream log) {
        this.options = options;
        this.log = log;
    }

    public static void main(String[] args) {
        BenchmarkOptions options;
        try {
            options = BenchmarkOptions.parse(List.of(args));
        } catch (IllegalArgumentException invalid) {
            System.err.println(invalid.getMessage());
            System.err.println();
            System.err.println(BenchmarkOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(BenchmarkOptions.USAGE);
            return;
        }
        try {
            Path report = new ReviewBenchmark(options, System.err).run();
            System.out.println("Report: " + report.toAbsolutePath());
        } catch (IOException failure) {
            System.err.println("Benchmark failed: " + failure.getMessage());
            System.exit(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Benchmark interrupted.");
            System.exit(130);
        }
    }

    Path run() throws IOException, InterruptedException {
        List<PrRef> prs = PrRef.parseList(Files.readAllLines(options.prsFile()));
        if (prs.isEmpty()) throw new IOException("No pull request URLs in " + options.prsFile());
        Instant started = Instant.now();
        Path outDir = options.outDir().resolve(STEM.format(started));
        Files.createDirectories(outDir);
        System.setProperty(CALL_SITES_PROPERTY, Boolean.toString(options.callSites()));
        reviews = new ReviewSessionService(new ReviewOutcomeLog(outDir.resolve("outcomes.jsonl")));

        List<BenchmarkReport.PrResult> results = new ArrayList<>();
        for (int i = 0; i < prs.size(); i++) {
            PrRef pr = prs.get(i);
            String prefix = "[" + (i + 1) + "/" + prs.size() + "] " + pr.label() + ": ";
            BenchmarkReport.PrResult result;
            try {
                result = benchmark(pr, status -> log.println(prefix + status));
            } catch (IOException | RuntimeException failure) {
                result =
                        BenchmarkReport.PrResult.notScored(
                                pr, url(pr), BenchmarkReport.STATUS_FAILED, message(failure));
            }
            log.println(prefix + summary(result));
            results.add(result);
        }
        BenchmarkReport report = BenchmarkReport.of(started.toString(), settings(), results);
        return report.write(outDir, "benchmark-" + STEM.format(started), mapper);
    }

    private BenchmarkReport.PrResult benchmark(PrRef pr, Consumer<String> say)
            throws IOException, InterruptedException {
        GitHubSession session = session(pr.githubBaseUrl());
        say.accept("fetching Mae comments");
        MaeComments.Baseline mae =
                MaeComments.firstReview(
                        new MaeComments(GitHubApiClient.http(), mapper)
                                .fetch(
                                        session.apiBaseUrl(),
                                        session.token(),
                                        pr,
                                        options.maeLoginPrefix()));
        if (mae.comments().isEmpty()) {
            return BenchmarkReport.PrResult.notScored(
                    pr, url(pr), BenchmarkReport.STATUS_SKIPPED, "Mae left no inline comments.");
        }
        PrDetailResult detailResult =
                github.getPullRequestDetail(
                        new PrDetailService.PrDetailParams(
                                pr.githubBaseUrl(), pr.owner(), pr.repo(), pr.number()));
        PrDetail detail = detailResult.detail();
        if (!"ok".equals(detailResult.status())
                || detail == null
                || StringUtils.isBlank(detail.baseSha())) {
            throw new IOException("Could not load PR details: " + detailResult.message());
        }

        File repoDir = repoDir(pr);
        say.accept("checking out " + StringUtils.left(mae.commitId(), 12));
        try (LocalCheckout checkout =
                LocalCheckout.open(
                        worktrees, repoDir, pr.number(), detail.baseSha(), mae.commitId())) {
            if (!"ok".equals(checkout.diff().status())) {
                throw new IOException("Diff unavailable: " + checkout.diff().message());
            }
            String worktree = checkout.worktreeDir().getAbsolutePath();
            CheckStatusResult ci =
                    github.getCheckStatus(
                            new CheckRunService.Params(
                                    pr.githubBaseUrl(), pr.owner(), pr.repo(), mae.commitId()));
            String linkedIssue =
                    github.getLinkedIssues(
                                    new LinkedIssueService.Params(
                                            pr.githubBaseUrl(),
                                            pr.owner(),
                                            pr.repo(),
                                            StringUtils.defaultString(detail.body()),
                                            List.of()))
                            .summary();
            String profile = github.getRepoProfile(worktree).summary();

            say.accept("reviewing");
            long start = System.nanoTime();
            ReviewResult review =
                    reviews.generate(
                            reviewParams(
                                    pr,
                                    detail,
                                    checkout,
                                    worktree,
                                    ci,
                                    linkedIssue,
                                    profile,
                                    mae.commitId()),
                            status -> {
                                if (options.verbose()) say.accept(status);
                            },
                            (a, b) -> {});
            long reviewMillis = (System.nanoTime() - start) / 1_000_000;

            List<Finding> expected =
                    findings(
                            "M",
                            mae.comments(),
                            c -> new Finding(null, c.path(), c.line(), c.body()));
            List<Finding> actual =
                    findings(
                            "P",
                            review.getLineComments(),
                            c ->
                                    new Finding(
                                            null,
                                            c.getFile(),
                                            Math.max(0, c.getLine()),
                                            c.getBody()));
            say.accept("matching " + expected.size() + " Mae / " + actual.size() + " PR Pilot");
            FindingMatcher.Outcome outcome =
                    FindingMatcher.match(expected, actual, options.lineWindow(), judge(worktree));
            return new BenchmarkReport.PrResult(
                    pr.label(),
                    url(pr),
                    BenchmarkReport.STATUS_SCORED,
                    "",
                    mae.commitId(),
                    reviewMillis,
                    checkout.diff().truncated(),
                    expected.size(),
                    actual.size(),
                    outcome.matches().stream()
                            .map(m -> new BenchmarkReport.Match(m.expected(), m.actual()))
                            .toList(),
                    outcome.misses().stream()
                            .map(m -> new BenchmarkReport.Miss(m.expected(), m.reason()))
                            .toList(),
                    outcome.extras());
        }
    }

    private GenerateReviewParams reviewParams(
            PrRef pr,
            PrDetail detail,
            LocalCheckout checkout,
            String worktree,
            CheckStatusResult ci,
            String linkedIssue,
            String profile,
            String commit) {
        List<CiAnnotationParam> annotations =
                ci.annotations().stream()
                        .map(
                                a ->
                                        new CiAnnotationParam(
                                                a.path(), a.startLine(), a.level(), a.message()))
                        .toList();
        return new GenerateReviewParams(
                "benchmark-" + pr.owner() + "-" + pr.repo() + "-" + pr.number(),
                options.provider(),
                worktree,
                options.model(),
                options.effort(),
                false,
                options.configDir(),
                options.selfCritique(),
                options.supervisor(),
                new PrParams(
                        StringUtils.defaultString(detail.title()),
                        url(pr),
                        pr.owner(),
                        pr.repo(),
                        pr.number(),
                        StringUtils.defaultString(detail.body()),
                        "",
                        "",
                        false),
                checkout.diff().diff(),
                "",
                "",
                "",
                "",
                "",
                StringUtils.defaultString(ci.summary()),
                "",
                StringUtils.defaultString(linkedIssue),
                StringUtils.defaultString(profile),
                annotations,
                options.chunked(),
                null,
                StringUtils.defaultString(detail.baseSha()),
                options.secondReviewerModel());
    }

    private FindingMatcher.Judge judge(String worktree) {
        if (BenchmarkOptions.JUDGE_LOCATION.equals(options.judge())) {
            return FindingMatcher.LOCATION_ONLY;
        }
        PromptCompleter completer =
                PromptCompleter.forProvider(
                        options.provider(),
                        worktree,
                        options.judgeModel(),
                        options.effort(),
                        options.configDir());
        return new LlmJudge(prompt -> completer.complete(prompt, LlmJudge.TIMEOUT_MILLIS), mapper);
    }

    private GitHubSession session(String baseUrl) throws IOException {
        GitHubSession session =
                sessions.computeIfAbsent(
                        baseUrl,
                        url ->
                                GitHubSession.open(
                                        new GitHubAuthService.ProcessTokenResolver(), url));
        if (!session.isOpen()) {
            throw new IOException(
                    "GitHub is not available for "
                            + baseUrl
                            + " ("
                            + session.failure()
                            + "). Run gh auth login.");
        }
        return session;
    }

    File repoDir(PrRef pr) throws IOException {
        for (Path candidate :
                List.of(
                        options.reposRoot().resolve(pr.repo()),
                        options.reposRoot().resolve(pr.owner()).resolve(pr.repo()))) {
            if (Files.exists(candidate.resolve(".git"))) return candidate.toFile();
        }
        throw new IOException(
                "No local clone of "
                        + pr.owner()
                        + "/"
                        + pr.repo()
                        + " under "
                        + options.reposRoot()
                        + " (expected <repo> or <owner>/<repo>).");
    }

    static <T> List<Finding> findings(String prefix, List<T> items, Function<T, Finding> map) {
        List<Finding> findings = new ArrayList<>();
        for (T item : items) {
            Finding finding = map.apply(item);
            findings.add(
                    new Finding(
                            prefix + (findings.size() + 1),
                            finding.path(),
                            finding.line(),
                            StringUtils.defaultString(finding.body())));
        }
        return findings;
    }

    private BenchmarkReport.Settings settings() {
        return new BenchmarkReport.Settings(
                options.provider(),
                options.model(),
                options.effort(),
                options.secondReviewerModel(),
                options.selfCritique(),
                options.supervisor(),
                options.chunked(),
                options.callSites(),
                options.judge(),
                options.judgeModel(),
                options.lineWindow());
    }

    private static String url(PrRef pr) {
        return pr.githubBaseUrl() + "/" + pr.owner() + "/" + pr.repo() + "/pull/" + pr.number();
    }

    private static String summary(BenchmarkReport.PrResult result) {
        if (!BenchmarkReport.STATUS_SCORED.equals(result.status())) {
            return result.status() + " — " + result.message();
        }
        return "matched "
                + result.matches().size()
                + "/"
                + result.maeFindings()
                + " Mae findings ("
                + result.prPilotFindings()
                + " PR Pilot findings)";
    }

    private static String message(Exception failure) {
        return StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName());
    }
}
