package com.jinloes.prpilot.review;

import java.io.IOException;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * A single tool-free prompt completion through the configured review provider.
 *
 * <p>Exists for offline tools such as the review benchmark's judge, which need the same Claude or
 * Copilot transport a review uses without the review pipeline around it. Read tools are always
 * disabled: a completion answers from the prompt alone.
 */
public final class PromptCompleter {
    private final Completion completion;

    PromptCompleter(Completion completion) {
        this.completion = completion;
    }

    /**
     * Creates a completer for {@code provider} ({@code claude} or {@code copilot}). {@code effort}
     * and {@code configDir} apply to Copilot only; blank values use provider defaults.
     */
    public static PromptCompleter forProvider(
            String provider, String projectDir, String model, String effort, String configDir) {
        if ("copilot".equals(provider)) {
            CopilotService service = new CopilotService(projectDir);
            return new PromptCompleter(
                    (prompt, timeoutMillis, onStatus) ->
                            service.completeReviewPrompt(
                                    prompt,
                                    StringUtils.trimToNull(model),
                                    StringUtils.trimToNull(effort),
                                    false,
                                    StringUtils.trimToNull(configDir),
                                    false,
                                    timeoutMillis,
                                    onStatus));
        }
        if ("claude".equals(provider)) {
            ClaudeService service = new ClaudeService(projectDir);
            return new PromptCompleter(
                    (prompt, timeoutMillis, onStatus) ->
                            service.completeReviewPrompt(
                                    prompt,
                                    StringUtils.trimToNull(model),
                                    onStatus,
                                    timeoutMillis,
                                    false));
        }
        throw new IllegalArgumentException("Unknown provider: " + provider);
    }

    /** Returns the provider's raw text answer to {@code prompt}. */
    public String complete(String prompt, long timeoutMillis)
            throws IOException, InterruptedException {
        if (StringUtils.isBlank(prompt)) throw new IllegalArgumentException("Prompt is blank.");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Timeout must be positive.");
        return StringUtils.defaultString(completion.complete(prompt, timeoutMillis, status -> {}));
    }

    interface Completion {
        String complete(String prompt, long timeoutMillis, Consumer<String> onStatus)
                throws IOException, InterruptedException;
    }
}
