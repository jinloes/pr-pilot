package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministically identifies coverage gaps in a reported inspection ledger: high-risk changed
 * hunks that were not inspected, and changed files the ledger never mentions at all.
 *
 * <p>Only hunk gaps are capped. Every unmentioned changed file is returned, because the supervisor
 * re-reviews each one in full rather than choosing among them — a file the reviewer skipped is the
 * likeliest place for a missed finding.
 */
final class ReviewCoverageAnalyzer {
    private static final int MAX_HUNK_GAPS = 12;

    List<CoverageGap> findGaps(InspectionManifest manifest, InspectionLedger ledger) {
        if (manifest == null || ledger == null || !ledger.reported()) {
            return List.of();
        }
        List<CoverageGap> gaps = new ArrayList<>();
        for (InspectionManifest.FileTarget file : manifest.files()) {
            for (InspectionManifest.HunkTarget hunk : file.hunks()) {
                if (!hunk.highRisk()
                        || hunk.changedNewLines().isEmpty()
                        || ledger.inspectedTargetIds().contains(hunk.id())) {
                    continue;
                }
                gaps.add(
                        new CoverageGap(
                                "G%03d".formatted(gaps.size() + 1),
                                hunk.id(),
                                hunk.path(),
                                hunk.newStart(),
                                "High-risk changed hunk was not recorded as inspected.",
                                CoverageGap.HUNK_PRIORITY));
            }
            if (!mentioned(file, ledger)) {
                file.hunks().stream()
                        .filter(hunk -> !hunk.changedNewLines().isEmpty())
                        .findFirst()
                        .ifPresent(
                                firstChanged ->
                                        gaps.add(
                                                new CoverageGap(
                                                        "G%03d".formatted(gaps.size() + 1),
                                                        file.id(),
                                                        file.path(),
                                                        firstChanged.newStart(),
                                                        "Changed file was not recorded as"
                                                                + " inspected.",
                                                        CoverageGap.FILE_PRIORITY)));
            }
        }
        Comparator<CoverageGap> byLocation =
                Comparator.comparing(CoverageGap::path).thenComparingInt(CoverageGap::newStart);
        List<CoverageGap> ranked = new ArrayList<>();
        gaps.stream()
                .filter(gap -> !gap.wholeFile())
                .sorted(byLocation)
                .limit(MAX_HUNK_GAPS)
                .forEach(ranked::add);
        gaps.stream().filter(CoverageGap::wholeFile).sorted(byLocation).forEach(ranked::add);
        return List.copyOf(ranked);
    }

    private static boolean mentioned(InspectionManifest.FileTarget file, InspectionLedger ledger) {
        return ledger.inspectedTargetIds().contains(file.id())
                || file.hunks().stream()
                        .anyMatch(hunk -> ledger.inspectedTargetIds().contains(hunk.id()));
    }
}
