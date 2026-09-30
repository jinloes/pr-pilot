package com.jinloes.prpilot.benchmark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scores PR Pilot's findings against Mae's.
 *
 * <p>Location narrows, the judge decides: only pairs on the same file within {@code lineWindow}
 * lines are shown to the judge, and each finding on either side can match at most once, so one
 * broad comment cannot claim several of Mae's findings.
 */
final class FindingMatcher {
    static final String NO_NEARBY_FINDING = "no_nearby_finding";
    static final String JUDGED_DIFFERENT = "judged_different";

    record Pair(Finding expected, Finding actual) {
        int distance() {
            return expected.line() == 0 || actual.line() == 0
                    ? 0
                    : Math.abs(expected.line() - actual.line());
        }
    }

    record Miss(Finding expected, String reason) {}

    record Outcome(List<Pair> matches, List<Miss> misses, List<Finding> extras) {}

    /** Decides which candidate pairs describe the same issue, best match first. */
    interface Judge {
        List<Pair> sameIssue(List<Pair> candidates) throws IOException, InterruptedException;
    }

    /** Location-only judging: every candidate matches, nearest first. */
    static final Judge LOCATION_ONLY =
            candidates ->
                    candidates.stream().sorted(Comparator.comparingInt(Pair::distance)).toList();

    private FindingMatcher() {}

    static List<Pair> candidates(List<Finding> expected, List<Finding> actual, int lineWindow) {
        List<Pair> pairs = new ArrayList<>();
        for (Finding mae : expected) {
            for (Finding pilot : actual) {
                Pair pair = new Pair(mae, pilot);
                if (normalizePath(mae.path()).equals(normalizePath(pilot.path()))
                        && pair.distance() <= lineWindow) {
                    pairs.add(pair);
                }
            }
        }
        return pairs;
    }

    static Outcome match(List<Finding> expected, List<Finding> actual, int lineWindow, Judge judge)
            throws IOException, InterruptedException {
        List<Pair> candidates = candidates(expected, actual, lineWindow);
        Set<Pair> allowed = new HashSet<>(candidates);
        List<Pair> judged = candidates.isEmpty() ? List.of() : judge.sameIssue(candidates);

        Set<String> usedExpected = new HashSet<>();
        Set<String> usedActual = new HashSet<>();
        List<Pair> matches = new ArrayList<>();
        for (Pair pair : judged) {
            if (!allowed.contains(pair)
                    || usedExpected.contains(pair.expected().id())
                    || usedActual.contains(pair.actual().id())) {
                continue;
            }
            matches.add(pair);
            usedExpected.add(pair.expected().id());
            usedActual.add(pair.actual().id());
        }

        Set<String> nearby = new LinkedHashSet<>();
        candidates.forEach(pair -> nearby.add(pair.expected().id()));
        List<Miss> misses =
                expected.stream()
                        .filter(mae -> !usedExpected.contains(mae.id()))
                        .map(
                                mae ->
                                        new Miss(
                                                mae,
                                                nearby.contains(mae.id())
                                                        ? JUDGED_DIFFERENT
                                                        : NO_NEARBY_FINDING))
                        .toList();
        List<Finding> extras =
                actual.stream().filter(pilot -> !usedActual.contains(pilot.id())).toList();
        return new Outcome(List.copyOf(matches), misses, extras);
    }

    static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./") || normalized.startsWith("/")) {
            normalized = normalized.substring(normalized.startsWith("./") ? 2 : 1);
        }
        return normalized;
    }
}
