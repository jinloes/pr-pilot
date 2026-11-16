package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link PromptResources}. */
class PromptResourcesTest {

    @Nested
    class Load {

        @Test
        void loadsBundledMarkdownResources() {
            assertThat(PromptResources.load("output-contract.md"))
                    .startsWith("Respond ONLY with a JSON object")
                    .endsWith("\n");
        }

        @Test
        void normalizesWindowsLineEndingsToLf() {
            String loaded =
                    PromptResources.load(
                            path ->
                                    new ByteArrayInputStream(
                                            "line one\r\nline two\r\n"
                                                    .getBytes(StandardCharsets.UTF_8)),
                            "crlf.md");

            assertThat(loaded).isEqualTo("line one\nline two\n");
        }

        @Test
        void rejectsAMissingResource() {
            assertThatThrownBy(() -> PromptResources.load(path -> null, "missing.md"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Missing bundled prompt resource: missing.md");
        }

        @Test
        void rejectsAnOversizedResource() {
            byte[] oversized = new byte[PromptResources.MAX_RESOURCE_BYTES + 1];

            assertThatThrownBy(
                            () ->
                                    PromptResources.load(
                                            path -> new ByteArrayInputStream(oversized), "big.md"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exceeds " + PromptResources.MAX_RESOURCE_BYTES);
        }

        @Test
        void wrapsReadFailuresAsIllegalStateException() {
            assertThatThrownBy(
                            () ->
                                    PromptResources.load(
                                            path -> {
                                                throw new IOException("boom");
                                            },
                                            "broken.md"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to read bundled prompt resource: broken.md")
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Nested
    class ComputeVersion {

        @Test
        void formatsPrefixAndTwelveHexDigestDeterministically() {
            String first =
                    PromptResources.computeVersion("prompt-sha256", List.of("alpha", "beta"));
            String second =
                    PromptResources.computeVersion("prompt-sha256", List.of("alpha", "beta"));

            assertThat(first).matches("prompt-sha256-[0-9a-f]{12}").isEqualTo(second);
        }

        @Test
        void changesWhenAnyComponentOrComponentBoundaryChanges() {
            String baseline = PromptResources.computeVersion("prompt-sha256", List.of("ab", "c"));

            assertThat(PromptResources.computeVersion("prompt-sha256", List.of("ab", "d")))
                    .isNotEqualTo(baseline);
            assertThat(PromptResources.computeVersion("prompt-sha256", List.of("a", "bc")))
                    .isNotEqualTo(baseline);
        }

        @Test
        void handlesNullComponentsWithoutThrowing() {
            String withNull =
                    PromptResources.computeVersion("prompt-sha256", Arrays.asList("a", null));
            String withEmpty = PromptResources.computeVersion("prompt-sha256", List.of("a", ""));

            assertThat(withNull).isEqualTo(withEmpty);
        }
    }
}
