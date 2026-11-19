package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.model.DiffCoverage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Renders the PR comparison from local commit objects, never from working files. */
final class LocalPrDiff {
    private static final Logger log = LoggerFactory.getLogger(LocalPrDiff.class);
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private LocalPrDiff() {}

    static PrDiffResult render(PrDiffService.LocalSource source, String mode) {
        if (source.worktreeDir() == null
                || source.worktreeDir().isBlank()
                || !validSha(source.baseSha())
                || !validSha(source.headSha())) {
            return PrDiffResult.failure(
                    "invalid_request", "Local PR diff requires exact revisions.");
        }
        try {
            Path dir = Path.of(source.worktreeDir()).toRealPath();
            if (!dir.equals(Path.of(readGit(dir, "rev-parse", "--show-toplevel")).toRealPath())
                    || !source.headSha().equals(readGit(dir, "rev-parse", "--verify", "HEAD"))) {
                return unavailable(
                        "The local worktree does not match the pull request head. Reopen the pull request and try again.");
            }
            try {
                readGit(dir, "cat-file", "-e", source.baseSha() + "^{commit}");
            } catch (IOException missingBase) {
                readGit(
                        dir,
                        "fetch",
                        "--quiet",
                        "--no-tags",
                        "--no-write-fetch-head",
                        "--refmap=",
                        "origin",
                        source.baseSha());
            }
            String mergeBase = readGit(dir, "merge-base", source.baseSha(), source.headSha());
            if (!validSha(mergeBase)) {
                return unavailable(
                        "The pull request merge base is not available locally. Fetch the base branch and try again.");
            }
            return git(
                    dir,
                    List.of(
                            "diff",
                            "--no-ext-diff",
                            "--no-textconv",
                            "--no-color",
                            "--no-relative",
                            "--src-prefix=a/",
                            "--dst-prefix=b/",
                            "--find-renames",
                            "--diff-algorithm=myers",
                            "--unified=3",
                            "--submodule=short",
                            mergeBase,
                            source.headSha(),
                            "--"),
                    "validation".equals(mode)
                            ? PrDiffService::boundValidationDiff
                            : PrDiffService::boundReviewDiff,
                    result -> !DiffCoverage.split(result.diff()).coverage().scanComplete());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("Local pull request diff was interrupted.");
        } catch (IOException
                | IllegalArgumentException
                | TimeoutException
                | ExecutionException failure) {
            log.debug("Local PR diff failed: {}", failure.toString());
            return unavailable(
                    "Could not build the local pull request diff. Make sure the checkout contains the PR base and head commits, then try again.");
        }
    }

    private static PrDiffResult unavailable(String message) {
        return PrDiffResult.failure("api_failed", message);
    }

    private static boolean validSha(String sha) {
        return sha != null && SHA.matcher(sha).matches();
    }

    private static String readGit(Path dir, String... args)
            throws IOException, InterruptedException, TimeoutException, ExecutionException {
        return git(
                dir,
                List.of(args),
                in -> {
                    byte[] output = in.readNBytes(8193);
                    if (output.length > 8192) throw new IOException("Unexpected Git output size");
                    return new String(output, StandardCharsets.UTF_8).stripTrailing();
                },
                ignored -> false);
    }

    private static <T> T git(
            Path dir, List<String> args, OutputReader<T> reader, Predicate<T> reachedScanLimit)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        List<String> command =
                new ArrayList<>(
                        List.of("git", "--no-replace-objects", "-c", "core.fsmonitor=false"));
        command.addAll(args);
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(dir.toFile())
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_NO_LAZY_FETCH", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            T output =
                    executor.submit(() -> reader.read(process.getInputStream()))
                            .get(20, TimeUnit.SECONDS);
            if (!reachedScanLimit.test(output)) {
                if (!process.waitFor(
                        Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                    throw new TimeoutException("Local Git command timed out");
                if (process.exitValue() != 0) throw new IOException("Local Git command failed");
            }
            return output;
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            executor.shutdownNow();
            process.getInputStream().close();
        }
    }

    @FunctionalInterface
    private interface OutputReader<T> {
        T read(InputStream input) throws IOException;
    }
}
