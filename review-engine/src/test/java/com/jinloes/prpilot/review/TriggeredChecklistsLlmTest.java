package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.TriggeredChecklistsTestSupport.changed;
import static com.jinloes.prpilot.review.TriggeredChecklistsTestSupport.fileDiff;
import static com.jinloes.prpilot.review.TriggeredChecklistsTestSupport.sectionFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for the LLM-integrations checklist in {@link TriggeredChecklists}. */
class TriggeredChecklistsLlmTest {

    @Nested
    class SectionFor {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "import openai",
                    "var client = new OpenAIClient(key);",
                    "from langchain_core.prompts import ChatPromptTemplate",
                    "response = client.messages.create(model=MODEL)",
                    "model: \"claude-sonnet-4-5\"",
                    "String MODEL = \"gpt-4o\";",
                    "BedrockRuntimeClient bedrock = BedrockRuntimeClient.create();",
                    "result = model.generate_content(prompt)",
                    "String systemPrompt = load();",
                    "_SYSTEM_PROMPT = \"You are a billing assistant.\"",
                    "for (var call : message.toolCalls()) {",
                    "const llmClient = createClient();",
                    "completion = client.chat.completions.create(**args)",
                    "from app.agents.subprompt.billing import BILLING_RULES",
                    "AGENT_INSTRUCTIONS = load_rules()",
                    "_REVIEW_INSTRUCTIONS = \"Return not_applicable for unrelated questions.\"",
                    "messages = [SystemMessage(content=prompt), HumanMessage(content=user_input)]",
                    "history = [AIMessage(content=\"ok\"), ToolMessage(content=\"{}\", tool_call_id=\"1\")]",
                    "if isinstance(action, AgentAction):",
                    "planned = [] if isinstance(output, AgentFinish) else [output]",
                    "class GuardedExecutor(AgentExecutor):"
                })
        void triggersTheLlmChecklistForModelAndAgentConstructsOnAddedLines(String addedLine) {
            assertThat(sectionFor(fileDiff("src/app/Service.java", "+" + addedLine)))
                    .contains("\nLLM integrations:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "String coherentState = \"x\";",
                    "// the bedrock of this design",
                    "boolean fullMatch = matcher.matches();",
                    "chatRoom.send(message);",
                    "String subPromptly = \"x\";",
                    "String systemMessage = \"disk full\";",
                    "String ecosystemPrompt = \"x\";",
                    "void agentAction(String id) {}",
                    "String reviewInstructionSet = \"x\";"
                })
        void doesNotTriggerTheLlmChecklistOnLookAlikeWords(String addedLine) {
            assertThat(sectionFor(fileDiff("src/app/Service.java", "+" + addedLine))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "prompts/review.txt",
                    "src/review.prompt.md",
                    "agent.prompty",
                    "src/app/subprompt/billing.py",
                    "src/app/agents/definitions/billing_definition.py"
                })
        void triggersTheLlmChecklistForPromptFiles(String path) {
            assertThat(changed(path)).contains("\nLLM integrations:\n");
        }

        @ParameterizedTest
        @ValueSource(strings = {"src/app/agents/billing.py", "src/useragents/definitions/a.py"})
        void doesNotTriggerTheLlmChecklistForOtherAgentPaths(String path) {
            assertThat(changed(path)).doesNotContain("\nLLM integrations:\n");
        }

        @Test
        void asksWhetherAPromptRuleContradictsToolGuidance() {
            assertThat(changed("prompts/review.txt"))
                    .contains("A changed prompt rule that contradicts another")
                    .contains("Check the empty,")
                    .contains("unchanged ones too");
        }

        @Test
        void asksWhetherAnAgentGuardCoversNoToolExitsFailedLookupsAndKeywordHeuristics() {
            assertThat(changed("prompts/review.txt"))
                    .contains("blocks unverified tool exits (such as a terminal answer tool)")
                    .contains(
                            "direct no-tool final response through without a successful lookup"
                                    + " result")
                    .contains(
                            "checks only whether a lookup tool was called while ignoring an error,"
                                    + " not-found, or unavailable observation")
                    .contains(
                            "letting ungrounded factual claims without figures pass as"
                                    + " clarification prompts")
                    .doesNotContain("updated for a conditionally registered tool");
        }
    }

    @Nested
    class EndToEndPromptAssembly {

        @Test
        void coversUngatedCompanionPromptAndAllowListEditsInAFlagGatedPr() {
            PullRequest pr =
                    new PullRequest(
                            "Add flag-gated charge lookup",
                            "https://github.com/acme/widgets/pull/42",
                            "acme",
                            "widgets",
                            42,
                            "Zero live behavior change when the feature flag is off.",
                            "alice",
                            "2026-01-01");
            String diff =
                    fileDiff(
                            "src/app/agents/refund_guard.py",
                            " _REVIEW_INSTRUCTIONS = (",
                            "-    \"Return not_applicable for an unrelated question.\"",
                            "+    \"Return not_applicable for an unrelated question or a charge"
                                    + " breakdown question.\"",
                            "+if isinstance(action, AgentAction) and action.tool =="
                                    + " \"charge_lookup\":",
                            "+    allow_account_url = not handoff");
            PRReviewRequest request =
                    PRReviewRequest.builder(pr, diff)
                            .repoGuidelines(
                                    "## AGENTS.md\n"
                                            + "New features must be behind a feature flag — no"
                                            + " always-on behavior changes.")
                            .build();

            String prompt = ReviewPrompts.buildPrompt(request);

            assertThat(prompt)
                    .contains("Claims are not evidence.")
                    .contains("Zero live behavior change when the feature flag is off.")
                    .contains("## AGENTS.md")
                    .contains("When this pull request itself gates a feature behind a flag")
                    .contains("with that gate off and that capability absent")
                    .contains("is an \"issue\" naming the gate")
                    .contains("\nLLM integrations:\n");
        }

        @Test
        void coversGuardExemptionsFailedLookupsAndLooseClarificationHeuristics() {
            PullRequest pr =
                    new PullRequest(
                            "Allow charge lookup turns through spend guard",
                            "https://github.com/acme/widgets/pull/43",
                            "acme",
                            "widgets",
                            43,
                            "Exempts charge lookup turns from the unavailable fallback.",
                            "alice",
                            "2026-01-01");
            String diff =
                    fileDiff(
                            "src/app/agents/turn_executor.py",
                            "+def _is_clarification_prompt(text):",
                            "+    return not _HAS_AMOUNT.search(text) and"
                                    + " bool(_DATE_OR_LINK.search(text))",
                            "+has_lookup = any(isinstance(a, AgentAction) and a.tool =="
                                    + " \"charge_lookup\" for a, _ in steps)",
                            "+if has_lookup:",
                            "+    return not planned or planned[0].tool == \"answer\"",
                            "+planned = [] if isinstance(output, AgentFinish) else [output]");

            String prompt = ReviewPrompts.buildPrompt(PRReviewRequest.builder(pr, diff).build());

            assertThat(prompt)
                    .contains("- Incomplete guard exemptions:")
                    .contains("every representation of an exit or action that reaches the guard")
                    .contains("empty-list final answer slip through unverified")
                    .contains("while ignoring its result or observation")
                    .contains(
                            "declarative unverified claim containing one of those words and no"
                                    + " figure")
                    .contains("limit the pre-step exemption")
                    .contains("\nLLM integrations:\n")
                    .contains(
                            "direct no-tool final response through without a successful lookup"
                                    + " result");
        }

        @Test
        void coversDuplicateAndSilentUnavailableBranchesInToolWrappers() {
            PullRequest pr =
                    new PullRequest(
                            "Wrap charge summary lookup tool",
                            "https://github.com/acme/widgets/pull/44",
                            "acme",
                            "widgets",
                            44,
                            "Returns unavailable on upstream or schema failures.",
                            "alice",
                            "2026-01-01");
            String diff =
                    fileDiff(
                            "src/app/tools/charge_lookup_tool.py",
                            "+def _parse_result(raw):",
                            "+    if not raw:",
                            "+        return Status.UNAVAILABLE",
                            "+    log.warning(\"upstream returned error status\")",
                            "+    return Status.UNAVAILABLE",
                            "+def _run(self, item_link):",
                            "+    status = _parse_result(self._invoke(item_link))",
                            "+    log.warning(\"lookup outcome=%s\", status)");

            String reviewPrompt =
                    ReviewPrompts.buildPrompt(PRReviewRequest.builder(pr, diff).build());
            String hygienePrompt =
                    ReviewHygienePrompts.buildHygienePrompt(
                            PRReviewRequest.builder(pr, diff).candidateRecall(true).build());

            assertThat(reviewPrompt)
                    .contains(
                            "collapse upstream, parse, or projection failures into a coarse error"
                                    + " status")
                    .contains("log a WARN with a fixed reason token");
            assertThat(hygienePrompt)
                    .contains("Duplicate or uneven failure logging")
                    .contains("emitting two warnings per failure")
                    .contains("silent sibling failure branch")
                    .contains("<changed_log_statements>\n");
        }
    }
}
