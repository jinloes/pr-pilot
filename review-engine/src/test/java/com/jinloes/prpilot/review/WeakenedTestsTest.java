package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ReviewPromptsTestSupport.fakeRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WeakenedTestsTest {

    @Nested
    class Extract {

        @Test
        void detectsAddedSkipAnnotationsAcrossLanguagesOnTestFilesOnly() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/main/java/Service.java b/src/main/java/Service.java
                            --- a/src/main/java/Service.java
                            +++ b/src/main/java/Service.java
                            @@ -1,2 +1,3 @@
                            +// @Disabled in comment and non-test path ignored
                             class Service {}
                            diff --git a/src/test/java/ServiceTest.java b/src/test/java/ServiceTest.java
                            --- a/src/test/java/ServiceTest.java
                            +++ b/src/test/java/ServiceTest.java
                            @@ -10,3 +10,4 @@
                            +    @Disabled("flaky")
                                 @Test
                                 void rejectsExpiredTokens() {
                            diff --git a/web/auth.test.ts b/web/auth.test.ts
                            --- a/web/auth.test.ts
                            +++ b/web/auth.test.ts
                            @@ -4,3 +4,3 @@
                            -it('validates nonce', () => {
                            +it.skip('validates nonce', () => {
                               expect(valid).toBe(true);
                             });
                            diff --git a/crates/core/tests/auth_test.rs b/crates/core/tests/auth_test.rs
                            --- a/crates/core/tests/auth_test.rs
                            +++ b/crates/core/tests/auth_test.rs
                            @@ -1,2 +1,3 @@
                             #[test]
                            +#[ignore]
                             fn checks_signature() {}
                            """);

            List<WeakenedTests.Entry> entries = WeakenedTests.extract(manifest);

            assertThat(entries)
                    .containsExactly(
                            new WeakenedTests.Entry(
                                    "src/test/java/ServiceTest.java",
                                    10,
                                    "skipped-test",
                                    "@Disabled(\"flaky\")"),
                            new WeakenedTests.Entry(
                                    "web/auth.test.ts",
                                    4,
                                    "skipped-test",
                                    "it.skip('validates nonce', () => {"),
                            new WeakenedTests.Entry(
                                    "crates/core/tests/auth_test.rs",
                                    2,
                                    "skipped-test",
                                    "#[ignore]"));
        }

        @Test
        void detectsRemovedAssertionsWithoutReplacementWhileIgnoringUpdatedOrDeletedTests() {
            InspectionManifest manifest =
                    InspectionManifest.fromDiff(
                            """
                            diff --git a/src/test/java/OrderTest.java b/src/test/java/OrderTest.java
                            --- a/src/test/java/OrderTest.java
                            +++ b/src/test/java/OrderTest.java
                            @@ -20,4 +20,3 @@
                                 Order order = service.submit();
                            -    assertThat(order.isAudited()).isTrue();
                            +    service.flush();
                             }
                            @@ -40,4 +39,4 @@
                                 Order order = service.submit();
                            -    assertEquals(1, order.items().size());
                            +    assertThat(order.items()).hasSize(1);
                             }
                            @@ -60,5 +59,1 @@
                            -    @Test
                            -    void legacyEndpointRemoved() {
                            -        assertThat(legacy()).isTrue();
                            -    }
                             }
                            """);

            List<WeakenedTests.Entry> entries = WeakenedTests.extract(manifest);

            assertThat(entries)
                    .containsExactly(
                            new WeakenedTests.Entry(
                                    "src/test/java/OrderTest.java",
                                    21,
                                    "removed-assertions",
                                    "Removed assertion: assertThat(order.isAudited()).isTrue();"));
        }
    }

    @Nested
    class PromptIntegration {

        @Test
        void omitsSectionWhenNoTestIsWeakenedAndIncludesItInReviewAndCritiquePromptsWhenPresent() {
            PRReviewRequest clean = fakeRequest();
            assertThat(ReviewPrompts.buildPrompt(clean)).doesNotContain("<weakened_tests>");

            String weakenedDiff =
                    """
                    diff --git a/src/test/java/AuthTest.java b/src/test/java/AuthTest.java
                    --- a/src/test/java/AuthTest.java
                    +++ b/src/test/java/AuthTest.java
                    @@ -5,3 +5,4 @@
                    +    @Disabled
                         @Test
                         void enforcesMfa() {}
                    """;
            PRReviewRequest withWeakenedTest = clean.toBuilder(weakenedDiff).build();

            String reviewPrompt = ReviewPrompts.buildPrompt(withWeakenedTest);
            assertThat(reviewPrompt)
                    .contains("<weakened_tests>")
                    .contains("- src/test/java/AuthTest.java:5 [skipped-test] @Disabled");

            PRReviewRequest condensedCritiqueRequest =
                    clean.toBuilder("Changed files index only").build();
            String critiquePrompt =
                    ReviewCritiquePrompts.buildCritiquePrompt(
                            condensedCritiqueRequest,
                            new ReviewResult("draft", "COMMENT", List.of()),
                            false,
                            WeakenedTests.extract(InspectionManifest.fromDiff(weakenedDiff)));
            assertThat(critiquePrompt)
                    .contains("<weakened_tests>")
                    .contains("- src/test/java/AuthTest.java:5 [skipped-test] @Disabled");
        }
    }
}
