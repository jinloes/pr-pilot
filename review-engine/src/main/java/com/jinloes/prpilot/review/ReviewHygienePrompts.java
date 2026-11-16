package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.PRReviewRequest;
import java.util.List;

/**
 * Hygiene rules and the standalone hygiene-pass prompt. {@link #HYGIENE_PASS} is also embedded in
 * the non-recall first-pass instructions as Pass C.
 */
final class ReviewHygienePrompts {

    private ReviewHygienePrompts() {}

    /**
     * Built-in hygiene rules that production code reviewers enforce but a bug hunt skips because
     * nothing is broken. On the recall benchmark they were most of the missed findings, so they get
     * their own pass with fixed type, category and severity to keep them from crowding out real
     * defects. In candidate-recall mode the same rules run as a separate {@link
     * #buildHygienePrompt} call instead, so the bug hunt keeps the whole first-pass budget.
     */
    static final String HYGIENE_RULES = PromptResources.load("hygiene-rules.md");

    static final String HYGIENE_PASS =
            "Pass C — hygiene checks on changed lines only, applied after A and B:\n"
                    + HYGIENE_RULES;

    static final String HYGIENE_PREAMBLE = PromptResources.load("hygiene-preamble.md");

    /**
     * Builds the standalone hygiene prompt the pipeline runs in candidate-recall mode: the {@link
     * #HYGIENE_RULES} with an explicit inventory step, the shared {@link
     * ReviewOutputContract#OUTPUT_CONTRACT}, the PR metadata, any repository guidelines, and the
     * annotated diff. Its findings are merged into the draft before validation.
     */
    public static String buildHygienePrompt(PRReviewRequest request) {
        return buildHygienePrompt(
                request,
                ChangedLogStatements.extract(InspectionManifest.fromDiff(request.getDiff())));
    }

    /**
     * Builds the hygiene prompt with a log inventory extracted from the full diff. A chunked review
     * sends only a condensed index as the diff, so the pipeline extracts {@code logs} before
     * condensing.
     */
    static String buildHygienePrompt(
            PRReviewRequest request, List<ChangedLogStatements.Statement> logs) {
        StringBuilder prompt =
                new StringBuilder(HYGIENE_PREAMBLE)
                        .append(HYGIENE_RULES)
                        .append("Return an empty \"lineComments\" list when nothing qualifies.\n\n")
                        .append(ReviewOutputContract.OUTPUT_CONTRACT);
        ReviewPrompts.appendPrMetadata(prompt, request.getPr());
        // Deep reviews carry their pinned semantic evidence into every provider call.
        ReviewPrompts.appendSemanticSections(prompt, request);
        ReviewPrompts.appendOptionalSection(
                prompt,
                "repo_guidelines",
                request.getRepoGuidelines(),
                "Project review guidelines extracted from this repository's contributor docs."
                        + " Apply a rule that changes or adds a hygiene expectation; cite its `##"
                        + " <path>` source in \"rationale\" when it is the basis for a comment:");
        ReviewPrompts.appendOptionalSection(
                prompt,
                "changed_log_statements",
                formatLogInventory(logs),
                "Every log statement on a changed line, extracted mechanically from the diff as"
                        + " `path:line [level] statement`:");
        ReviewPrompts.appendOptionalSection(
                prompt,
                "proto_schema_changes",
                request.getProtoSchemaChanges(),
                "Schema differences PR Pilot computed for each changed .proto file between the"
                        + " version the diff starts from and this branch, as `- path:line RULE"
                        + " [impacts] detail`. The line is an added line to anchor a finding on;"
                        + " reuse-risk marks a removed number or name that is not reserved:");
        ReviewPrompts.appendPrDiff(prompt, request);
        return prompt.toString();
    }

    static String formatLogInventory(List<ChangedLogStatements.Statement> logs) {
        StringBuilder inventory = new StringBuilder();
        for (ChangedLogStatements.Statement log : logs) {
            inventory
                    .append("- ")
                    .append(log.path())
                    .append(':')
                    .append(log.line())
                    .append(" [")
                    .append(log.level())
                    .append("] ")
                    .append(log.text())
                    .append('\n');
        }
        if (logs.size() >= ChangedLogStatements.MAX_STATEMENTS) {
            inventory.append("- (list capped; inventory any further log statements yourself)\n");
        }
        return inventory.toString();
    }
}
