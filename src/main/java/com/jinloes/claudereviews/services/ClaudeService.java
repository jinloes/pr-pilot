package com.jinloes.claudereviews.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.application.ApplicationManager;
import com.jinloes.claudereviews.model.ChatMessage;
import com.jinloes.claudereviews.model.PRReviewRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import com.jinloes.claudereviews.services.stream.ContentBlock;
import com.jinloes.claudereviews.services.stream.StreamEvent;
import com.jinloes.claudereviews.settings.PluginSettings;
import com.jinloes.claudereviews.util.ProcessUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

@Slf4j
public class ClaudeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // User-visible status labels surfaced in the progress indicator
    private static final String STATUS_GENERATING = "Generating review…";
    private static final String STATUS_PARSING = "Parsing review…";
    private static final String ERROR_CLAUDE_NOT_FOUND =
            "'claude' not found on PATH. "
                    + "Make sure Claude Code is installed and accessible from your terminal.";

    // Paths containing this directory are internal Claude Code implementation details;
    // suppress them from the tool-use status display
    private static final String CLAUDE_DIR_UNIX = "/.claude/";
    private static final String CLAUDE_DIR_WIN = "\\.claude\\";

    private static final String REVIEW_INSTRUCTIONS =
            """
            You are a staff engineer with a security specialization conducting a formal \
            pre-merge code review. Your mandate: find bugs that will reach production, \
            security vulnerabilities that are real and exploitable (not theoretical), and \
            design decisions that will hurt the team in 6 months. \
            You are not a linter — do not flag style issues already enforced by tooling. \
            The diff is provided below — do NOT re-fetch it. \
            Respond ONLY with a JSON object — no markdown fences, no prose before or after.

            Schema (emit exactly this structure — no extra fields, no comments, no trailing text):
            {
              "summary": "Markdown with sections:\\n## Overview\\n<2-3 sentences: what this PR does and why>\\n## Key Changes\\n- `path/to/file`: <what changed and why>\\n## Risk Areas\\n- <bullet per area needing extra scrutiny: complex logic, missing tests, security-sensitive code, breaking API changes; omit section if none>",
              "lineComments": [
                {
                  "file": "path/to/file.ext",
                  "line": 42,
                  "type": "issue",
                  "body": "single-line string: problem, why it matters, and how to fix"
                }
              ],
              "verdict": "APPROVE"
            }

            Line numbering: take the number after '+' in each @@ header \
            (e.g. @@ -3,7 +3,8 @@ → new file starts at 3), then count forward +1 for \
            each context or added ('+') line; skip deleted ('-') lines entirely.

            Only comment on changed lines ('+' lines in the diff). \
            Do not flag pre-existing issues in unchanged context lines.

            Leave lineComments as [] when you have no specific, actionable points.

            "type" values:
            - "issue" — must fix before merging (bug, security flaw, missing required test)
            - "suggestion" — worth improving but not blocking
            - "note" — informational; no action required

            Each "body" must be a single-line JSON string (no literal newlines). \
            Include: what the problem is, why it matters, and a concrete fix.

            Review checklist (in priority order — stop at the first blocking issue per area):
            1. Correctness: logic bugs, unhandled edge cases, off-by-one errors, \
            null/empty dereferences
            2. Security: injection risks (SQL, command, XSS), missing input validation, \
            exposed secrets or credentials, insecure defaults, broken auth/authz
            3. Test coverage: flag as "issue" any non-trivial new or changed branch or \
            method with no corresponding test; name the specific untested branch; \
            aim for 100% branch coverage on non-trivial changes
            4. Performance: unnecessary allocations in hot paths, N+1 queries, \
            blocking calls on the wrong thread
            5. Design: missing error handling at system boundaries, API surface leaking \
            implementation details, violated encapsulation

            Verdict criteria:
            - APPROVE: no issues found, or only suggestions/notes
            - REQUEST_CHANGES: one or more "issue" type comments that must be resolved
            - COMMENT: questions about intent or approach without a blocking concern
            """;

    private static final String CHAT_PERSONA =
            "You are a staff engineer with a security specialization. "
                    + "Answer questions about code and pull request reviews concisely and"
                    + " precisely.\n\n";

    /**
     * Shells out to the {@code claude} CLI using {@code --output-format stream-json}. Tool-use
     * events are surfaced via {@code onStatus}; the final {@code result} event is parsed into a
     * {@link ReviewResult}.
     *
     * @param request PR metadata, diff, and known patterns
     * @param onStatus called on the EDT with a human-readable description of what Claude is doing
     * @param onProgress called on the EDT with the number of result characters received so far
     * @param onComplete called on the EDT with the parsed {@link ReviewResult}
     * @param onError called on the EDT with a human-readable error message
     */
    public void reviewPR(
            PRReviewRequest request,
            Consumer<String> onStatus,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {

        String prompt = buildPrompt(request);
        ApplicationManager.getApplication()
                .executeOnPooledThread(() -> runReview(prompt, onStatus, onComplete, onError));
    }

    private void runReview(
            String prompt,
            Consumer<String> onStatus,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        try {
            List<String> args =
                    new ArrayList<>(List.of("--verbose", "--output-format", "stream-json"));
            String model = PluginSettings.getInstance().getReviewModel();
            if (!model.isBlank()) {
                args.add("--model");
                args.add(model);
            }
            Process process = buildProcess(args.toArray(new String[0]));
            writeStdin(process, prompt);

            StringBuilder resultBuffer = new StringBuilder();
            try (LineIterator it =
                    IOUtils.lineIterator(process.getInputStream(), StandardCharsets.UTF_8)) {
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.isBlank()) continue;
                    try {
                        StreamEvent event = MAPPER.readValue(line, StreamEvent.class);
                        handleStreamEvent(event, onStatus, resultBuffer);
                    } catch (Exception ignored) {
                        // Claude's stream-json output occasionally includes non-JSON
                        // lines (e.g. progress markers). Skip them rather than aborting.
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                reportProcessFailure(exitCode, process, onError);
            } else {
                ReviewResult result = parseReview(resultBuffer.toString());
                onEdt(() -> onComplete.accept(result));
            }
        } catch (IOException e) {
            reportIoError(e, onError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onEdt(() -> onError.accept("Review interrupted."));
        }
    }

    private void handleStreamEvent(
            StreamEvent event, Consumer<String> onStatus, StringBuilder resultBuffer) {
        switch (StringUtils.defaultString(event.type())) {
            case "assistant" ->
                    event.message()
                            .ifPresent(
                                    message ->
                                            message.content()
                                                    .orElse(List.of())
                                                    .forEach(
                                                            block ->
                                                                    handleContentBlock(
                                                                            block, onStatus)));
            case "result" ->
                    event.result()
                            .ifPresent(
                                    result -> {
                                        resultBuffer.append(result);
                                        onEdt(() -> onStatus.accept(STATUS_PARSING));
                                    });
        }
    }

    private void handleContentBlock(ContentBlock block, Consumer<String> onStatus) {
        switch (StringUtils.defaultString(block.type())) {
            case "tool_use" -> {
                String status =
                        toolUseStatus(block.name().orElse(""), block.input().orElse(Map.of()));
                if (status != null) onEdt(() -> onStatus.accept(status));
            }
            case "text" -> onEdt(() -> onStatus.accept(STATUS_GENERATING));
        }
    }

    /**
     * Formats a tool-use event as a compact CLI-style label, e.g. {@code
     * github/get_file_contents(owner=foo, repo=bar, path=CLAUDE.md)}.
     *
     * <p>Returns {@code null} for Claude Code's internal tool-result temp files, which are an
     * implementation detail and not meaningful to show.
     */
    static String toolUseStatus(String toolName, Map<String, Object> input) {
        // Suppress internal Claude Code temp-file reads
        for (String key : List.of("path", "file_path", "filename")) {
            if (input.get(key) instanceof String p) {
                if (p.contains(CLAUDE_DIR_UNIX) || p.contains(CLAUDE_DIR_WIN)) return null;
            }
        }

        // Strip mcp__ prefix and replace __ separator with / for readability
        String display = Strings.CS.removeStart(toolName, "mcp__").replace("__", "/");

        // Build compact args from scalar input values
        String args =
                input.entrySet().stream()
                        .filter(e -> isScalar(e.getValue()))
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "));

        return display + "(" + args + ")";
    }

    /**
     * Sends a chat message to Claude with optional PR context and conversation history. Responses
     * are streamed as raw text chunks rather than buffered as JSON.
     *
     * @param prContext formatted PR + review background (may be empty)
     * @param history prior turns in this conversation
     * @param userMessage the user's latest message
     * @param onChunk called on the EDT with each new text chunk as it arrives
     * @param onDone called on the EDT with the complete response text
     * @param onError called on the EDT with a human-readable error message
     */
    public void chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {

        String prompt = buildChatPrompt(prContext, history, userMessage);
        ApplicationManager.getApplication()
                .executeOnPooledThread(() -> runChat(prompt, onChunk, onDone, onError));
    }

    private void runChat(
            String prompt,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        try {
            Process process = buildProcess();
            writeStdin(process, prompt);

            StringBuilder buffer = new StringBuilder();
            try (BufferedReader reader =
                    IOUtils.toBufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                // Read in small chunks so onChunk is called incrementally as text
                // arrives, rather than waiting for the full response to buffer.
                char[] buf = new char[256];
                int n;
                while ((n = reader.read(buf, 0, buf.length)) != -1) {
                    String chunk = new String(buf, 0, n);
                    buffer.append(chunk);
                    onEdt(() -> onChunk.accept(chunk));
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                reportProcessFailure(exitCode, process, onError);
            } else {
                onEdt(() -> onDone.accept(buffer.toString()));
            }
        } catch (IOException e) {
            reportIoError(e, onError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onEdt(() -> onError.accept("Chat interrupted."));
        }
    }

    private static String buildChatPrompt(
            String prContext, List<ChatMessage> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(CHAT_PERSONA);
        if (StringUtils.isNotBlank(prContext)) {
            sb.append(prContext).append("\n\n---\n\n");
        }
        history.forEach(
                msg -> {
                    sb.append(msg.role() == ChatMessage.Role.USER ? "User" : "Assistant");
                    sb.append(": ").append(msg.content()).append("\n\n");
                });
        sb.append("User: ").append(userMessage).append("\n");
        return sb.toString();
    }

    /** Builds a {@code claude} process with the base flags plus any extra arguments. */
    private Process buildProcess(String... extraArgs) throws IOException {
        List<String> cmd =
                new ArrayList<>(
                        List.of(findClaudeBinary(), "--print", "--dangerously-skip-permissions"));
        cmd.addAll(List.of(extraArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Use $HOME as working directory so claude can locate its config (~/.claude/).
        // IntelliJ launched from the macOS Dock does not inherit the shell PATH or HOME,
        // and claude relies on HOME to find its credentials and settings.
        pb.directory(new File(System.getProperty("user.home", "/")));
        return pb.start();
    }

    /** Writes {@code prompt} to the process stdin and closes the stream. */
    private static void writeStdin(Process process, String prompt) throws IOException {
        try (OutputStream out = process.getOutputStream()) {
            IOUtils.write(prompt, out, StandardCharsets.UTF_8);
        }
    }

    /** Reads stderr and fires {@code onError} with the exit code and any error text. */
    private static void reportProcessFailure(
            int exitCode, Process process, Consumer<String> onError) throws IOException {
        String stderr = IOUtils.toString(process.getErrorStream(), StandardCharsets.UTF_8);
        String msg = "claude exited " + exitCode + (stderr.isBlank() ? "" : ": " + stderr.trim());
        onEdt(() -> onError.accept(msg));
    }

    /** Fires the appropriate {@code onError} message for an {@link IOException}. */
    private static void reportIoError(IOException e, Consumer<String> onError) {
        String msg = StringUtils.defaultString(e.getMessage());
        // "No such file" is the JVM message on Linux/Mac; "error=2" is POSIX ENOENT
        // surfaced by Windows and some JVM implementations when the executable is missing.
        boolean notFound = msg.contains("No such file") || msg.contains("error=2");
        onEdt(() -> onError.accept(notFound ? ERROR_CLAUDE_NOT_FOUND : msg));
    }

    /** Returns true for JSON scalar types that render meaningfully as {@code key=value} args. */
    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    /** Schedules {@code action} on the Event Dispatch Thread. */
    private static void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action);
    }

    /**
     * Returns the absolute path to the {@code claude} binary by probing common install locations.
     * Falls back to {@code "claude"} (plain name) as a last resort so the OS can try PATH
     * resolution.
     */
    private static String findClaudeBinary() {
        String home = System.getProperty("user.home", "");
        return ProcessUtil.findBinary(
                "claude",
                List.of(
                        home + "/.local/bin/claude", // Claude Code default install
                        home + "/.npm-global/bin/claude", // npm global without sudo
                        "/usr/local/bin/claude", // manual install
                        "/opt/homebrew/bin/claude", // Homebrew
                        "/usr/bin/claude")); // system package managers
    }

    /**
     * Extracts a JSON object from the raw claude output (which may include markdown fences or
     * leading/trailing prose) and deserialises it.
     */
    private static ReviewResult parseReview(String raw) throws IOException {
        String json = raw.strip();

        // Strip markdown code fence (```json ... ``` or ``` ... ```)
        if (json.startsWith("```")) {
            int newline = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (newline > 0 && closing > newline) {
                json = json.substring(newline + 1, closing).strip();
            }
        }

        // Find outermost JSON object in case of surrounding prose
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        return MAPPER.readValue(json, ReviewResult.class);
    }

    static String buildPrompt(PRReviewRequest request) {
        var pr = request.pr();
        StringBuilder prompt =
                new StringBuilder(REVIEW_INSTRUCTIONS)
                        .append(
                                "\n## PR #%d — %s/%s: %s\n"
                                        .formatted(
                                                pr.getNumber(),
                                                pr.getOwner(),
                                                pr.getRepo(),
                                                pr.getTitle()));
        if (StringUtils.isNotBlank(request.projectConventions())) {
            prompt.append("\n### Project Conventions\n")
                    .append(
                            """
                            The following conventions are in effect for this project. \
                            Use them to judge whether the changes follow established patterns \
                            and flag deviations that would violate them:

                            """)
                    .append(request.projectConventions().strip())
                    .append("\n");
        }
        if (StringUtils.isNotBlank(request.knownPatterns())) {
            prompt.append("\n### Previously Verified Patterns\n")
                    .append(
                            """
                            The following patterns have already been verified in this \
                            repository. Do NOT re-flag them:

                            """)
                    .append(request.knownPatterns().strip())
                    .append("\n");
        }
        if (StringUtils.isNotBlank(pr.getBody())) {
            prompt.append("\n### Description\n").append(pr.getBody()).append("\n");
        }
        prompt.append("\n### Diff\n```diff\n").append(request.diff()).append("\n```\n");
        return prompt.toString();
    }
}
