package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministically identifies coverage gaps in a reported inspection ledger: high-risk changed
 * hunks that were not inspected, and changed files the ledger never mentions at all.
 */
final class ReviewCoverageAnalyzer {
    private static final int MAX_GAPS = 12;

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
                                100));
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
                                                        50)));
            }
        }
        return gaps.stream()
                .sorted(
                        Comparator.comparingInt(CoverageGap::priority)
                                .reversed()
                                .thenComparing(CoverageGap::path)
                                .thenComparingInt(CoverageGap::newStart))
                .limit(MAX_GAPS)
                .toList();
    }

    private static boolean mentioned(InspectionManifest.FileTarget file, InspectionLedger ledger) {
        return ledger.inspectedTargetIds().contains(file.id())
                || file.hunks().stream()
                        .anyMatch(hunk -> ledger.inspectedTargetIds().contains(hunk.id()));
    }
}
