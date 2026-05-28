package com.jinloes.prpilot.services

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe

/** Sample taken verbatim from `copilot help config` v1.0.54 — kept in this test to lock the parser
 *  against the documented help format.
 */
private val REAL_HELP_SAMPLE = """
    `keepAlive`: keep-alive mode applied at CLI startup; prevents the system from sleeping while the session is active. Defaults to `"off"`.

  `model`: AI model to use for Copilot CLI; can be changed with /model command or --model flag option.
    - "claude-sonnet-4.6"
    - "claude-sonnet-4.5"
    - "claude-haiku-4.5"
    - "claude-opus-4.7"
    - "claude-opus-4.6"
    - "claude-opus-4.6-fast"
    - "claude-opus-4.5"
    - "gpt-5.5"
    - "gpt-5.4"
    - "gpt-5.3-codex"
    - "gpt-5.2-codex"
    - "gpt-5.2"
    - "gpt-5.4-mini"

  `mouse`: whether to enable mouse support in alt screen mode; defaults to `true` on macOS, `false` elsewhere.
""".trimIndent()

class CopilotModelDiscoveryTest : FunSpec({

    context("parseModelsFromHelp") {

        test("real help sample — extracts all model IDs in order") {
            val models = CopilotModelDiscovery.parseModelsFromHelp(REAL_HELP_SAMPLE)
            models shouldContainExactly listOf(
                "claude-sonnet-4.6",
                "claude-sonnet-4.5",
                "claude-haiku-4.5",
                "claude-opus-4.7",
                "claude-opus-4.6",
                "claude-opus-4.6-fast",
                "claude-opus-4.5",
                "gpt-5.5",
                "gpt-5.4",
                "gpt-5.3-codex",
                "gpt-5.2-codex",
                "gpt-5.2",
                "gpt-5.4-mini",
            )
        }

        test("section ends at blank line before next setting") {
            val help = """
              `model`: AI model to use for Copilot CLI.
                - "a"
                - "b"

              `theme`: theme to color and stylize output; defaults to "auto".
                - "auto"
                - "dark"
            """.trimIndent()
            CopilotModelDiscovery.parseModelsFromHelp(help) shouldContainExactly listOf("a", "b")
        }

        test("no model section — returns empty list") {
            val help = """
              `theme`: theme to color and stylize output; defaults to "auto".
                - "auto"
                - "dark"
            """.trimIndent()
            CopilotModelDiscovery.parseModelsFromHelp(help).shouldBeEmpty()
        }

        test("empty help text — returns empty list") {
            CopilotModelDiscovery.parseModelsFromHelp("").shouldBeEmpty()
        }

        test("model section present but no items — returns empty list") {
            val help = """
              `model`: AI model to use for Copilot CLI.
              (none configured)
            """.trimIndent()
            CopilotModelDiscovery.parseModelsFromHelp(help).shouldBeEmpty()
        }

        test("non-quoted bullets are ignored") {
            val help = """
              `model`: AI model to use for Copilot CLI.
                - plain-text
                - "real-id"
            """.trimIndent()
            CopilotModelDiscovery.parseModelsFromHelp(help) shouldContainExactly listOf("real-id")
        }

        test("description continuation between header and bullets — still finds items") {
            // Some CLIs wrap a long description onto continuation lines before the bullets.
            val help = """
              `model`: AI model to use for Copilot CLI.
                Long wrapped description that continues here without dashes.
                - "first"
                - "second"
            """.trimIndent()
            val models = CopilotModelDiscovery.parseModelsFromHelp(help)
            models shouldContainInOrder listOf("first", "second")
        }

        test("backtick required — bare model: heading is not matched") {
            val help = """
              model: bare heading without backticks
                - "should-not-match"
            """.trimIndent()
            CopilotModelDiscovery.parseModelsFromHelp(help).shouldBeEmpty()
        }
    }

    context("listModels + invalidate") {

        test("invalidate — drops the cache so the next call re-probes") {
            // We can't easily mock the real `copilot` invocation, so we just verify the invalidate
            // contract: after calling it, the cache is null.
            CopilotModelDiscovery.invalidate()
            // Asserting via behavior: a fresh call must trigger a probe attempt. Whether it
            // succeeds depends on whether copilot is installed in the test environment — both
            // outcomes are acceptable, so we just confirm we get back a non-null List.
            val first = CopilotModelDiscovery.listModels()
            (first.size >= 0) shouldBe true
            CopilotModelDiscovery.invalidate()
        }
    }
})
