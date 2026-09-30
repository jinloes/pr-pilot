package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.DiffCoverage;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PrDiffServiceTest {
    @Nested
    class Bounding {
        private static final int REVIEW = PrDiffService.REVIEW_LIMIT_BYTES;
        private static final int VALIDATION = PrDiffService.VALIDATION_LIMIT_BYTES;
        private static final int RESERVE = 16_384;

        /** Negative control: nothing over budget means the exact bytes, no trailer, no flag. */
        @Test
        void anUnderBudgetDiffIsReturnedByteIdenticalWithoutATrailer() throws IOException {
            String diff =
                    "diff --git a/w.txt b/w.txt\r\n--- a/w.txt\r\n+++ b/w.txt\r\n@@ -1 +1 @@\r\n"
                            + "-caf\u00e9\r\n+na\u00efve \u6587\r\n"
                            + section("b.txt", 5_000)
                            + "diff --git a/c.txt b/c.txt\n+no newline at end";

            for (int limit : List.of(REVIEW, VALIDATION)) {
                PrDiffService.Response response = bound(diff, limit);

                assertThat(response.status()).isEqualTo(PrDiffService.Status.OK);
                assertThat(response.diff()).isEqualTo(diff);
                assertThat(response.truncated()).isFalse();
                assertThat(DiffCoverage.split(response.diff()).coverage())
                        .isSameAs(DiffCoverage.NONE);
            }
        }

        @Test
        void aScanThatEndsExactlyAtTheCeilingIsComplete() throws IOException {
            String diff = section("a.txt", 4_000) + section("b.txt", 6_000);
            byte[] bytes = diff.getBytes(StandardCharsets.UTF_8);

            PrDiffService.Response response =
                    PrDiffService.bound(
                            new ByteArrayInputStream(bytes),
                            REVIEW,
                            PrDiffService.PER_FILE_CAP_BYTES,
                            bytes.length);

            assertThat(response.diff()).isEqualTo(diff);
            assertThat(response.truncated()).isFalse();
        }

        @Test
        void aLimitInsideAMultibyteCharacterKeepsWholeFiles() throws IOException {
            String small = section("a.txt", 1_000);
            String header =
                    "diff --git a/cjk.txt b/cjk.txt\n--- a/cjk.txt\n+++ b/cjk.txt\n@@ -1 +1 @@\n";
            int before = small.length() + header.length() + 1;
            int pad = Math.floorMod(REVIEW - before - 1, 3);
            int chars = (249_500 - header.length() - 2 - pad) / 3;
            String cjk = header + "+" + "x".repeat(pad) + "\u6587".repeat(chars) + "\n";
            byte[] bytes = (small + cjk).getBytes(StandardCharsets.UTF_8);
            assertThat(bytes.length).isGreaterThan(REVIEW);
            assertThat(bytes[REVIEW] & 0xC0).as("limit falls inside a character").isEqualTo(0x80);

            PrDiffService.Response response = bound(small + cjk, REVIEW);

            assertThat(response.diff()).doesNotContain("\uFFFD");
            assertThat(response.truncated()).isTrue();
            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(small);
            assertThat(split.coverage())
                    .isEqualTo(new DiffCoverage(1, List.of("cjk.txt"), REVIEW, true));
        }

        @Test
        void aLimitInsideAHunkKeepsWholeFilesInOriginalOrder() throws IOException {
            String a = section("a.txt", 100_000);
            String b = section("b.txt", 100_000);
            String c = section("c.txt", 100_000);

            PrDiffService.Response response = bound(a + b + c, REVIEW);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(a + b);
            assertThat(split.coverage().paths()).containsExactly("c.txt");
            assertThat(response.truncated()).isTrue();
        }

        @Test
        void keptCrlfSectionsArePreservedByteForByte() throws IOException {
            String crlf =
                    "diff --git a/w.txt b/w.txt\r\n--- a/w.txt\r\n+++ b/w.txt\r\n@@ -1 +1 @@\r\n"
                            + "-a\r\n+b\r\n";
            String big = section("big.txt", 249_000).replace("\n", "\r\n");
            String tail = section("tail.txt", 1_000);

            PrDiffService.Response response = bound(crlf + big + tail, REVIEW);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(crlf + tail);
            assertThat(split.coverage().paths()).containsExactly("big.txt");
        }

        /** First-fit would keep the 200K file; smallest-first keeps both smaller files. */
        @Test
        void keepsTheMaximalSmallestFirstFileSet() throws IOException {
            String large = section("large.txt", 200_000);
            String medium = section("medium.txt", 60_000);
            String small = section("small.txt", 40_000);

            PrDiffService.Response review = bound(large + medium + small, REVIEW);
            PrDiffService.Response validation = bound(large + medium + small, VALIDATION);

            DiffCoverage.Split split = DiffCoverage.split(review.diff());
            assertThat(split.body()).isEqualTo(medium + small);
            assertThat(split.coverage())
                    .isEqualTo(new DiffCoverage(1, List.of("large.txt"), REVIEW, true));
            assertThat(validation.diff()).isEqualTo(large + medium + small);
            assertThat(validation.truncated()).isFalse();
        }

        @Test
        void reviewSelectionIsASubsetOfValidationSelection() throws IOException {
            int[] sizes = {
                200_000, 60_000, 40_000, 180_000, 300_000, 90_000, 245_000, 10_000, 150_000, 120_000
            };
            List<String> input = new ArrayList<>();
            for (int index = 0; index < sizes.length; index++) {
                input.add(section("f" + index + ".txt", sizes[index]));
            }
            String diff = String.join("", input);

            DiffCoverage.Split review = DiffCoverage.split(bound(diff, REVIEW).diff());
            DiffCoverage.Split validation = DiffCoverage.split(bound(diff, VALIDATION).diff());

            List<String> reviewKept = sections(review.body());
            List<String> validationKept = sections(validation.body());
            assertThat(input).containsAll(reviewKept).containsAll(validationKept);
            assertThat(validationKept).containsAll(reviewKept);
            assertThat(review.coverage().paths()).containsAll(validation.coverage().paths());
            assertThat(review.coverage().paths()).contains("f4.txt");
            assertThat(validation.coverage().paths()).contains("f4.txt");
            assertThat(reviewKept.size() + review.coverage().omitted()).isEqualTo(sizes.length);
            assertThat(validationKept.size() + validation.coverage().omitted())
                    .isEqualTo(sizes.length);
        }

        @Test
        void aFileOverTheCapIsOmittedEvenInValidationMode() throws IOException {
            String huge = section("huge.txt", 300_000);
            String small = section("small.txt", 50_000);

            PrDiffService.Response response = bound(huge + small, VALIDATION);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(small);
            assertThat(split.coverage())
                    .isEqualTo(new DiffCoverage(1, List.of("huge.txt"), VALIDATION, true));
            assertThat(response.truncated()).isTrue();
        }

        @Test
        void bodyPlusTrailerNeverExceedsTheLimit() throws IOException {
            String longDir = "d/" + "segment-".repeat(18);
            StringBuilder diff = new StringBuilder();
            for (int index = 0; index < 400; index++) {
                diff.append(section(longDir + index + ".txt", 700));
            }

            PrDiffService.Response response = bound(diff.toString(), REVIEW);

            int total = response.diff().getBytes(StandardCharsets.UTF_8).length;
            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(total).isLessThanOrEqualTo(REVIEW);
            assertThat(split.body().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(REVIEW - RESERVE);
            assertThat(split.coverage().listed()).isPositive().isLessThanOrEqualTo(200);
            assertThat(sections(split.body()).size() + split.coverage().omitted()).isEqualTo(400);
            assertThat(response.truncated()).isTrue();
        }

        @Test
        void listsPathsFromPlusMinusAndHeaderFallbacksKeepingCQuotedPathsVerbatim()
                throws IOException {
            String pad = "+" + "x".repeat(2_100) + "\n";
            String diff =
                    section("keep.txt", 500)
                            + "diff --git a/old.txt b/new.txt\nsimilarity index 90%\n"
                            + "rename from old.txt\nrename to new.txt\n--- a/old.txt\n"
                            + "+++ b/new.txt\n@@ -1 +1 @@\n"
                            + pad
                            + "diff --git a/gone.txt b/gone.txt\ndeleted file mode 100644\n"
                            + "--- a/gone.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n"
                            + pad.replace('+', '-')
                            + "diff --git a/img one.png b/img one.png\nindex 1..2 100644\n"
                            + "Binary files a/img one.png and b/img one.png differ\n"
                            + "x".repeat(2_100)
                            + "\n"
                            + "diff --git \"a/caf\\303\\251.txt\" \"b/caf\\303\\251.txt\"\n"
                            + "--- \"a/caf\\303\\251.txt\"\n+++ \"b/caf\\303\\251.txt\"\n"
                            + "@@ -1 +1 @@\n"
                            + pad
                            + "diff --git a/my file.txt b/my file.txt\n--- a/my file.txt\t\n"
                            + "+++ b/my file.txt\t\n@@ -1 +1 @@\n"
                            + pad
                            + "diff --git a/real.txt b/real.txt\n--- a/real.txt\n+++ b/real.txt\n"
                            + "@@ -1 +1 @@\n+++ b/fake.txt\n"
                            + pad
                            + "diff --git \"a/sp ace\\t.txt\" \"b/sp ace\\t.txt\"\nold mode 100644\n"
                            + "new mode 100755\n"
                            + "x".repeat(2_100)
                            + "\n"
                            + "diff --git a/mode.sh b/mode.sh\r\nold mode 100644\r\nnew mode 100755\r\n"
                            + "x".repeat(2_100)
                            + "\r\n";

            PrDiffService.Response response =
                    PrDiffService.bound(
                            new ByteArrayInputStream(diff.getBytes(StandardCharsets.UTF_8)),
                            REVIEW,
                            2_000,
                            PrDiffService.SCAN_CEILING_BYTES);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(section("keep.txt", 500));
            assertThat(split.coverage().paths())
                    .containsExactly(
                            "new.txt",
                            "gone.txt",
                            "img one.png",
                            "\"caf\\303\\251.txt\"",
                            "my file.txt",
                            "real.txt",
                            "\"sp ace\\t.txt\"",
                            "mode.sh");
        }

        @Test
        void listsAtMostTwoHundredPathsButCountsEveryOmission() throws IOException {
            List<String> paths =
                    IntStream.range(0, 250).mapToObj(index -> "p" + index + ".txt").toList();
            String diff = String.join("", paths.stream().map(path -> section(path, 150)).toList());

            PrDiffService.Response response =
                    PrDiffService.bound(
                            new ByteArrayInputStream(diff.getBytes(StandardCharsets.UTF_8)),
                            REVIEW,
                            100,
                            PrDiffService.SCAN_CEILING_BYTES);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEmpty();
            assertThat(split.coverage().omitted()).isEqualTo(250);
            assertThat(split.coverage().paths()).isEqualTo(paths.subList(0, 200));
            assertThat(response.truncated()).isTrue();
        }

        @Test
        void aScanPastTheCeilingIsIncompleteAndCountsTheFileInProgress() throws IOException {
            List<String> input =
                    IntStream.range(0, 30)
                            .mapToObj(index -> section("s" + index + ".txt", 1_000))
                            .toList();
            byte[] bytes = String.join("", input).getBytes(StandardCharsets.UTF_8);

            PrDiffService.Response response =
                    PrDiffService.bound(
                            new ByteArrayInputStream(bytes),
                            REVIEW,
                            PrDiffService.PER_FILE_CAP_BYTES,
                            10_500);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(String.join("", input.subList(0, 10)));
            assertThat(split.coverage())
                    .isEqualTo(new DiffCoverage(1, List.of("s10.txt"), REVIEW, false));
            assertThat(response.truncated()).isTrue();
        }

        @Test
        void aLastKeptSectionWithoutAFinalNewlineGetsOneBeforeTheTrailer() throws IOException {
            String a = section("a.txt", 1_000);
            String big = section("big.txt", 249_500);
            String last = "diff --git a/c.txt b/c.txt\n+no newline";

            PrDiffService.Response response = bound(a + big + last, REVIEW);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(a + last + "\n");
            assertThat(split.coverage().paths()).containsExactly("big.txt");
        }

        @Test
        void aPreambleJoinsTheFirstSection() throws IOException {
            String a = "note before the first file\n" + section("a.txt", 100_000);
            String b = section("b.txt", 100_000);
            String c = section("c.txt", 100_000);

            PrDiffService.Response response = bound(a + b + c, REVIEW);

            DiffCoverage.Split split = DiffCoverage.split(response.diff());
            assertThat(split.body()).isEqualTo(b + c);
            assertThat(split.coverage().paths()).containsExactly("a.txt");
        }

        @Test
        void boundReviewDiffAppliesTheReviewLimitToALocalDiff() throws IOException {
            String small = section("small.txt", 1_000);
            String large = section("large.txt", REVIEW);

            PrDiffResult result =
                    PrDiffService.boundReviewDiff(
                            new ByteArrayInputStream(
                                    (small + large).getBytes(StandardCharsets.UTF_8)));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.truncated()).isTrue();
            assertThat(result.limitBytes()).isEqualTo(REVIEW);
            DiffCoverage.Split split = DiffCoverage.split(result.diff());
            assertThat(split.body()).isEqualTo(small);
            assertThat(split.coverage().paths()).containsExactly("large.txt");
        }

        @Test
        void boundReviewDiffReturnsASmallLocalDiffUnchanged() throws IOException {
            String diff = section("a.txt", 2_000);

            PrDiffResult result =
                    PrDiffService.boundReviewDiff(
                            new ByteArrayInputStream(diff.getBytes(StandardCharsets.UTF_8)));

            assertThat(result.diff()).isEqualTo(diff);
            assertThat(result.truncated()).isFalse();
        }

        private static PrDiffService.Response bound(String diff, int limit) throws IOException {
            return PrDiffService.bound(
                    new ByteArrayInputStream(diff.getBytes(StandardCharsets.UTF_8)),
                    limit,
                    PrDiffService.PER_FILE_CAP_BYTES,
                    PrDiffService.SCAN_CEILING_BYTES);
        }

        /** An ASCII file section of exactly {@code bytes} bytes. */
        private static String section(String path, int bytes) {
            String header =
                    "diff --git a/"
                            + path
                            + " b/"
                            + path
                            + "\n--- a/"
                            + path
                            + "\n+++ b/"
                            + path
                            + "\n@@ -1 +1 @@\n";
            return header + "+" + "x".repeat(bytes - header.length() - 2) + "\n";
        }

        private static List<String> sections(String body) {
            if (body.isEmpty()) return List.of();
            return Arrays.asList(body.split("(?m)(?=^diff --git )"));
        }
    }

    @Test
    void usesReviewLimitForReviewMode() {
        AtomicInteger requestedLimit = new AtomicInteger();
        PrDiffService service = service(requestedLimit);

        PrDiffResult result = service.get(params("review"));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.limitBytes()).isEqualTo(PrDiffService.REVIEW_LIMIT_BYTES);
        assertThat(requestedLimit.get()).isEqualTo(PrDiffService.REVIEW_LIMIT_BYTES);
    }

    @Test
    void usesValidationLimitForValidationMode() {
        AtomicInteger requestedLimit = new AtomicInteger();
        PrDiffService service = service(requestedLimit);

        PrDiffResult result = service.get(params("validation"));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.limitBytes()).isEqualTo(PrDiffService.VALIDATION_LIMIT_BYTES);
        assertThat(requestedLimit.get()).isEqualTo(PrDiffService.VALIDATION_LIMIT_BYTES);
    }

    @Test
    void rejectsUnknownModesWithoutResolvingAToken() {
        AtomicInteger tokenCalls = new AtomicInteger();
        PrDiffService service =
                new PrDiffService(
                        hostname -> {
                            tokenCalls.incrementAndGet();
                            return GitHubAuthService.TokenResolution.resolved("secret-token");
                        },
                        (api, token, owner, repo, number, limit) ->
                                PrDiffService.Response.ok("diff", false));

        PrDiffResult result = service.get(params("archive"));

        assertThat(result.status()).isEqualTo("invalid_request");
        assertThat(tokenCalls).hasValue(0);
    }

    @Test
    void retriesEveryTransientFailureClass() {
        for (PrDiffService.Status status :
                List.of(
                        PrDiffService.Status.RATE_LIMITED,
                        PrDiffService.Status.NETWORK,
                        PrDiffService.Status.TRANSIENT_API)) {
            ArrayDeque<PrDiffService.Response> responses =
                    new ArrayDeque<>(
                            List.of(
                                    PrDiffService.Response.of(status),
                                    PrDiffService.Response.ok("diff", false)));
            List<Integer> backoffs = new ArrayList<>();
            PrDiffService service =
                    new PrDiffService(
                            hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            (api, token, owner, repo, number, limit) -> responses.removeFirst(),
                            backoffs::add);

            PrDiffResult result = service.get(params("review"));

            assertThat(result.status()).as(status.name()).isEqualTo("ok");
            assertThat(backoffs).as(status.name()).containsExactly(1);
        }
    }

    @Test
    void stopsAfterThreeTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> backoffs = new ArrayList<>();
        PrDiffService service =
                new PrDiffService(
                        hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        (api, token, owner, repo, number, limit) -> {
                            attempts.incrementAndGet();
                            return PrDiffService.Response.of(PrDiffService.Status.RATE_LIMITED);
                        },
                        backoffs::add);

        PrDiffResult result = service.get(params("review"));

        assertThat(result.status()).isEqualTo("rate_limited");
        assertThat(attempts).hasValue(3);
        assertThat(backoffs).containsExactly(1, 2);
    }

    @Test
    void doesNotRetryPermanentApiFailures() {
        AtomicInteger attempts = new AtomicInteger();
        PrDiffService service =
                new PrDiffService(
                        hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        (api, token, owner, repo, number, limit) -> {
                            attempts.incrementAndGet();
                            return PrDiffService.Response.of(PrDiffService.Status.API);
                        },
                        attempt -> {});

        assertThat(service.get(params("review")).status()).isEqualTo("api_failed");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void preservesAmbiguityWhenThePullRequestIsNotFoundOrInaccessible() {
        PrDiffService service =
                new PrDiffService(
                        hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        (api, token, owner, repo, number, limit) ->
                                PrDiffService.Response.of(PrDiffService.Status.NOT_FOUND));

        PrDiffResult result = service.get(params("review"));

        assertThat(result.status()).isEqualTo(PrDiffResult.STATUS_NOT_FOUND_OR_INACCESSIBLE);
        assertThat(result.message()).contains("not found or inaccessible", "active gh account");
    }

    @Test
    void reportsAnOversizedDiffOnceWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        PrDiffService service =
                new PrDiffService(
                        hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        (api, token, owner, repo, number, limit) -> {
                            attempts.incrementAndGet();
                            return PrDiffService.Response.of(PrDiffService.Status.TOO_LARGE);
                        },
                        attempt -> {});

        PrDiffResult result = service.get(params("review"));

        assertThat(attempts).hasValue(1);
        assertThat(result.status()).isEqualTo(PrDiffResult.STATUS_DIFF_TOO_LARGE);
        assertThat(result.status()).isEqualTo("diff_too_large");
        assertThat(result.message())
                .isEqualTo(
                        "GitHub declined to return this pull request's diff (HTTP 406); it likely"
                                + " exceeds GitHub's diff size limits.");
        assertThat(result.diff()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "400, API",
        "401, UNAUTHENTICATED",
        "403, UNAUTHENTICATED",
        "404, NOT_FOUND",
        "406, TOO_LARGE",
        "429, RATE_LIMITED",
        "500, TRANSIENT_API"
    })
    void classifiesHttpFailures(int statusCode, PrDiffService.Status expected) {
        assertThat(PrDiffService.classifyFailure(statusCode)).isEqualTo(expected);
    }

    private static PrDiffService service(AtomicInteger requestedLimit) {
        return new PrDiffService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                (api, token, owner, repo, number, limit) -> {
                    requestedLimit.set(limit);
                    return PrDiffService.Response.ok("diff", false);
                });
    }

    private static PrDiffService.Params params(String mode) {
        return new PrDiffService.Params("https://github.com", "acme", "widgets", 42, mode);
    }
}
