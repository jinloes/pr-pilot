package com.jinloes.claudereviews.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * Resolves an Anthropic API key from available sources, in priority order:
 *
 * <ol>
 *   <li>macOS Keychain — service {@code "Claude Code"}, written by the {@code claude auth login}
 *       flow ({@code security find-generic-password -s "Claude Code" -w}).
 *   <li>{@code ANTHROPIC_API_KEY} environment variable.
 * </ol>
 *
 * Returns {@link Optional#empty()} when neither source yields a key; callers should fall back to
 * the {@code claude} CLI for model access.
 */
@Slf4j
public class AnthropicApiKeyLocator {

    static final String KEYCHAIN_SERVICE = "Claude Code";
    static final String ENV_VAR = "ANTHROPIC_API_KEY";

    private final List<String> keychainCmd;
    private final Function<String, String> envLookup;

    public AnthropicApiKeyLocator() {
        this(
                List.of("security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-w"),
                System::getenv);
    }

    AnthropicApiKeyLocator(List<String> keychainCmd, Function<String, String> envLookup) {
        this.keychainCmd = keychainCmd;
        this.envLookup = envLookup;
    }

    /**
     * Returns the first available Anthropic API key, or empty if none is found. Never throws;
     * failures from each source are logged at debug level and skipped.
     */
    public Optional<String> locate() {
        Optional<String> fromKeychain = fromKeychain();
        if (fromKeychain.isPresent()) {
            return fromKeychain;
        }
        return fromEnv();
    }

    Optional<String> fromKeychain() {
        try {
            ProcessBuilder pb = new ProcessBuilder(keychainCmd);
            pb.environment().put("HOME", System.getProperty("user.home"));
            Process process = pb.start();
            String stdout =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                            .trim();
            // Drain stderr to avoid the subprocess blocking on a full pipe.
            process.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exit = process.waitFor();
            if (exit == 0 && StringUtils.isNotBlank(stdout)) {
                log.debug(
                        "Anthropic API key resolved from macOS Keychain (service={})",
                        KEYCHAIN_SERVICE);
                return Optional.of(stdout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Keychain lookup interrupted");
        } catch (IOException e) {
            log.debug("Keychain lookup unavailable: {}", e.getMessage());
        }
        return Optional.empty();
    }

    Optional<String> fromEnv() {
        String val = envLookup.apply(ENV_VAR);
        if (StringUtils.isNotBlank(val)) {
            log.debug("Anthropic API key resolved from {} environment variable", ENV_VAR);
            return Optional.of(val);
        }
        return Optional.empty();
    }
}
