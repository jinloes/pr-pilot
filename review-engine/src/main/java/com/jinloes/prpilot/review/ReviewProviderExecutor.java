package com.jinloes.prpilot.review;

import com.jinloes.prpilot.model.PRReviewRequest;
import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * A review provider as {@link ReviewPipelineService} and {@link ReviewRulesPass} drive it: the
 * first review pass, prompt completions for the later stages, and cooperative cancellation. {@link
 * ClaudeExecutor} and {@link CopilotExecutor} adapt the two CLI services.
 */
interface ReviewProviderExecutor {
    ReviewPassResult primary(
            PRReviewRequest request, Consumer<String> onStatus, BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException;

    String complete(
            String prompt,
            long timeoutMillis,
            boolean allowReadTools,
            boolean allowMcp,
            Consumer<String> onStatus)
            throws IOException, InterruptedException;

    void checkCancelled() throws InterruptedException;

    /** The working directory the provider reviews in, or null when it has none. */
    default File projectDir() {
        return null;
    }

    /** The model name shown in review progress, or blank when unknown. */
    default String displayModel() {
        return "";
    }

    record ClaudeExecutor(ClaudeService service, String model) implements ReviewProviderExecutor {
        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException, InterruptedException {
            return service.reviewPass(request, model, onStatus, onChunk);
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException, InterruptedException {
            return service.completeReviewPrompt(
                    prompt, model, onStatus, timeoutMillis, allowReadTools);
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            service.throwIfCancelled();
        }

        @Override
        public File projectDir() {
            return service.projectDir();
        }

        @Override
        public String displayModel() {
            return StringUtils.defaultIfBlank(model, "Claude default");
        }
    }

    record CopilotExecutor(
            CopilotService service,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir)
            implements ReviewProviderExecutor {
        @Override
        public ReviewPassResult primary(
                PRReviewRequest request,
                Consumer<String> onStatus,
                BiConsumer<String, String> onChunk)
                throws IOException, InterruptedException {
            return service.reviewPass(
                    request, model, effort, onStatus, onChunk, inheritMcp, configDir);
        }

        @Override
        public String complete(
                String prompt,
                long timeoutMillis,
                boolean allowReadTools,
                boolean allowMcp,
                Consumer<String> onStatus)
                throws IOException, InterruptedException {
            return service.completeReviewPrompt(
                    prompt,
                    model,
                    effort,
                    allowMcp && inheritMcp,
                    configDir,
                    allowReadTools,
                    timeoutMillis,
                    onStatus);
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            service.throwIfCancelled();
        }

        @Override
        public File projectDir() {
            return service.projectDir();
        }

        @Override
        public String displayModel() {
            return StringUtils.defaultIfBlank(model, "Copilot default");
        }
    }
}
