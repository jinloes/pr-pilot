package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What happened inside one review, kept beside its findings for {@code
 * scripts/reviewbench-report.mjs}: status lines carrying finding counts, and findings the critique
 * dropped. A golden finding missed near a dropped one points at validation rather than detection.
 * Written outside the findings directory because the judge reads every file there.
 */
record ReviewBenchDiagnostics(List<String> stages, List<Dropped> dropped) {

    record Dropped(String file, int line, String message) {}

    static ReviewBenchDiagnostics of(List<String> stages, List<Finding> dropped) {
        return new ReviewBenchDiagnostics(
                List.copyOf(stages),
                dropped.stream()
                        .map(
                                finding ->
                                        new Dropped(
                                                ReviewBenchFindings.normalizePath(finding.path()),
                                                finding.line(),
                                                finding.body()))
                        .toList());
    }

    void write(Path file, ObjectMapper mapper) throws IOException {
        ReviewBenchFindings.writeJson(file, this, mapper);
    }
}
