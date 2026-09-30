package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/** The benchmark's result, written as a Markdown summary and a JSON file for later comparison. */
record BenchmarkReport(String generatedAt, Settings settings, Totals totals, List<PrResult> prs) {

    static final String STATUS_SCORED = "scored";
    static final String STATUS_SKIPPED = "skipped";
    static final String STATUS_FAILED = "failed";
    private static final int EXCERPT_CHARS = 240;

    record Settings(
            String provider,
            String model,
            String effort,
            String secondReviewerModel,
            boolean selfCritique,
            boolean supervisor,
            boolean chunked,
            boolean callSites,
            String judge,
            String judgeModel,
            int lineWindow) {}

    record Match(Finding mae, Finding prPilot) {}

    record Miss(Finding mae, String reason) {}

    record PrResult(
            String pr,
            String url,
            String status,
            String message,
            String reviewedCommit,
            long reviewMillis,
            boolean diffTruncated,
            int maeFindings,
            int prPilotFindings,
            List<Match> matches,
            List<Miss> misses,
            List<Finding> prPilotOnly) {

        static PrResult notScored(PrRef pr, String url, String status, String message) {
            return new PrResult(
                    pr.label(),
                    url,
                    status,
                    message,
                    "",
                    0,
                    false,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    List.of());
        }
    }

    record Totals(
            int prs,
            int scored,
            int skipped,
            int failed,
            int maeFindings,
            int matched,
            int prPilotFindings,
            double recall,
            double meanPrRecall) {

        static Totals of(List<PrResult> prs) {
            List<PrResult> scored =
                    prs.stream().filter(pr -> STATUS_SCORED.equals(pr.status())).toList();
            int mae = scored.stream().mapToInt(PrResult::maeFindings).sum();
            int matched = scored.stream().mapToInt(pr -> pr.matches().size()).sum();
            double mean =
                    scored.stream()
                            .filter(pr -> pr.maeFindings() > 0)
                            .mapToDouble(pr -> (double) pr.matches().size() / pr.maeFindings())
                            .average()
                            .orElse(0);
            return new Totals(
                    prs.size(),
                    scored.size(),
                    count(prs, STATUS_SKIPPED),
                    count(prs, STATUS_FAILED),
                    mae,
                    matched,
                    scored.stream().mapToInt(PrResult::prPilotFindings).sum(),
                    mae == 0 ? 0 : (double) matched / mae,
                    mean);
        }

        private static int count(List<PrResult> prs, String status) {
            return (int) prs.stream().filter(pr -> status.equals(pr.status())).count();
        }
    }

    static BenchmarkReport of(String generatedAt, Settings settings, List<PrResult> prs) {
        return new BenchmarkReport(generatedAt, settings, Totals.of(prs), List.copyOf(prs));
    }

    String markdown() {
        StringBuilder md = new StringBuilder("# PR Pilot recall against Mae\n\n");
        md.append("Generated ")
                .append(generatedAt)
                .append(". Provider `")
                .append(settings.provider());
        md.append("`, model `").append(orDefault(settings.model())).append('`');
        if (StringUtils.isNotBlank(settings.secondReviewerModel())) {
            md.append(", second reviewer `").append(settings.secondReviewerModel()).append('`');
        }
        if (settings.chunked()) md.append(", chunked review");
        if (!settings.callSites()) md.append(", no call sites");
        md.append(", judge `").append(settings.judge());
        if ("llm".equals(settings.judge())) {
            md.append(" (").append(orDefault(settings.judgeModel())).append(')');
        }
        md.append("`, line window ").append(settings.lineWindow()).append(".\n\n");

        md.append("| Metric | Value |\n|---|---|\n");
        row(
                md,
                "Recall (matched / Mae findings)",
                percent(totals.recall())
                        + " ("
                        + totals.matched()
                        + " / "
                        + totals.maeFindings()
                        + ")");
        row(md, "Mean per-PR recall", percent(totals.meanPrRecall()));
        row(md, "PR Pilot findings", Integer.toString(totals.prPilotFindings()));
        row(
                md,
                "PRs scored / skipped / failed",
                totals.scored() + " / " + totals.skipped() + " / " + totals.failed());

        md.append("\n## Per pull request\n\n");
        md.append("| PR | Status | Mae | Matched | Recall | PR Pilot | Review time |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (PrResult pr : prs) {
            boolean scored = STATUS_SCORED.equals(pr.status());
            md.append("| [")
                    .append(pr.pr())
                    .append("](")
                    .append(pr.url())
                    .append(") | ")
                    .append(pr.status())
                    .append(pr.diffTruncated() ? " (diff truncated)" : "")
                    .append(" | ")
                    .append(scored ? pr.maeFindings() : "-")
                    .append(" | ")
                    .append(scored ? pr.matches().size() : "-")
                    .append(" | ")
                    .append(
                            scored && pr.maeFindings() > 0
                                    ? percent((double) pr.matches().size() / pr.maeFindings())
                                    : "-")
                    .append(" | ")
                    .append(scored ? pr.prPilotFindings() : "-")
                    .append(" | ")
                    .append(scored ? (pr.reviewMillis() / 1000) + "s" : "-")
                    .append(" |\n");
        }

        md.append("\n## Missed Mae findings\n");
        boolean anyMiss = false;
        for (PrResult pr : prs) {
            if (pr.misses().isEmpty()) continue;
            anyMiss = true;
            md.append("\n### ").append(pr.pr()).append("\n\n");
            for (Miss miss : pr.misses()) {
                md.append("- `")
                        .append(location(miss.mae()))
                        .append("` — ")
                        .append(missReason(miss.reason()))
                        .append(": ")
                        .append(excerpt(miss.mae().body()))
                        .append('\n');
            }
        }
        if (!anyMiss) md.append("\nNone.\n");

        List<PrResult> notScored =
                prs.stream().filter(pr -> !STATUS_SCORED.equals(pr.status())).toList();
        if (!notScored.isEmpty()) {
            md.append("\n## Skipped or failed\n\n");
            for (PrResult pr : notScored) {
                md.append("- ")
                        .append(pr.pr())
                        .append(" (")
                        .append(pr.status())
                        .append("): ")
                        .append(pr.message())
                        .append('\n');
            }
        }
        return md.toString();
    }

    /**
     * Writes {@code <stem>.md} and {@code <stem>.json} into {@code dir}; returns the Markdown path.
     */
    Path write(Path dir, String stem, ObjectMapper mapper) throws IOException {
        Files.createDirectories(dir);
        Path json = dir.resolve(stem + ".json");
        mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), this);
        Path md = dir.resolve(stem + ".md");
        Files.writeString(md, markdown());
        return md;
    }

    private static String missReason(String reason) {
        return FindingMatcher.NO_NEARBY_FINDING.equals(reason)
                ? "no PR Pilot finding nearby"
                : "nearby finding judged different";
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    private static String location(Finding finding) {
        return finding.line() > 0 ? finding.path() + ":" + finding.line() : finding.path();
    }

    static String excerpt(String body) {
        String oneLine = StringUtils.normalizeSpace(StringUtils.defaultString(body));
        return StringUtils.abbreviate(oneLine.replace("|", "\\|"), EXCERPT_CHARS);
    }

    private static String orDefault(String model) {
        return StringUtils.isBlank(model) ? "default" : model;
    }
}
