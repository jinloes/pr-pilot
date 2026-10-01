package com.jinloes.prpilot.benchmark;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** Command-line options for {@link ReviewBenchmark}. */
record BenchmarkOptions(
        Path prsFile,
        Path reposRoot,
        String provider,
        String model,
        String effort,
        String configDir,
        String secondReviewerModel,
        boolean selfCritique,
        boolean supervisor,
        boolean chunked,
        boolean callSites,
        String judge,
        String judgeModel,
        int lineWindow,
        int repeat,
        String maeLoginPrefix,
        Path outDir,
        boolean verbose,
        boolean help) {

    static final String JUDGE_LLM = "llm";
    static final String JUDGE_LOCATION = "location";
    static final int DEFAULT_LINE_WINDOW = 10;
    private static final Set<String> PROVIDERS = Set.of("claude", "copilot");
    private static final Set<String> VALUE_OPTIONS =
            Set.of(
                    "--prs",
                    "--repos-root",
                    "--provider",
                    "--model",
                    "--effort",
                    "--config-dir",
                    "--second-reviewer",
                    "--judge",
                    "--judge-model",
                    "--line-window",
                    "--repeat",
                    "--mae-login-prefix",
                    "--out");

    static final String USAGE =
            """
            Usage: ./gradlew :review-benchmark:reviewBenchmark --args="--prs FILE --repos-root DIR --provider claude|copilot [options]"

            Reviews each pull request at the commit Mae reviewed and reports how many of Mae's
            inline findings PR Pilot also raised.

              --prs FILE              Pull request URLs, one per line (# comments allowed)
              --repos-root DIR        Directory holding local clones as <repo> or <owner>/<repo>
              --provider NAME         claude or copilot
              --model ID              Review model (provider default when omitted)
              --effort LEVEL          Reasoning effort
              --config-dir DIR        Provider config directory
              --second-reviewer ID    Also run a second reviewer with this model
              --no-self-critique      Skip the self-critique pass
              --supervisor            Enable the review supervisor
              --chunked               Review large diffs in per-file chunks
              --no-call-sites         Omit base-commit call-site context
              --judge llm|location    How matches are decided (default llm)
              --judge-model ID        Judge model (defaults to --model)
              --line-window N         Max line distance for a candidate match (default 10)
              --repeat N              Run the whole benchmark N times and summarize (default 1)
              --mae-login-prefix P    Reviewer login prefix (default svc-mae)
              --out DIR               Report directory (default build/review-benchmark)
              --verbose               Print review status updates
              --help                  Show this help
            """;

    static BenchmarkOptions parse(List<String> args) {
        Path prs = null;
        Path repos = null;
        String provider = null;
        String model = "";
        String effort = "";
        String configDir = "";
        String second = "";
        boolean selfCritique = true;
        boolean supervisor = false;
        boolean chunked = false;
        boolean callSites = true;
        String judge = JUDGE_LLM;
        String judgeModel = "";
        int window = DEFAULT_LINE_WINDOW;
        int repeat = 1;
        String prefix = MaeComments.DEFAULT_LOGIN_PREFIX;
        Path out = Path.of("build", "review-benchmark");
        boolean verbose = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--help", "-h" -> {
                    return new BenchmarkOptions(
                            null,
                            null,
                            "",
                            "",
                            "",
                            "",
                            "",
                            true,
                            false,
                            false,
                            true,
                            JUDGE_LLM,
                            "",
                            DEFAULT_LINE_WINDOW,
                            1,
                            prefix,
                            out,
                            false,
                            true);
                }
                case "--no-self-critique" -> selfCritique = false;
                case "--supervisor" -> supervisor = true;
                case "--chunked" -> chunked = true;
                case "--no-call-sites" -> callSites = false;
                case "--verbose" -> verbose = true;
                default -> {
                    String value = value(args, ++i, arg);
                    switch (arg) {
                        case "--prs" -> prs = Path.of(value);
                        case "--repos-root" -> repos = Path.of(value);
                        case "--provider" -> provider = value.toLowerCase(Locale.ROOT);
                        case "--model" -> model = value;
                        case "--effort" -> effort = value;
                        case "--config-dir" -> configDir = value;
                        case "--second-reviewer" -> second = value;
                        case "--judge" -> judge = value;
                        case "--judge-model" -> judgeModel = value;
                        case "--line-window" -> window = lineWindow(value);
                        case "--repeat" -> repeat = repeat(value);
                        case "--mae-login-prefix" -> prefix = value;
                        case "--out" -> out = Path.of(value);
                        default -> throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                }
            }
        }
        if (prs == null) throw new IllegalArgumentException("--prs is required.");
        if (repos == null) throw new IllegalArgumentException("--repos-root is required.");
        if (provider == null || !PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("--provider must be claude or copilot.");
        }
        if (!JUDGE_LLM.equals(judge) && !JUDGE_LOCATION.equals(judge)) {
            throw new IllegalArgumentException("--judge must be llm or location.");
        }
        return new BenchmarkOptions(
                prs,
                repos,
                provider,
                model,
                effort,
                configDir,
                second,
                selfCritique,
                supervisor,
                chunked,
                callSites,
                judge,
                StringUtils.defaultIfBlank(judgeModel, model),
                window,
                repeat,
                prefix,
                out,
                verbose,
                false);
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

    private static int lineWindow(String value) {
        try {
            int window = Integer.parseInt(value);
            if (window >= 0) return window;
        } catch (NumberFormatException ignored) {
            // Reported below with the option name.
        }
        throw new IllegalArgumentException("--line-window must be a non-negative integer.");
    }

    private static int repeat(String value) {
        try {
            int repeat = Integer.parseInt(value);
            if (repeat >= 1) return repeat;
        } catch (NumberFormatException ignored) {
            // Reported below with the option name.
        }
        throw new IllegalArgumentException("--repeat must be a positive integer.");
    }
}
