package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DiffCoverageTest {

    private static final String GOLDEN = "/diff-coverage/trailer.golden.txt";

    @Nested
    class SharedGoldenFixture {

        static Stream<Arguments> goldenCases() throws IOException {
            return loadGolden().stream().map(golden -> Arguments.of(golden.name(), golden));
        }

        /**
         * The webview parser reads the same fixture, so a verdict here is also the webview's
         * verdict. Accepted cases must round-trip exactly: body plus rebuilt trailer is the input.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("goldenCases")
        void splitReachesTheSharedVerdict(String name, GoldenCase golden) {
            DiffCoverage.Split split = DiffCoverage.split(golden.input());

            assertThat(split.body()).isEqualTo(golden.body());
            assertThat(split.coverage()).isEqualTo(golden.coverage());
            if (golden.coverage() == DiffCoverage.NONE) {
                assertThat(split.body()).isEqualTo(golden.input());
            } else {
                assertThat(split.body() + split.coverage().trailer()).isEqualTo(golden.input());
            }
        }

        /** Guards against a fixture-reader bug that would make the parameterized test vacuous. */
        @Test
        void fixtureCoversAcceptedRejectedAndNegativeControlCases() throws IOException {
            List<GoldenCase> cases = loadGolden();
            assertThat(cases).hasSizeGreaterThan(30);
            assertThat(cases).anyMatch(golden -> golden.coverage() != DiffCoverage.NONE);
            assertThat(cases)
                    .filteredOn(golden -> golden.name().equals("mid-body-header-is-not-a-trailer"))
                    .singleElement()
                    .satisfies(golden -> assertThat(golden.coverage()).isSameAs(DiffCoverage.NONE));
        }
    }

    @Nested
    class Splitting {

        @Test
        void midBodyHeaderLeavesTheDiffUnchangedWithCompleteCoverage() {
            String diff =
                    "+x\n[pr-pilot:diff-coverage] omitted=1 listed=0 budget=250000 scan=complete\n"
                            + "diff --git a/b b/b\n+z\n";

            DiffCoverage.Split split = DiffCoverage.split(diff);

            assertThat(split.body()).isSameAs(diff);
            assertThat(split.coverage()).isSameAs(DiffCoverage.NONE);
        }

        @Test
        void nullInputHasCompleteCoverage() {
            DiffCoverage.Split split = DiffCoverage.split(null);
            assertThat(split.body()).isNull();
            assertThat(split.coverage()).isSameAs(DiffCoverage.NONE);
        }

        @Test
        void acceptsExactlyTwoHundredListedPaths() {
            DiffCoverage coverage = new DiffCoverage(250, paths(200), 250_000, true);
            String input = "+x\n" + coverage.trailer();

            DiffCoverage.Split split = DiffCoverage.split(input);

            assertThat(split.body()).isEqualTo("+x\n");
            assertThat(split.coverage()).isEqualTo(coverage);
            assertThat(split.coverage().unlisted()).isEqualTo(50);
        }

        @Test
        void rejectsMoreThanTwoHundredListedPaths() {
            StringBuilder input =
                    new StringBuilder(
                            "+x\n[pr-pilot:diff-coverage] omitted=300 listed=201 budget=250000"
                                    + " scan=complete\n");
            for (String path : paths(201))
                input.append("[pr-pilot:omitted] ").append(path).append('\n');

            DiffCoverage.Split split = DiffCoverage.split(input.toString());

            assertThat(split.body()).isEqualTo(input.toString());
            assertThat(split.coverage()).isSameAs(DiffCoverage.NONE);
        }
    }

    @Nested
    class Values {

        @Test
        void noneIsCompleteAndHasNoTrailer() {
            assertThat(DiffCoverage.NONE.complete()).isTrue();
            assertThat(DiffCoverage.NONE.trailer()).isEmpty();
            assertThat(DiffCoverage.NONE.listed()).isZero();
        }

        @Test
        void anIncompleteScanIsIncompleteEvenWithNothingOmitted() {
            DiffCoverage coverage = new DiffCoverage(0, List.of(), 250_000, false);

            assertThat(coverage.complete()).isFalse();
            assertThat(coverage.trailer())
                    .isEqualTo(
                            "[pr-pilot:diff-coverage] omitted=0 listed=0 budget=250000"
                                    + " scan=incomplete\n");
        }

        @Test
        void trailerListsPathsInOrder() {
            DiffCoverage coverage = new DiffCoverage(3, List.of("b.txt", "a.txt"), 1_000_000, true);

            assertThat(coverage.unlisted()).isEqualTo(1);
            assertThat(coverage.trailer())
                    .isEqualTo(
                            "[pr-pilot:diff-coverage] omitted=3 listed=2 budget=1000000"
                                    + " scan=complete\n"
                                    + "[pr-pilot:omitted] b.txt\n"
                                    + "[pr-pilot:omitted] a.txt\n");
        }

        @Test
        void copiesPathsSoCallerMutationCannotChangeTheCoverage() {
            List<String> source = new ArrayList<>(List.of("a.txt"));
            DiffCoverage coverage = new DiffCoverage(1, source, 1, true);
            source.set(0, "changed.txt");

            assertThat(coverage.paths()).containsExactly("a.txt");
            assertThatThrownBy(() -> coverage.paths().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void rejectsValuesTheGrammarCannotCarry() {
            assertThatThrownBy(() -> new DiffCoverage(-1, List.of(), 1, true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DiffCoverage(1_000_000_000, List.of(), 1, true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DiffCoverage(1, List.of(), -1, true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DiffCoverage(1, List.of("a", "b"), 1, true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DiffCoverage(300, paths(201), 1, true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DiffCoverage(1, List.of(""), 1, true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DiffCoverage(1, List.of("a\nb"), 1, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void promptTextListsEachPathAndIsNeverBlank() {
            assertThat(new DiffCoverage(2, List.of(" lead.txt", "b.txt"), 1, true).promptText())
                    .isEqualTo("-  lead.txt\n- b.txt");
            assertThat(new DiffCoverage(4, List.of(), 1, true).promptText())
                    .isEqualTo("(no omitted paths are listed)");
        }
    }

    @Nested
    class Fitted {

        @Test
        void listsAtMostTwoHundredPaths() {
            DiffCoverage coverage = DiffCoverage.fitted(250, paths(250), 250_000, true, 16_384);

            assertThat(coverage.listed()).isEqualTo(200);
            assertThat(coverage.paths()).isEqualTo(paths(200));
            assertThat(coverage.omitted()).isEqualTo(250);
        }

        @Test
        void stopsAtTheFirstPathThatDoesNotFitAndStaysWithinTheByteBudget() {
            String longPath = "d/" + "\u00e9".repeat(200);
            List<String> candidates = List.of("a.txt", longPath, "b.txt");
            int max = 120 + "[pr-pilot:omitted] a.txt\n".length();

            DiffCoverage coverage = DiffCoverage.fitted(3, candidates, 250_000, true, max);

            assertThat(coverage.paths()).containsExactly("a.txt");
            assertThat(coverage.unlisted()).isEqualTo(2);
            assertThat(coverage.trailer().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(max);
        }

        @Test
        void measuresMultibytePathsInUtf8Bytes() {
            List<String> candidates = Collections.nCopies(200, "\u6587".repeat(30));
            int max = 2_000;

            DiffCoverage coverage = DiffCoverage.fitted(200, candidates, 250_000, false, max);

            assertThat(coverage.listed()).isPositive().isLessThan(200);
            assertThat(coverage.trailer().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(max);
        }

        @Test
        void skipsUnlistablePathsButStillCountsThemAsOmitted() {
            DiffCoverage coverage =
                    DiffCoverage.fitted(3, List.of("", "a\tb", "ok.txt"), 250_000, true, 16_384);

            assertThat(coverage.omitted()).isEqualTo(3);
            assertThat(coverage.paths()).containsExactly("ok.txt");
        }

        @Test
        void fittedTrailerRoundTripsThroughSplit() {
            DiffCoverage coverage = DiffCoverage.fitted(5, paths(5), 1_000_000, false, 16_384);

            assertThat(DiffCoverage.split("+x\n" + coverage.trailer()).coverage())
                    .isEqualTo(coverage);
        }
    }

    private static List<String> paths(int count) {
        return IntStream.range(0, count).mapToObj(index -> "src/file-" + index + ".txt").toList();
    }

    record GoldenCase(String name, String input, String body, DiffCoverage coverage) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Reads the line-oriented shared fixture; see the format notes at the top of the file. */
    static List<GoldenCase> loadGolden() throws IOException {
        String text;
        try (InputStream in = DiffCoverageTest.class.getResourceAsStream(GOLDEN)) {
            if (in == null) throw new IOException("missing fixture " + GOLDEN);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<GoldenCase> cases = new ArrayList<>();
        String name = null;
        String input = null;
        String body = null;
        String coverage = null;
        List<String> paths = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("case ")) {
                name = line.substring("case ".length());
                input = null;
                body = null;
                coverage = null;
                paths = new ArrayList<>();
            } else if (keyword(line, "input")) {
                input = unescape(value(line, "input"));
            } else if (keyword(line, "body")) {
                body = unescape(value(line, "body"));
            } else if (keyword(line, "coverage")) {
                coverage = value(line, "coverage");
            } else if (keyword(line, "path")) {
                paths.add(unescape(value(line, "path")));
            } else if (line.equals("end")) {
                if (name == null || input == null || body == null || coverage == null) {
                    throw new IOException("incomplete golden case " + name);
                }
                cases.add(new GoldenCase(name, input, body, coverage(coverage, paths)));
                name = null;
            } else {
                throw new IOException("unrecognized golden line: " + line);
            }
        }
        return cases;
    }

    private static DiffCoverage coverage(String spec, List<String> paths) throws IOException {
        if (spec.equals("none")) {
            if (!paths.isEmpty()) throw new IOException("paths on a rejected case");
            return DiffCoverage.NONE;
        }
        String[] fields = spec.split(" ");
        if (fields.length != 4 || Integer.parseInt(fields[1]) != paths.size()) {
            throw new IOException("bad coverage spec: " + spec);
        }
        return new DiffCoverage(
                Integer.parseInt(fields[0]),
                paths,
                Integer.parseInt(fields[2]),
                fields[3].equals("complete"));
    }

    private static boolean keyword(String line, String keyword) {
        return line.equals(keyword) || line.startsWith(keyword + " ");
    }

    private static String value(String line, String keyword) {
        return line.length() == keyword.length() ? "" : line.substring(keyword.length() + 1);
    }

    private static String unescape(String escaped) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < escaped.length(); index++) {
            char c = escaped.charAt(index);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index + 1 >= escaped.length()) throw new IOException("dangling escape");
            char next = escaped.charAt(++index);
            switch (next) {
                case '\\' -> out.append('\\');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (index + 4 >= escaped.length()) throw new IOException("short \\u escape");
                    out.append(
                            (char) Integer.parseInt(escaped.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> throw new IOException("unknown escape \\" + next);
            }
        }
        return out.toString();
    }
}
