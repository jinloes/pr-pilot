package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shells out to the {@code claude} CLI using {@code --output-format stream-json}. Runs
 * synchronously on the calling thread — callers are responsible for dispatching to a background
 * thread if needed.
 *
 * <p>Java port of the former {@code core/jvmMain} Kotlin {@code ClaudeService}; behavior is
 * unchanged. See {@code ARCHITECTURE.md} "Module boundaries" for why review generation now lives in
 * this plain Java engine rather than KMP {@code core}.
 */
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private static final AtomicLong IO_THREAD_SEQUENCE = new AtomicLong();
    private static final Executor IO_EXECUTOR =
            Executors.newCachedThreadPool(
                    task -> {
                        Thread thread =
                                new Thread(
                                        task,
                                        "pr-pilot-claude-io-"
                                                + IO_THREAD_SEQUENCE.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });

    static final String STATUS_REFINING = "Refining review…";

    static final int DEFAULT_MAX_TURNS = 15;

    /**
     * Turn budget for review-engine passes. Reviews investigate beyond the diff (callers, tests,
     * history), so they get more room than chat, which keeps {@link #DEFAULT_MAX_TURNS}.
     */
    static final int REVIEW_MAX_TURNS = 40;

    static final int RESUME_MAX_TURNS = 3;

    /**
     * Read-only Claude Code tools the review is allowed to use against the PR-branch worktree.
     * Deliberately excludes any mutating, shell, or network tool so an untrusted PR cannot cause
     * side effects. Passed as the {@code --tools} allowlist value.
     */
    static final String READ_ONLY_TOOLS = "Read Grep Glob";

    /**
     * The sandboxing arguments passed on every Claude spawn, in order. Together they restrict the
     * process to {@link #READ_ONLY_TOOLS}, stop it prompting for escalation, and deny it any MCP
     * server the user has configured — an untrusted PR must not reach a network or write tool
     * through an inherited MCP config. Held as a constant so the guarantee is assertable without
     * spawning a process; {@code ClaudeServiceTest} pins the exact contents.
     */
    static final List<String> SAFE_CLI_ARGS =
            List.of(
                    "--tools",
                    READ_ONLY_TOOLS,
                    "--permission-mode",
                    "dontAsk",
                    "--strict-mcp-config",
                    "--mcp-config",
                    "{\"mcpServers\":{}}",
                    "--setting-sources",
                    "user");

    static final List<String> NO_TOOL_CLI_ARGS =
            List.of(
                    "--tools",
                    "",
                    "--permission-mode",
                    "dontAsk",
                    "--strict-mcp-config",
                    "--mcp-config",
                    "{\"mcpServers\":{}}",
                    "--setting-sources",
                    "user");

    private static final String RESUME_NUDGE =
            "You have gathered sufficient context. Output the review JSON now following the"
                    + " schema exactly — no more tool calls.";

    private final File workingDir;
    private final File projectDir;
    private final CancellationToken cancellationToken;
    private final Executor ioExecutor;

    /** Processes executing review or chat requests; several run at once during the rules pass. */
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();

    public ClaudeService() {
        this(null, new CancellationToken(), IO_EXECUTOR);
    }

    public ClaudeService(String projectDir) {
        this(projectDir, new CancellationToken(), IO_EXECUTOR);
    }

    public ClaudeService(String projectDir, CancellationToken cancellationToken) {
        this(projectDir, cancellationToken, IO_EXECUTOR);
    }

    ClaudeService(String projectDir, CancellationToken cancellationToken, Executor ioExecutor) {
        this.projectDir = StringUtils.isNotBlank(projectDir) ? new File(projectDir) : null;
        this.workingDir =
                this.projectDir != null
                        ? this.projectDir
                        : new File(System.getProperty("user.home", "/"));
        this.cancellationToken = Objects.requireNonNull(cancellationToken);
        this.ioExecutor = Objects.requireNonNull(ioExecutor);
    }

    public ReviewResult reviewPR(PRReviewRequest request, String model, Consumer<String> onStatus)
            throws IOException, InterruptedException {
        return reviewPR(request, model, false, onStatus, null);
    }

    /**
     * Like {@link #reviewPR(PRReviewRequest, String, Consumer)} but also calls {@code onChunk} with
     * streaming text and thinking content as it arrives. The first argument is the kind ("text" or
     * "thinking"); the second is the content string. Pass null to suppress chunk callbacks.
     */
    public ReviewResult reviewPR(
            PRReviewRequest request,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        return reviewPR(request, model, false, onStatus, onChunk);
    }

    /**
     * Generates a review and, when {@code selfCritique} is true, runs a second validation pass that
     * re-checks each finding against the diff and drops misattributed/unsupported ones before
     * returning. The critique pass falls back to the first-pass review if it fails.
     */
    public ReviewResult reviewPR(
            PRReviewRequest request,
            String model,
            boolean selfCritique,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        ReviewPassResult pass = reviewPass(request, model, onStatus, onChunk);
        ReviewResult draft = pass.review();
        if (!selfCritique) {
            return CiFindingSuppressor.suppress(draft, request.getCiAnnotations());
        }
        onStatus.accept(STATUS_REFINING);
        try {
            return CiFindingSuppressor.suppress(
                    runReview(
                            ReviewCritiquePrompts.buildCritiquePrompt(request, draft),
                            model,
                            onStatus,
                            onChunk),
                    request.getCiAnnotations());
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception e) {
            log.warn("Self-critique pass failed; keeping first-pass review", e);
            return CiFindingSuppressor.suppress(draft, request.getCiAnnotations());
        }
    }

    ReviewPassResult reviewPass(
            PRReviewRequest request,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        InspectionManifest manifest = InspectionManifest.fromDiff(request.getDiff());
        String prompt = ReviewPrompts.buildPrompt(request, manifest);
        log.info(
                "Review prompt: {} chars — diff {} chars, CI {} chars, commits {} chars",
                prompt.length(),
                StringUtils.length(request.getDiff()),
                StringUtils.length(request.getCiStatus()),
                StringUtils.length(request.getCommits()));
        String raw =
                runRawReview(prompt, model, onStatus, onChunk, reviewTimeoutMillis(), true, true);
        try {
            return ReviewPassParser.parse(
                    raw, manifest, workingDir, ReviewResultParser.maxComments(request));
        } catch (Exception parseEx) {
            log.warn("Failed to parse Claude review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Claude output.", parseEx);
        }
    }

    String completeReviewPrompt(
            String prompt,
            String model,
            Consumer<String> onStatus,
            long timeoutMillis,
            boolean allowReadTools)
            throws IOException, InterruptedException {
        return runRawReview(prompt, model, onStatus, null, timeoutMillis, allowReadTools, false);
    }

    void throwIfCancelled() throws InterruptedException {
        cancellationToken.throwIfCancelled();
    }

    /** The PR checkout this service reviews, or null when it was built without one. */
    File projectDir() {
        return projectDir;
    }

    private ReviewResult runReview(
            String prompt,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        String raw =
                runRawReview(prompt, model, onStatus, onChunk, reviewTimeoutMillis(), true, true);
        try {
            return ReviewResultParser.parseReview(raw);
        } catch (Exception parseEx) {
            log.warn("Failed to parse Claude review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Claude output.", parseEx);
        }
    }

    private String runRawReview(
            String prompt,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            long timeoutMillis,
            boolean allowReadTools,
            boolean allowResume)
            throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        Process process = null;
        File stdoutFile = createOutputFile("claude-review-");
        try {
            List<String> args =
                    new ArrayList<>(List.of("--verbose", "--output-format", "stream-json"));
            if (StringUtils.isNotBlank(model)) {
                args.add("--model");
                args.add(model);
            }
            process =
                    allowReadTools
                            ? buildProcess(
                                    stdoutFile, REVIEW_MAX_TURNS, args.toArray(new String[0]))
                            : buildProcessWithoutTools(
                                    stdoutFile, REVIEW_MAX_TURNS, args.toArray(new String[0]));
            activeProcesses.add(process);
            if (cancellationToken.isCancelled()) {
                cancelCurrentRequest();
                cancellationToken.throwIfCancelled();
            }

            // Write stdin and drain stderr concurrently so a large prompt does not fill the OS
            // stdin pipe buffer and stall until claude finishes startup.
            Process finalProcess = process;
            CompletableFuture<String> stderrFuture = drainStderr(process);
            CompletableFuture<Void> stdinFuture =
                    CompletableFuture.runAsync(() -> writeStdin(finalProcess, prompt), ioExecutor);

            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcess(process, stdinFuture, stderrFuture);
                throw new IOException("Review timed out — provider pass exceeded its time limit.");
            }
            cancellationToken.throwIfCancelled();
            awaitIo(stdinFuture, "write the review prompt");
            int exitCode = process.exitValue();
            String stderr = awaitIo(stderrFuture, "read review stderr");
            if (exitCode != 0) {
                ClaudeStreamParser.ErrorInfo errorInfo =
                        ClaudeStreamParser.findErrorInfo(stdoutFile);
                if (allowResume
                        && "error_max_turns".equals(errorInfo.subtype())
                        && errorInfo.sessionId() != null) {
                    onStatus.accept("Resuming review session…");
                    return runResumeRaw(errorInfo.sessionId(), model, onStatus, onChunk);
                }
                String msg =
                        "error_max_turns".equals(errorInfo.subtype())
                                ? "Review hit the turn limit — the PR may be too large. Try again."
                                : "claude exited "
                                        + exitCode
                                        + (StringUtils.isBlank(stderr) ? "" : ": " + stderr.trim());
                throw new IOException(msg);
            }

            log.info(
                    "claude stdout file: {} ({} bytes)",
                    stdoutFile.getAbsolutePath(),
                    stdoutFile.length());
            return ClaudeStreamParser.parseStdoutFileToRaw(stdoutFile, stderr, onStatus, onChunk);
        } finally {
            if (process != null) activeProcesses.remove(process);
            if (process != null) {
                process.destroy();
            }
            deleteOutputFile(stdoutFile);
        }
    }

    private String runResumeRaw(
            String sessionId,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        Process process = null;
        File stdoutFile = createOutputFile("claude-resume-");
        try {
            List<String> args =
                    new ArrayList<>(
                            List.of(
                                    "--verbose",
                                    "--output-format",
                                    "stream-json",
                                    "--resume",
                                    sessionId));
            if (StringUtils.isNotBlank(model)) {
                args.add("--model");
                args.add(model);
            }
            process = buildProcess(stdoutFile, RESUME_MAX_TURNS, args.toArray(new String[0]));
            activeProcesses.add(process);
            if (cancellationToken.isCancelled()) {
                cancelCurrentRequest();
                cancellationToken.throwIfCancelled();
            }

            Process finalProcess = process;
            CompletableFuture<String> stderrFuture = drainStderr(process);
            CompletableFuture<Void> stdinFuture =
                    CompletableFuture.runAsync(
                            () -> writeStdin(finalProcess, RESUME_NUDGE), ioExecutor);

            boolean finished = process.waitFor(resumeTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcess(process, stdinFuture, stderrFuture);
                throw new IOException(
                        "Resume timed out — claude did not finish within 10 minutes.");
            }
            cancellationToken.throwIfCancelled();
            awaitIo(stdinFuture, "write the resume prompt");
            int exitCode = process.exitValue();
            String stderr = awaitIo(stderrFuture, "read resume stderr");
            if (exitCode != 0) {
                ClaudeStreamParser.ErrorInfo errorInfo =
                        ClaudeStreamParser.findErrorInfo(stdoutFile);
                String msg =
                        "error_max_turns".equals(errorInfo.subtype())
                                ? "Review hit the turn limit even after resume — the PR may be too large."
                                : "claude exited "
                                        + exitCode
                                        + " during resume"
                                        + (StringUtils.isBlank(stderr) ? "" : ": " + stderr.trim());
                throw new IOException(msg);
            }

            return ClaudeStreamParser.parseStdoutFileToRaw(stdoutFile, stderr, onStatus, onChunk);
        } finally {
            if (process != null) activeProcesses.remove(process);
            if (process != null) {
                process.destroy();
            }
            deleteOutputFile(stdoutFile);
        }
    }

    File createOutputFile(String prefix) throws IOException {
        try {
            return Files.createTempFile(
                            prefix,
                            ".ndjson",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")))
                    .toFile();
        } catch (UnsupportedOperationException e) {
            return Files.createTempFile(prefix, ".ndjson").toFile();
        }
    }

    private void deleteOutputFile(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("Failed to delete temporary Claude output: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Sends a chat message to Claude. Runs synchronously on the calling thread.
     *
     * @param prContext formatted PR + review background (may be empty)
     * @param history prior turns in this conversation
     * @param userMessage the user's latest message
     * @param onChunk called with each new text chunk as it arrives
     * @return the complete response text
     */
    public String chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk)
            throws IOException, InterruptedException {
        String prompt = ReviewChatPrompts.buildChatPrompt(prContext, history, userMessage);
        return runChat(prompt, onChunk);
    }

    /**
     * Sends a pre-built prompt directly to Claude without wrapping it in {@link
     * ReviewChatPrompts#buildChatPrompt}. Use this when the caller has already assembled the full
     * prompt (e.g. via {@link ReviewChatPrompts#buildFocusedChatPrompt}) and does not want any
     * additional wrapping.
     */
    public String chatWithPrompt(String rawPrompt, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        return runChat(rawPrompt, onChunk);
    }

    private String runChat(String prompt, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        Process process = null;
        try {
            process = buildProcess();
            activeProcesses.add(process);
            if (cancellationToken.isCancelled()) {
                cancelCurrentRequest();
                cancellationToken.throwIfCancelled();
            }
            Process finalProcess = process;
            CompletableFuture<Void> stdinFuture =
                    CompletableFuture.runAsync(() -> writeStdin(finalProcess, prompt), ioExecutor);
            CompletableFuture<String> stderrFuture = drainStderr(process);
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(
                            () -> readChatOutput(finalProcess, onChunk), ioExecutor);

            boolean finished = process.waitFor(chatTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcess(process, stdinFuture, stdoutFuture, stderrFuture);
                throw new IOException("Chat timed out — claude did not finish within 10 minutes.");
            }
            cancellationToken.throwIfCancelled();
            awaitIo(stdinFuture, "write the chat prompt");
            int exitCode = process.exitValue();
            String response = awaitIo(stdoutFuture, "read chat stdout");
            String stderr = awaitIo(stderrFuture, "read chat stderr");
            if (exitCode != 0) {
                throw new IOException(
                        "claude exited "
                                + exitCode
                                + (StringUtils.isBlank(stderr) ? "" : ": " + stderr.trim()));
            }
            return response;
        } finally {
            if (process != null) activeProcesses.remove(process);
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Cancels every running review or chat request, if any. The blocked calling thread will receive
     * an IOException.
     */
    public void cancelCurrentRequest() {
        cancellationToken.cancel();
        for (Process process : activeProcesses) {
            if (activeProcesses.remove(process)) {
                process.destroyForcibly();
            }
        }
    }

    Process buildProcess(String... extraArgs) throws IOException {
        return buildProcess(null, DEFAULT_MAX_TURNS, extraArgs);
    }

    Process buildProcess(File stdoutFile, int maxTurns, String... extraArgs) throws IOException {
        return buildProcess(stdoutFile, maxTurns, SAFE_CLI_ARGS, extraArgs);
    }

    Process buildProcessWithoutTools(File stdoutFile, int maxTurns, String... extraArgs)
            throws IOException {
        return buildProcess(stdoutFile, maxTurns, NO_TOOL_CLI_ARGS, extraArgs);
    }

    private Process buildProcess(
            File stdoutFile, int maxTurns, List<String> safeArgs, String... extraArgs)
            throws IOException {
        List<String> cmd = new ArrayList<>(List.of(findClaudeBinary(), "--print"));
        cmd.addAll(safeArgs);
        cmd.addAll(List.of("--max-turns", String.valueOf(maxTurns)));
        cmd.addAll(List.of(extraArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        String userHome = System.getProperty("user.home", "/");
        pb.environment().put("HOME", userHome);
        // GUI-launched IDEs do not inherit shell PATH configuration.
        String existingPath = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH", BinaryLocator.providerPath(userHome, existingPath));
        if (stdoutFile != null) {
            pb.redirectOutput(stdoutFile);
        }
        return pb.start();
    }

    long reviewTimeoutMillis() {
        return TimeUnit.MINUTES.toMillis(30);
    }

    long resumeTimeoutMillis() {
        return TimeUnit.MINUTES.toMillis(10);
    }

    long chatTimeoutMillis() {
        return TimeUnit.MINUTES.toMillis(10);
    }

    private CompletableFuture<String> drainStderr(Process process) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return IOUtils.toString(process.getErrorStream(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        return "";
                    }
                },
                ioExecutor);
    }

    private static String readChatOutput(Process process, Consumer<String> onChunk) {
        StringBuilder buffer = new StringBuilder();
        try (var reader =
                IOUtils.toBufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] chars = new char[256];
            int count;
            while ((count = reader.read(chars, 0, chars.length)) != -1) {
                String chunk = new String(chars, 0, count);
                buffer.append(chunk);
                onChunk.accept(chunk);
            }
            return buffer.toString();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static <T> T awaitIo(CompletableFuture<T> future, String operation)
            throws IOException, InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.io.UncheckedIOException unchecked) {
                throw unchecked.getCause();
            }
            throw new IOException("Failed to " + operation + ".", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out while waiting to " + operation + ".", e);
        }
    }

    private static void terminateProcess(Process process, CompletableFuture<?>... ioFutures)
            throws InterruptedException {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        for (CompletableFuture<?> future : ioFutures) {
            future.cancel(true);
        }
    }

    private static void writeStdin(Process process, String prompt) {
        try (var out = process.getOutputStream()) {
            IOUtils.write(prompt, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static String findClaudeBinary() {
        return BinaryLocator.findBinary("claude", claudeBinaryCandidates());
    }

    /** Proactive preflight: true when the {@code claude} CLI is resolvable without spawning it. */
    public static boolean isBinaryAvailable() {
        return BinaryLocator.isBinaryAvailable("claude", claudeBinaryCandidates());
    }

    private static List<String> claudeBinaryCandidates() {
        String home = System.getProperty("user.home", "");
        return List.of(
                home + "/.local/bin/claude", // Claude Code default install
                home + "/.npm-global/bin/claude", // npm global without sudo
                "/usr/local/bin/claude", // manual install
                "/opt/homebrew/bin/claude", // Homebrew
                "/usr/bin/claude" // system package managers
                );
    }
}
