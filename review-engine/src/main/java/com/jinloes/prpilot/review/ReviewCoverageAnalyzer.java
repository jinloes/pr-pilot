package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Deterministically identifies coverage gaps in a reported inspection ledger: high-risk changed
 * hunks that were not inspected, and changed files the ledger never mentions at all.
 *
 * <p>Only hunk gaps are capped. Every unmentioned changed file is returned, because the supervisor
 * re-reviews each one in full rather than choosing among them — a file the reviewer skipped is the
 * likeliest place for a missed finding.
 *
 * <p>When a provider omits the ledger on a multi-file diff, whole-file gaps are never guessed, so a
 * missing field cannot trigger a full re-review. Instead, at most {@link
 * #MAX_OMITTED_LEDGER_HUNK_GAPS} high-risk changed hunks in files that received zero baseline
 * findings are surfaced for one bounded follow-up pass.
 */
final class ReviewCoverageAnalyzer {
    private static final int MAX_HUNK_GAPS = 12;
    static final int MAX_OMITTED_LEDGER_HUNK_GAPS = 3;

    List<CoverageGap> findGaps(InspectionManifest manifest, InspectionLedger ledger) {
        return findGaps(manifest, ledger, Set.of());
    }

    List<CoverageGap> findGaps(
            InspectionManifest manifest,
            InspectionLedger ledger,
            Set<String> baselineFindingFiles) {
        if (manifest == null || ledger == null) {
            return List.of();
        }
        if (!ledger.reported()) {
            return omittedLedgerHighRiskGaps(
                    manifest, baselineFindingFiles == null ? Set.of() : baselineFindingFiles);
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

    private static List<CoverageGap> omittedLedgerHighRiskGaps(
            InspectionManifest manifest, Set<String> baselineFindingFiles) {
        if (manifest.files().size() < 2) {
            return List.of();
        }
        List<InspectionManifest.HunkTarget> candidates = new ArrayList<>();
        for (InspectionManifest.FileTarget file : manifest.files()) {
            if (baselineFindingFiles.contains(file.path())) {
                continue;
            }
            for (InspectionManifest.HunkTarget hunk : file.hunks()) {
                if (hunk.highRisk() && !hunk.changedNewLines().isEmpty()) {
                    candidates.add(hunk);
                }
            }
        }
        candidates.sort(
                Comparator.comparing(InspectionManifest.HunkTarget::path)
                        .thenComparingInt(InspectionManifest.HunkTarget::newStart));
        int limit = Math.min(candidates.size(), MAX_OMITTED_LEDGER_HUNK_GAPS);
        List<CoverageGap> gaps = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            InspectionManifest.HunkTarget hunk = candidates.get(i);
            gaps.add(
                    new CoverageGap(
                            "G%03d".formatted(i + 1),
                            hunk.id(),
                            hunk.path(),
                            hunk.newStart(),
                            "High-risk changed hunk in an uncommented file when the inspection"
                                    + " ledger was omitted.",
                            CoverageGap.HUNK_PRIORITY));
        }
        return List.copyOf(gaps);
    }

    private static boolean mentioned(InspectionManifest.FileTarget file, InspectionLedger ledger) {
        return ledger.inspectedTargetIds().contains(file.id())
                || file.hunks().stream()
                        .anyMatch(hunk -> ledger.inspectedTargetIds().contains(hunk.id()));
    }
}
