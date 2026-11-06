package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for reading the session model catalog in {@link CopilotModelDiscovery}. */
class CopilotModelDiscoverySessionCatalogTest {

    @Nested
    class FilterCatalogEntries {

        @Test
        void keepsEnabledAndUnconfiguredModelsInCatalogOrder() {
            List<String> ids =
                    CopilotModelDiscovery.filterCatalogEntries(
                            List.of(
                                    Map.of("id", "auto"),
                                    Map.of(
                                            "id",
                                            " gemini-4-argon ",
                                            "policy",
                                            Map.of("state", "enabled", "terms", "Enable access"),
                                            "model_picker_enabled",
                                            true),
                                    Map.of("id", "gpt-5.3-codex")));

            assertThat(ids).containsExactly("auto", "gemini-4-argon", "gpt-5.3-codex");
        }

        @Test
        void dropsDisabledBlankDuplicateAndNonObjectEntries() {
            List<String> ids =
                    CopilotModelDiscovery.filterCatalogEntries(
                            Arrays.asList(
                                    "loose-string",
                                    null,
                                    List.of("nested"),
                                    Map.of("id", "   "),
                                    Map.of("id", 42),
                                    Map.of("id", "off", "policy", Map.of("state", "DISABLED")),
                                    Map.of("id", "kept"),
                                    Map.of("id", "kept")));

            assertThat(ids).containsExactly("kept");
        }

        @Test
        void treatsNullAsEmpty() {
            assertThat(CopilotModelDiscovery.filterCatalogEntries(null)).isEmpty();
        }
    }

    @Nested
    class AccountModels {

        @Test
        void prefersTheSessionCatalogAndSkipsTheGlobalOne() throws Exception {
            AtomicInteger globalCalls = new AtomicInteger();

            List<String> ids =
                    CopilotModelDiscovery.accountModels(
                            () -> List.of("gemini-4-argon", "gpt-6-sol"),
                            () -> {
                                globalCalls.incrementAndGet();
                                return List.of("gpt-6-sol");
                            });

            assertThat(ids).containsExactly("gemini-4-argon", "gpt-6-sol");
            assertThat(globalCalls).hasValue(0);
        }

        @Test
        void fallsBackToTheGlobalCatalogWhenTheSessionRouteFails() throws Exception {
            List<String> ids =
                    CopilotModelDiscovery.accountModels(
                            () -> {
                                throw new IOException("session creation failed");
                            },
                            () -> List.of("gpt-6-sol"));

            assertThat(ids).containsExactly("gpt-6-sol");
        }

        @Test
        void fallsBackToTheGlobalCatalogWhenTheSessionCatalogIsEmpty() throws Exception {
            assertThat(CopilotModelDiscovery.accountModels(List::of, () -> List.of("gpt-6-sol")))
                    .containsExactly("gpt-6-sol");
        }

        @Test
        void propagatesInterruptionWithoutTryingTheGlobalCatalog() {
            AtomicInteger globalCalls = new AtomicInteger();

            assertThatThrownBy(
                            () ->
                                    CopilotModelDiscovery.accountModels(
                                            () -> {
                                                throw new InterruptedException("stop");
                                            },
                                            () -> {
                                                globalCalls.incrementAndGet();
                                                return List.of();
                                            }))
                    .isInstanceOf(InterruptedException.class);
            assertThat(globalCalls).hasValue(0);
        }

        @Test
        void propagatesAGlobalCatalogFailureSoDiscoveryCanFallBackToHelp() {
            assertThatThrownBy(
                            () ->
                                    CopilotModelDiscovery.accountModels(
                                            List::of,
                                            () -> {
                                                throw new IOException("models.list failed");
                                            }))
                    .isInstanceOf(IOException.class)
                    .hasMessage("models.list failed");
        }
    }
}
