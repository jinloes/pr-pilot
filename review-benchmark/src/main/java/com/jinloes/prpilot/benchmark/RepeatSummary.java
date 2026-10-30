package com.jinloes.prpilot.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Summarizes repeated benchmark runs over the same pull requests. Model output varies between runs
 * of an unchanged prompt, so one run cannot tell a prompt change from noise; the spread across runs
 * shows how large a difference has to be before it means anything.
 */
record RepeatSummary(List<BenchmarkReport> runs) {

    RepeatSummary {
        if (runs.isEmpty()) throw new IllegalArgumentException("No benchmark runs to summarize.");
        runs = List.copyOf(runs);
    }

    double meanRecall() {
        return runs.stream().mapToDouble(run -> run.totals().recall()).average().orElse(0);
    }

    double minRecall() {
        return runs.stream().mapToDouble(run -> run.totals().recall()).min().orElse(0);
    }

    double maxRecall() {
        return runs.stream().mapToDouble(run -> run.totals().recall()).max().orElse(0);
    }

    /**
     * Matched reference findings per PR label and run, in the order PRs first appear. A run in
     * which the PR was not scored holds {@code null}.
     */
    Map<String, List<Integer>> matchedByPr() {
        Map<String, List<Integer>> byPr = new LinkedHashMap<>();
        for (int i = 0; i < runs.size(); i++) {
            for (BenchmarkReport.PrResult pr : runs.get(i).prs()) {
                List<Integer> counts =
                        byPr.computeIfAbsent(pr.pr(), key -> blankCounts(runs.size()));
                if (BenchmarkReport.STATUS_SCORED.equals(pr.status())) {
                    counts.set(i, pr.matches().size());
                }
            }
        }
        return byPr;
    }

    String markdown() {
        StringBuilder md = new StringBuilder("# PR Pilot recall across repeated runs\n\n");
        md.append("| Run | Recall | Recall on complete diffs |\n|---|---|---|\n");
        for (int i = 0; i < runs.size(); i++) {
            BenchmarkReport.Totals totals = runs.get(i).totals();
            md.append("| ")
                    .append(i + 1)
                    .append(" | ")
                    .append(ratio(totals.recall(), totals.matched(), totals.referenceFindings()))
                    .append(" | ")
                    .append(
                            ratio(
                                    totals.completeDiffRecall(),
                                    totals.completeDiffMatched(),
                                    totals.completeDiffReferenceFindings()))
                    .append(" |\n");
        }
        md.append("\nMean recall ")
                .append(BenchmarkReport.percent(meanRecall()))
                .append(" (min ")
                .append(BenchmarkReport.percent(minRecall()))
                .append(", max ")
                .append(BenchmarkReport.percent(maxRecall()))
                .append(
                        ").\n\n## Matched reference findings per pull request\n\n| PR | Reference |");
        for (int i = 0; i < runs.size(); i++) md.append(" Run ").append(i + 1).append(" |");
        md.append(" Mean |\n|---|---|");
        md.append("---|".repeat(runs.size() + 1)).append('\n');
        Map<String, Integer> referenceByPr = referenceByPr();
        matchedByPr()
                .forEach(
                        (pr, counts) -> {
                            md.append("| ").append(pr).append(" | ");
                            Integer reference = referenceByPr.get(pr);
                            md.append(reference == null ? "–" : reference.toString()).append(" |");
                            for (Integer count : counts) {
                                md.append(' ')
                                        .append(count == null ? "–" : count.toString())
                                        .append(" |");
                            }
                            md.append(' ').append(mean(counts)).append(" |\n");
                        });
        return md.toString();
    }

    Path write(Path dir, String stem) throws IOException {
        Files.createDirectories(dir);
        Path md = dir.resolve(stem + ".md");
        Files.writeString(md, markdown());
        return md;
    }

    private Map<String, Integer> referenceByPr() {
        Map<String, Integer> reference = new LinkedHashMap<>();
        for (BenchmarkReport run : runs) {
            for (BenchmarkReport.PrResult pr : run.prs()) {
                if (BenchmarkReport.STATUS_SCORED.equals(pr.status())) {
                    reference.putIfAbsent(pr.pr(), pr.referenceFindings());
                }
            }
        }
        return reference;
    }

    private static List<Integer> blankCounts(int size) {
        List<Integer> counts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) counts.add(null);
        return counts;
    }

    private static String mean(List<Integer> counts) {
        return counts.stream()
                .filter(count -> count != null)
                .mapToInt(Integer::intValue)
                .average()
                .stream()
                .mapToObj(value -> String.format(Locale.ROOT, "%.1f", value))
                .findFirst()
                .orElse("–");
    }

    private static String ratio(double recall, int matched, int total) {
        return BenchmarkReport.percent(recall) + " (" + matched + " / " + total + ")";
    }
}
