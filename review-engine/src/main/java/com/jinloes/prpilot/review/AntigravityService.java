package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.ChatContext;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewResult;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives Google Antigravity CLI ({@code agy}) in headless {@code stream-json} mode inside an {@link
 * AntigravityIsolatedWorkspace}.
 *
 * <p>Model slugs in Antigravity encode their own thinking tier (for example {@code
 * gemini-3.8-flash-high} or {@code claude-opus-4-6-thinking}) and {@code agy} exits non-zero when
 * {@code --effort} is combined with tier-encoded or non-effort models, so {@code --effort} is
 * deliberately never passed.
 */
public class AntigravityService {

    private static final Logger log = LoggerFactory.getLogger(AntigravityService.class);

    private static final String STATUS_GENERATING = "Generating review…";

    private static final ExecutorService ioExecutor =
            Executors.newCachedThreadPool(
                    runnable -> {
                        Thread thread = new Thread(runnable, "pr-pilot-agy-io");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final File workingDir;
    private final File projectDir;
    private final CancellationToken cancellationToken;
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();

    public AntigravityService() {
        this(null, new CancellationToken());
    }

    public AntigravityService(String projectDir) {
        this(projectDir, new CancellationToken());
    }

    public AntigravityService(String projectDir, CancellationToken cancellationToken) {
        this.projectDir = StringUtils.isNotBlank(projectDir) ? new File(projectDir) : null;
        this.workingDir =
                this.projectDir != null
                        ? this.projectDir
                        : new File(System.getProperty("user.home", "/"));
        this.cancellationToken = Objects.requireNonNull(cancellationToken);
    }

    public ReviewResult reviewPR(
            PRReviewRequest request,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            boolean selfCritique)
            throws IOException, InterruptedException {
        ReviewPassResult pass = reviewPass(request, model, onStatus, onChunk);
        ReviewResult draft = pass.review();
        if (!selfCritique) {
            return CiFindingSuppressor.suppress(draft, request.getCiAnnotations());
        }
        onStatus.accept(ClaudeService.STATUS_REFINING);
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
            log.warn("Antigravity self-critique pass failed; keeping first-pass review", e);
            return CiFindingSuppressor.suppress(draft, request.getCiAnnotations());
        }
    }

    public ReviewResult reviewPR(
            PRReviewRequest request,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        return reviewPR(request, model, onStatus, onChunk, false);
    }

    public ReviewResult reviewPR(PRReviewRequest request, String model, Consumer<String> onStatus)
            throws IOException, InterruptedException {
        return reviewPR(request, model, onStatus, null, false);
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
                "Antigravity review prompt: {} chars — diff {} chars, CI {} chars, commits {} chars",
                prompt.length(),
                StringUtils.length(request.getDiff()),
                StringUtils.length(request.getCiStatus()),
                StringUtils.length(request.getCommits()));
        onStatus.accept(STATUS_GENERATING);
        String raw = runRawReview(prompt, model, onStatus, onChunk, reviewTimeoutMillis(), true);
        try {
            return ReviewPassParser.parse(
                    raw, manifest, workingDir, ReviewResultParser.maxComments(request));
        } catch (Exception parseEx) {
            log.warn("Failed to parse Antigravity review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Antigravity output.", parseEx);
        }
    }

    String completeReviewPrompt(
            String prompt,
            String model,
            boolean allowReadTools,
            long timeoutMillis,
            Consumer<String> onStatus)
            throws IOException, InterruptedException {
        return runRawReview(prompt, model, onStatus, null, timeoutMillis, allowReadTools);
    }

    File projectDir() {
        return projectDir;
    }

    void throwIfCancelled() throws InterruptedException {
        cancellationToken.throwIfCancelled();
    }

    private ReviewResult runReview(
            String prompt,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        String raw = runRawReview(prompt, model, onStatus, onChunk, reviewTimeoutMillis(), true);
        try {
            return ReviewResultParser.parseReview(raw);
        } catch (Exception parseEx) {
            log.warn("Failed to parse Antigravity review JSON (output chars: {})", raw.length());
            throw new IOException("Failed to parse review JSON from Antigravity output.", parseEx);
        }
    }

    private String runRawReview(
            String prompt,
            String model,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            long timeoutMillis,
            boolean allowReadTools)
            throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        Process process = null;
        File stdoutFile = createOutputFile("agy-review-");
        try (AntigravityIsolatedWorkspace workspace =
                AntigravityIsolatedWorkspace.create(projectDir, allowReadTools)) {
            process = buildProcess(workspace, stdoutFile, model);
            activeProcesses.add(process);
            if (cancellationToken.isCancelled()) {
                cancelCurrentRequest();
                cancellationToken.throwIfCancelled();
            }

            String stdinPayload = AntigravityStreamParser.encodeUserPromptLine(prompt);
            Process finalProcess = process;
            CompletableFuture<String> stderrFuture = drainStderr(process);
            CompletableFuture<Void> stdinFuture =
                    CompletableFuture.runAsync(
                            () -> writeStdin(finalProcess, stdinPayload), ioExecutor);

            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcess(process, stdinFuture, stderrFuture);
                throw new IOException("Review timed out — provider pass exceeded its time limit.");
            }
            cancellationToken.throwIfCancelled();
            int exitCode = process.exitValue();
            String stderr = awaitIo(stderrFuture, "read review stderr");
            if (exitCode != 0) {
                stdinFuture.cancel(true);
                AntigravityStreamParser.ErrorInfo errorInfo =
                        AntigravityStreamParser.findErrorInfo(stdoutFile);
                throw new IOException(formatExitFailure(exitCode, stderr, errorInfo));
            }
            awaitIo(stdinFuture, "write the review prompt");

            log.info(
                    "agy stdout file: {} ({} bytes)",
                    stdoutFile.getAbsolutePath(),
                    stdoutFile.length());
            return AntigravityStreamParser.parseStdoutFileToRaw(
                    stdoutFile, workspace.isolatedDir(), stderr, onStatus, onChunk);
        } finally {
            cleanupProcess(process);
            deleteOutputFile(stdoutFile);
        }
    }

    public String chat(
            ChatContext context,
            List<ChatMessage> history,
            String userMessage,
            String model,
            Consumer<String> onChunk)
            throws IOException, InterruptedException {
        String prompt = ReviewChatPrompts.buildChatPrompt(context, history, userMessage);
        return runChat(prompt, model, onChunk);
    }

    public String chat(
            ChatContext context,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk)
            throws IOException, InterruptedException {
        return chat(context, history, userMessage, "", onChunk);
    }

    public String chatWithPrompt(String rawPrompt, String model, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        return runChat(rawPrompt, model, onChunk);
    }

    public String chatWithPrompt(String rawPrompt, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        return chatWithPrompt(rawPrompt, "", onChunk);
    }

    private String runChat(String prompt, String model, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        cancellationToken.throwIfCancelled();
        Process process = null;
        try (AntigravityIsolatedWorkspace workspace =
                AntigravityIsolatedWorkspace.create(projectDir, true)) {
            process = buildProcess(workspace, null, model);
            activeProcesses.add(process);
            if (cancellationToken.isCancelled()) {
                cancelCurrentRequest();
                cancellationToken.throwIfCancelled();
            }

            String stdinPayload = AntigravityStreamParser.encodeUserPromptLine(prompt);
            Process finalProcess = process;
            Path isolatedRoot = workspace.isolatedDir();
            CompletableFuture<Void> stdinFuture =
                    CompletableFuture.runAsync(
                            () -> writeStdin(finalProcess, stdinPayload), ioExecutor);
            CompletableFuture<String> stderrFuture = drainStderr(process);
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(
                            () -> readChatStream(finalProcess, isolatedRoot, onChunk), ioExecutor);

            boolean finished = process.waitFor(chatTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcess(process, stdinFuture, stdoutFuture, stderrFuture);
                throw new IOException("Chat timed out — agy did not finish within 10 minutes.");
            }
            cancellationToken.throwIfCancelled();
            int exitCode = process.exitValue();
            String stderr = awaitIo(stderrFuture, "read chat stderr");
            String response;
            try {
                response = awaitIo(stdoutFuture, "read chat stdout");
            } catch (IOException streamFailure) {
                stdinFuture.cancel(true);
                if (exitCode != 0 && StringUtils.isNotBlank(stderr)) {
                    throw new IOException(
                            "agy exited " + exitCode + ": " + stderr.trim(), streamFailure);
                }
                throw streamFailure;
            }
            if (exitCode != 0) {
                stdinFuture.cancel(true);
                throw new IOException(
                        "agy exited "
                                + exitCode
                                + (StringUtils.isBlank(stderr) ? "" : ": " + stderr.trim()));
            }
            awaitIo(stdinFuture, "write the chat prompt");
            return response;
        } finally {
            cleanupProcess(process);
        }
    }

    public void cancelCurrentRequest() {
        cancellationToken.cancel();
        for (Process process : activeProcesses) {
            if (activeProcesses.remove(process)) {
                ClaudeService.destroyProcessTree(process);
            }
        }
    }

    Process buildProcess(AntigravityIsolatedWorkspace workspace, File stdoutFile, String model)
            throws IOException {
        String binary = findAntigravityBinary();
        boolean cmdShim = BinaryLocator.isWindowsBatchShim(binary);
        List<String> cmd =
                prepareCommand(
                        binary,
                        cmdShim,
                        BinaryLocator.resolveComSpec(System.getenv()),
                        workspace.settingsFile(),
                        model);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workspace.isolatedDir().toFile());
        String userHome = System.getProperty("user.home", "/");
        pb.environment().put("HOME", userHome);
        BinaryLocator.putPathEnv(pb.environment(), userHome);
        if (stdoutFile != null) {
            pb.redirectOutput(stdoutFile);
        }
        return pb.start();
    }

    static List<String> prepareCommand(
            String binary, boolean cmdShim, String comSpec, Path settingsFile, String model)
            throws IOException {
        List<String> cliArgs = new ArrayList<>();
        // Antigravity's Go flag parser defines --print as a string option; "--print=" enables
        // headless print mode with an empty inline prompt so --input-format stream-json drives
        // the turn from stdin without consuming the next flag as the prompt.
        cliArgs.add("--print=");
        cliArgs.add("--input-format");
        cliArgs.add("stream-json");
        cliArgs.add("--output-format");
        cliArgs.add("stream-json");
        cliArgs.add("--agent");
        cliArgs.add(AntigravityIsolatedWorkspace.REVIEWER_AGENT_NAME);
        cliArgs.add("--settings");
        cliArgs.add(settingsFile.toAbsolutePath().toString());
        cliArgs.add("--disable-slash-commands");
        if (StringUtils.isNotBlank(model)) {
            String trimmedModel = model.trim();
            validateCliOptionValue(trimmedModel, "model");
            cliArgs.add("--model");
            cliArgs.add(trimmedModel);
        }
        if (cmdShim) {
            return BinaryLocator.buildCommand(
                    binary,
                    true,
                    Map.of("ComSpec", StringUtils.defaultIfBlank(comSpec, "cmd.exe")),
                    cliArgs);
        }
        List<String> cmd = new ArrayList<>(cliArgs.size() + 1);
        cmd.add(binary);
        cmd.addAll(cliArgs);
        return cmd;
    }

    static void validateCliOptionValue(String value, String optionName) throws IOException {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("-")) {
            throw new IOException("Invalid Antigravity CLI " + optionName + " value.");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                throw new IOException("Invalid Antigravity CLI " + optionName + " value.");
            }
        }
    }

    static String formatExitFailure(
            int exitCode, String stderr, AntigravityStreamParser.ErrorInfo errorInfo) {
        String trimmedStderr = StringUtils.trimToEmpty(stderr);
        String streamError = errorInfo == null ? "" : StringUtils.trimToEmpty(errorInfo.message());
        String detail;
        if (!streamError.isEmpty() && !trimmedStderr.isEmpty()) {
            detail =
                    trimmedStderr.contains(streamError)
                            ? trimmedStderr
                            : streamError + " (" + trimmedStderr + ")";
        } else {
            detail = !streamError.isEmpty() ? streamError : trimmedStderr;
        }
        return "agy exited " + exitCode + (detail.isEmpty() ? "" : ": " + detail);
    }

    long reviewTimeoutMillis() {
        return TimeUnit.MINUTES.toMillis(30);
    }

    long chatTimeoutMillis() {
        return TimeUnit.MINUTES.toMillis(10);
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

    private static void deleteOutputFile(File file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn(
                    "Failed to delete temporary Antigravity output: {}",
                    e.getClass().getSimpleName());
        }
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

    private static String readChatStream(
            Process process, Path isolatedRoot, Consumer<String> onChunk) {
        try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            return AntigravityStreamParser.parseChatStream(reader, isolatedRoot, onChunk);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeStdin(Process process, String payload) {
        try (var out = process.getOutputStream()) {
            IOUtils.write(payload, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T awaitIo(CompletableFuture<T> future, String operation)
            throws IOException, InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException unchecked) {
                throw unchecked.getCause();
            }
            throw new IOException("Failed to " + operation + ".", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out while waiting to " + operation + ".", e);
        }
    }

    private void cleanupProcess(Process process) {
        if (process == null) {
            return;
        }
        activeProcesses.remove(process);
        if (process.isAlive()) {
            ClaudeService.destroyProcessTree(process);
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } else {
            ClaudeService.destroyProcessTree(process);
        }
    }

    private static void terminateProcess(Process process, CompletableFuture<?>... ioFutures)
            throws InterruptedException {
        ClaudeService.destroyProcessTree(process);
        process.waitFor(5, TimeUnit.SECONDS);
        for (CompletableFuture<?> future : ioFutures) {
            future.cancel(true);
        }
    }

    public static String findAntigravityBinary() {
        return BinaryLocator.findBinary("agy", antigravityBinaryCandidates());
    }

    /** Proactive preflight: true when the {@code agy} CLI is resolvable without spawning it. */
    public static boolean isBinaryAvailable() {
        return BinaryLocator.isBinaryAvailable("agy", antigravityBinaryCandidates());
    }

    private static List<String> antigravityBinaryCandidates() {
        return BinaryLocator.candidatePaths("agy");
    }
}
