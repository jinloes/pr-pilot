package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import com.jinloes.prpilot.review.ReviewPipelineService;
import com.jinloes.prpilot.util.LogRedactor;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;

/**
 * Fast (~5-10s) live contract canary for PR Pilot's review providers ({@code copilot} and {@code
 * claude}).
 *
 * <p>Exercises binary resolution, live Copilot SDK protocol negotiation and model catalog
 * discovery, isolated temporary worktree read-only access, and end-to-end JSON review output
 * parsing against a tiny synthetic diff. Designed for SDK/CLI upgrades and opt-in/nightly checks so
 * protocol or permission-gate regressions are caught immediately without running a 25-PR benchmark.
 */
public final class ProviderCanary {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VALID_VERDICTS =
            Set.of("APPROVE", "COMMENT", "REQUEST_CHANGES");
    private static final String CANARY_FILE = "src/BatchAverage.java";
    private static final String CANARY_HELPER_FILE = "src/MathOps.java";
    private static final String CANARY_DIFF =
            "diff --git a/src/BatchAverage.java b/src/BatchAverage.java\n"
                    + "index 1111111..2222222 100644\n"
                    + "--- a/src/BatchAverage.java\n"
                    + "+++ b/src/BatchAverage.java\n"
                    + "@@ -1,5 +1,7 @@\n"
                    + " package acme;\n"
                    + " public final class BatchAverage {\n"
                    + "+    public static int mean(int sum, int count) {\n"
                    + "+        return MathOps.divide(sum, count);\n"
                    + "+    }\n"
                    + " }\n";

    static final String USAGE =
            "Usage: ./gradlew :review-benchmark:providerCanary --args=\"[options]\"\n"
                    + "  --provider copilot|claude|both   Provider(s) to verify (default: copilot)\n"
                    + "  --model <id>                     Optional model override\n"
                    + "  --effort low|medium|high         Copilot reasoning effort (default: low)\n"
                    + "  --json                           Emit JSON report to stdout\n"
                    + "  --help                           Show this help";

    public record Options(
            List<ReviewProvider> providers,
            String model,
            String effort,
            boolean json,
            boolean help) {}

    public record CheckReport(
            String provider,
            boolean passed,
            String binaryStatus,
            String discoverySource,
            int discoveredModels,
            String verdict,
            int findingCount,
            long elapsedMs,
            String failure) {}

    interface ProviderProbe {
        ProviderSetupProbe.Result preflight(ReviewProvider provider);

        CopilotModelDiscovery.Result discoverCopilotModels();

        ReviewResult runCanaryReview(
                ReviewProvider provider,
                Path repoDir,
                PRReviewRequest request,
                String model,
                String effort)
                throws Exception;
    }

    private ProviderCanary() {}

    public static void main(String[] args) throws Exception {
        int code = runCli(args, defaultProbe(), System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int runCli(String[] args, ProviderProbe probe, PrintStream out, PrintStream err)
            throws IOException {
        Options options;
        try {
            options = parseArgs(args);
        } catch (IllegalArgumentException badArgs) {
            err.println(badArgs.getMessage());
            err.println(USAGE);
            return 2;
        }
        if (options.help()) {
            out.println(USAGE);
            return 0;
        }

        List<CheckReport> reports = new ArrayList<>();
        for (ReviewProvider provider : options.providers()) {
            reports.add(verifyProvider(provider, options.model(), options.effort(), probe));
        }

        if (options.json()) {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
        } else {
            for (CheckReport report : reports) {
                if (report.passed()) {
                    out.printf(
                            "PASS provider=%s discovery=%s(%d) verdict=%s findings=%d (%dms)%n",
                            report.provider(),
                            report.discoverySource(),
                            report.discoveredModels(),
                            report.verdict(),
                            report.findingCount(),
                            report.elapsedMs());
                } else {
                    out.printf(
                            "FAIL provider=%s (%dms): %s%n",
                            report.provider(), report.elapsedMs(), report.failure());
                }
            }
        }
        return reports.stream().allMatch(CheckReport::passed) ? 0 : 1;
    }

    static Options parseArgs(String[] args) {
        List<ReviewProvider> providers = List.of(ReviewProvider.COPILOT);
        String model = "";
        String effort = "low";
        boolean json = false;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> help = true;
                case "--json" -> json = true;
                case "--provider" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value after --provider");
                    }
                    providers = parseProviders(args[++i]);
                }
                case "--model" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value after --model");
                    }
                    model = args[++i].trim();
                }
                case "--effort" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value after --effort");
                    }
                    effort = args[++i].trim().toLowerCase(Locale.ROOT);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return new Options(providers, model, effort, json, help);
    }

    private static List<ReviewProvider> parseProviders(String raw) {
        return switch (StringUtils.trimToEmpty(raw).toLowerCase(Locale.ROOT)) {
            case "copilot" -> List.of(ReviewProvider.COPILOT);
            case "claude" -> List.of(ReviewProvider.CLAUDE);
            case "both", "all" -> List.of(ReviewProvider.COPILOT, ReviewProvider.CLAUDE);
            default -> throw new IllegalArgumentException("Unsupported --provider: " + raw);
        };
    }

    static CheckReport verifyProvider(
            ReviewProvider provider, String model, String effort, ProviderProbe probe)
            throws IOException {
        long startedNanos = System.nanoTime();
        ProviderSetupProbe.Result setup = probe.preflight(provider);
        if (!setup.available()) {
            return new CheckReport(
                    provider.getId(),
                    false,
                    setup.binaryStatus(),
                    "N/A",
                    0,
                    "",
                    0,
                    elapsedMillis(startedNanos),
                    "CLI binary unavailable ("
                            + setup.binaryStatus()
                            + "; install CLI then run '"
                            + setup.authCommand()
                            + "')");
        }

        String discoverySource = "N/A";
        int discoveredCount = 0;
        if (provider == ReviewProvider.COPILOT) {
            CopilotModelDiscovery.Result discovery = probe.discoverCopilotModels();
            discoverySource = discovery.source().name();
            discoveredCount = discovery.models().size();
            if (discovery.source() != CopilotModelDiscovery.Source.ACCOUNT
                    || discovery.models().isEmpty()) {
                String reason =
                        StringUtils.defaultIfBlank(
                                discovery.failureReason(),
                                "expected live ACCOUNT catalog, got " + discovery.source());
                return new CheckReport(
                        provider.getId(),
                        false,
                        setup.binaryStatus(),
                        discoverySource,
                        discoveredCount,
                        "",
                        0,
                        elapsedMillis(startedNanos),
                        "Copilot live model discovery failed: " + LogRedactor.redact(reason));
            }
        }

        Path tempRepo = Files.createTempDirectory("pr-pilot-canary-");
        try {
            seedSyntheticCheckout(tempRepo);
            PullRequest pr =
                    new PullRequest(
                            "Add batch mean helper",
                            "https://github.com/acme/widgets/pull/1",
                            "acme",
                            "widgets",
                            1,
                            "Adds BatchAverage.mean delegating to MathOps.divide.",
                            "canary",
                            "2026-11-19T00:00:00Z",
                            false);
            PRReviewRequest request =
                    PRReviewRequest.builder(pr, CANARY_DIFF).builtInRules(false).build();
            ReviewResult result = probe.runCanaryReview(provider, tempRepo, request, model, effort);
            String contractError = validateResultContract(result);
            if (contractError != null) {
                return new CheckReport(
                        provider.getId(),
                        false,
                        setup.binaryStatus(),
                        discoverySource,
                        discoveredCount,
                        result == null ? "" : StringUtils.defaultString(result.getVerdict()),
                        result == null || result.getLineComments() == null
                                ? 0
                                : result.getLineComments().size(),
                        elapsedMillis(startedNanos),
                        contractError);
            }
            return new CheckReport(
                    provider.getId(),
                    true,
                    setup.binaryStatus(),
                    discoverySource,
                    discoveredCount,
                    result.getVerdict(),
                    result.getLineComments().size(),
                    elapsedMillis(startedNanos),
                    "");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message =
                    StringUtils.defaultIfBlank(
                            LogRedactor.redact(failure.getMessage()),
                            LogRedactor.errorType(failure));
            return new CheckReport(
                    provider.getId(),
                    false,
                    setup.binaryStatus(),
                    discoverySource,
                    discoveredCount,
                    "",
                    0,
                    elapsedMillis(startedNanos),
                    message);
        } finally {
            deleteQuietly(tempRepo);
        }
    }

    static String validateResultContract(ReviewResult result) {
        if (result == null) {
            return "Provider returned null ReviewResult";
        }
        if (!VALID_VERDICTS.contains(StringUtils.defaultString(result.getVerdict()))) {
            return "Invalid verdict: " + result.getVerdict();
        }
        if (StringUtils.isBlank(result.getSummary())) {
            return "ReviewResult summary was blank";
        }
        if (result.getLineComments() == null) {
            return "ReviewResult lineComments was null";
        }
        for (LineComment comment : result.getLineComments()) {
            if (!CANARY_FILE.equals(comment.getFile())
                    || comment.getLine() < 3
                    || comment.getLine() > 5) {
                return "Unanchored finding survived validation at "
                        + comment.getFile()
                        + ":"
                        + comment.getLine();
            }
        }
        return null;
    }

    private static void seedSyntheticCheckout(Path repoDir) throws IOException {
        Path src = repoDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(
                repoDir.resolve(CANARY_HELPER_FILE),
                "package acme;\n"
                        + "public final class MathOps {\n"
                        + "    public static int divide(int sum, int count) {\n"
                        + "        return sum / count;\n"
                        + "    }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                repoDir.resolve(CANARY_FILE),
                "package acme;\n"
                        + "public final class BatchAverage {\n"
                        + "    public static int mean(int sum, int count) {\n"
                        + "        return MathOps.divide(sum, count);\n"
                        + "    }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
    }

    private static ProviderProbe defaultProbe() {
        ProviderSetupProbe setupProbe = new ProviderSetupProbe();
        return new ProviderProbe() {
            @Override
            public ProviderSetupProbe.Result preflight(ReviewProvider provider) {
                return setupProbe.probe(provider);
            }

            @Override
            public CopilotModelDiscovery.Result discoverCopilotModels() {
                CopilotModelDiscovery.invalidate();
                return CopilotModelDiscovery.refresh();
            }

            @Override
            public ReviewResult runCanaryReview(
                    ReviewProvider provider,
                    Path repoDir,
                    PRReviewRequest request,
                    String model,
                    String effort)
                    throws Exception {
                if (provider == ReviewProvider.COPILOT) {
                    CopilotService copilot = new CopilotService(repoDir.toString());
                    return ReviewPipelineService.forCopilot(copilot, model, effort, false, "")
                            .review(request, false, false, false, ignored -> {}, null);
                }
                ClaudeService claude = new ClaudeService(repoDir.toString());
                return ReviewPipelineService.forClaude(claude, model)
                        .review(request, false, false, false, ignored -> {}, null);
            }
        };
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static void deleteQuietly(Path root) {
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                                throws IOException {
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {
            // Best-effort cleanup of temp canary checkout.
        }
    }
}
