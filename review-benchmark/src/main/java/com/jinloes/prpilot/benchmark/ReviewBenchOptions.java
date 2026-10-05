package com.jinloes.prpilot.benchmark;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** Command-line options for {@link ReviewBenchRunner}. */
record ReviewBenchOptions(
        Path corpus,
        Path out,
        String set,
        int rounds,
        int limit,
        List<String> only,
        Path reposDir,
        String provider,
        String model,
        String effort,
        String configDir,
        String secondReviewerModel,
        boolean selfCritique,
        boolean supervisor,
        boolean chunked,
        boolean callSites,
        boolean includeNotes,
        boolean verbose,
        boolean help) {

    private static final Set<String> PROVIDERS = Set.of("claude", "copilot");
    private static final Set<String> SETS =
            Set.of(ReviewBenchTask.SET_TEST, ReviewBenchTask.SET_FULL);
    private static final Set<String> VALUE_OPTIONS =
            Set.of(
                    "--corpus",
                    "--out",
                    "--set",
                    "--rounds",
                    "--limit",
                    "--only",
                    "--repos-dir",
                    "--provider",
                    "--model",
                    "--effort",
                    "--config-dir",
                    "--second-reviewer");

    static final String USAGE =
            """
            Usage: ./gradlew :review-benchmark:reviewBench --args="--corpus DIR --out DIR [options]"

            Reviews ReviewBench pull requests with PR Pilot and writes findings in ReviewBench's
            judging format. Normally run through scripts/reviewbench.mjs, which also fetches the
            corpus and runs the judge.

              --corpus DIR            ReviewBench repository checkout
              --out DIR               Run directory; findings go to DIR/findings/round-N/
              --set test|full         25-PR test set or the full 219-PR set (default test)
              --rounds N              Review every PR N times (default 1)
              --limit N               Review only the first N PRs
              --only KEY              Review only this task (golden-file stem; repeatable)
              --repos-dir DIR         Clone cache (default build/reviewbench/repos)
              --provider NAME         claude or copilot (default copilot)
              --model ID              Review model (provider default when omitted)
              --effort LEVEL          Reasoning effort
              --config-dir DIR        Provider config directory
              --second-reviewer ID    Also run a second reviewer with this model
              --no-self-critique      Skip the self-critique pass
              --supervisor            Enable the review supervisor
              --chunked               Review large diffs in per-file chunks
              --no-call-sites         Omit base-commit call-site context
              --include-notes         Also submit "note" comments as findings
              --verbose               Print review status updates
              --help                  Show this help

            Existing findings files are kept, so rerunning with the same --out resumes.
            """;

    static ReviewBenchOptions parse(List<String> args) {
        Path corpus = null;
        Path out = null;
        String set = ReviewBenchTask.SET_TEST;
        int rounds = 1;
        int limit = 0;
        List<String> only = new ArrayList<>();
        Path reposDir = Path.of("build", "reviewbench", "repos");
        String provider = "copilot";
        String model = "";
        String effort = "";
        String configDir = "";
        String second = "";
        boolean selfCritique = true;
        boolean supervisor = false;
        boolean chunked = false;
        boolean callSites = true;
        boolean includeNotes = false;
        boolean verbose = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--help", "-h" -> {
                    return new ReviewBenchOptions(
                            null, null, set, 1, 0, List.of(), reposDir, provider, "", "", "", "",
                            true, false, false, true, false, false, true);
                }
                case "--no-self-critique" -> selfCritique = false;
                case "--supervisor" -> supervisor = true;
                case "--chunked" -> chunked = true;
                case "--no-call-sites" -> callSites = false;
                case "--include-notes" -> includeNotes = true;
                case "--verbose" -> verbose = true;
                default -> {
                    String value = value(args, ++i, arg);
                    switch (arg) {
                        case "--corpus" -> corpus = Path.of(value);
                        case "--out" -> out = Path.of(value);
                        case "--set" -> set = value.toLowerCase(Locale.ROOT);
                        case "--rounds" -> rounds = positive(value, "--rounds");
                        case "--limit" -> limit = positive(value, "--limit");
                        case "--only" -> only.add(value);
                        case "--repos-dir" -> reposDir = Path.of(value);
                        case "--provider" -> provider = value.toLowerCase(Locale.ROOT);
                        case "--model" -> model = value;
                        case "--effort" -> effort = value;
                        case "--config-dir" -> configDir = value;
                        case "--second-reviewer" -> second = value;
                        default -> throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                }
            }
        }
        if (corpus == null) throw new IllegalArgumentException("--corpus is required.");
        if (out == null) throw new IllegalArgumentException("--out is required.");
        if (!SETS.contains(set)) throw new IllegalArgumentException("--set must be test or full.");
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("--provider must be claude or copilot.");
        }
        return new ReviewBenchOptions(
                corpus,
                out,
                set,
                rounds,
                limit,
                List.copyOf(only),
                reposDir,
                provider,
                model,
                effort,
                configDir,
                second,
                selfCritique,
                supervisor,
                chunked,
                callSites,
                includeNotes,
                verbose,
                false);
    }

    /** Applies {@code --only} and then {@code --limit}. */
    List<ReviewBenchTask> select(List<ReviewBenchTask> tasks) {
        List<ReviewBenchTask> selected =
                only.isEmpty()
                        ? tasks
                        : tasks.stream().filter(task -> only.contains(task.key())).toList();
        if (!only.isEmpty() && selected.size() != only.stream().distinct().count()) {
            List<String> found = selected.stream().map(ReviewBenchTask::key).toList();
            List<String> missing = only.stream().filter(key -> !found.contains(key)).toList();
            throw new IllegalArgumentException("Not in the " + set + " set: " + missing);
        }
        return limit > 0 && limit < selected.size() ? selected.subList(0, limit) : selected;
    }

    private static String value(List<String> args, int index, String option) {
        if (!VALUE_OPTIONS.contains(option)) {
            throw new IllegalArgumentException("Unknown option: " + option);
        }
        if (index >= args.size() || StringUtils.isBlank(args.get(index))) {
            throw new IllegalArgumentException(option + " needs a value.");
        }
        return args.get(index).strip();
    }

    private static int positive(String value, String option) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 1) return parsed;
        } catch (NumberFormatException ignored) {
            // Reported below with the option name.
        }
        throw new IllegalArgumentException(option + " must be a positive integer.");
    }
}
