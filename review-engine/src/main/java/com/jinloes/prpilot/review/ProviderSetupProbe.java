package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.ReviewProvider;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** Side-effect-free, bounded provider readiness checks used by first-run onboarding. */
public final class ProviderSetupProbe {
    private static final long AUTH_TIMEOUT_SECONDS = 5;
    private final Predicate<ReviewProvider> binaryAvailable;
    private final AuthCheck authCheck;

    public ProviderSetupProbe() {
        this(ProviderSetupProbe::isBinaryAvailable, ProviderSetupProbe::probeClaudeAuth);
    }

    ProviderSetupProbe(Predicate<ReviewProvider> binaryAvailable, AuthCheck authCheck) {
        this.binaryAvailable = binaryAvailable;
        this.authCheck = authCheck;
    }

    public Result probe(ReviewProvider provider) {
        return probe(provider, true);
    }

    /**
     * Probes CLI binary availability, optionally also checking non-interactive authentication
     * readiness when {@code checkAuthentication} is true.
     */
    public Result probe(ReviewProvider provider, boolean checkAuthentication) {
        boolean available = binaryAvailable.test(provider);
        String command = provider == ReviewProvider.COPILOT ? "copilot login" : "claude auth login";
        if (!available) {
            return new Result(false, "missing", "unavailable", command);
        }
        if (!checkAuthentication || provider == ReviewProvider.COPILOT) {
            return new Result(true, "ready", "unverified", command);
        }
        try {
            return new Result(true, "ready", authCheck.authenticationStatus().wireValue(), command);
        } catch (IOException | TimeoutException exception) {
            return new Result(true, "ready", "unverified", command);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(true, "ready", "unverified", command);
        }
    }

    public record Result(
            boolean available,
            String binaryStatus,
            String authenticationStatus,
            String authCommand) {}

    private static boolean isBinaryAvailable(ReviewProvider provider) {
        return provider == ReviewProvider.COPILOT
                ? CopilotService.isBinaryAvailable()
                : ClaudeService.isBinaryAvailable();
    }

    private static AuthenticationStatus probeClaudeAuth()
            throws IOException, InterruptedException, TimeoutException {
        ProcessBuilder pb =
                buildClaudeAuthProcess(
                        ClaudeService.findClaudeBinary(),
                        BinaryLocator.isWindows(),
                        System.getenv(),
                        System.getProperty("user.home", "/"));
        BoundedProcessRunner.ProcessResult result =
                new BoundedProcessRunner().run(pb, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return classifyClaudeAuthResult(result);
    }

    static ProcessBuilder buildClaudeAuthProcess(
            String binary, boolean windows, Map<String, String> env, String userHome)
            throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder(
                        BinaryLocator.buildCommand(
                                binary, windows, env, List.of("auth", "status", "--text")));
        pb.environment().put("HOME", userHome);
        BinaryLocator.putPathEnv(pb.environment(), userHome);
        return pb;
    }

    static AuthenticationStatus classifyClaudeAuthResult(
            BoundedProcessRunner.ProcessResult result) {
        if (result.outputTruncated()) {
            return AuthenticationStatus.UNVERIFIED;
        }
        String output = result.output().toLowerCase(Locale.ROOT);
        if (output.contains("unknown command")
                || output.contains("unrecognized command")
                || output.contains("unsupported command")
                || output.contains("invalid command")
                || output.contains("unknown option")
                || output.contains("unrecognized option")
                || output.contains("unexpected argument")) {
            return AuthenticationStatus.UNVERIFIED;
        }
        if (result.exitCode() == 0) {
            return AuthenticationStatus.READY;
        }
        if (output.contains("not logged in")
                || output.contains("not authenticated")
                || output.contains("signed out")
                || output.contains("logged out")) {
            return AuthenticationStatus.UNAVAILABLE;
        }
        return AuthenticationStatus.UNVERIFIED;
    }

    enum AuthenticationStatus {
        READY("ready"),
        UNAVAILABLE("unavailable"),
        UNVERIFIED("unverified");

        private final String wireValue;

        AuthenticationStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }
    }

    @FunctionalInterface
    interface AuthCheck {
        AuthenticationStatus authenticationStatus()
                throws IOException, InterruptedException, TimeoutException;
    }
}
