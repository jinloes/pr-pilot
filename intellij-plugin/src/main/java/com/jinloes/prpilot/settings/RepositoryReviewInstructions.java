package com.jinloes.prpilot.settings;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Remembered per-repository review instructions ({@code repositoryReviewInstructions}).
 *
 * <p>Mirrors {@code vscode-extension/src/repositoryInstructions.ts}: keys are lowercase {@code
 * owner/repo} because GitHub treats repository names case-insensitively, and the remembered text is
 * folded into the engine's existing {@code customInstructions} input rather than a new engine
 * field.
 */
public final class RepositoryReviewInstructions {

    public static final int MAX_INSTRUCTIONS_LENGTH = 10_000;
    public static final int MAX_REPOSITORIES = 200;

    private static final Pattern REPOSITORY_KEY =
            Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,38})/[a-z0-9._-]{1,100}$");

    private RepositoryReviewInstructions() {}

    /** Returns the lowercase {@code owner/repo} key, or {@code null} for an invalid GitHub name. */
    public static String repositoryKey(String owner, String repo) {
        String key =
                (StringUtils.trimToEmpty(owner) + "/" + StringUtils.trimToEmpty(repo))
                        .toLowerCase(Locale.ROOT);
        return REPOSITORY_KEY.matcher(key).matches() ? key : null;
    }

    /**
     * Sanitizes stored entries: keeps valid keys, trims values, drops blank or over-limit values,
     * and keeps the first entry when keys collide after lowercasing.
     */
    public static Map<String, String> normalize(Map<String, String> raw) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (normalized.size() >= MAX_REPOSITORIES) {
                break;
            }
            String key = keyOf(entry.getKey());
            String value = StringUtils.trimToEmpty(entry.getValue());
            if (key == null || value.isEmpty() || value.length() > MAX_INSTRUCTIONS_LENGTH) {
                continue;
            }
            normalized.putIfAbsent(key, value);
        }
        return normalized;
    }

    /**
     * Returns a copy with {@code key} set to the trimmed instructions, or removed when blank.
     * Returns {@code null} when the text is over the limit or a new key would exceed the cap.
     */
    public static Map<String, String> with(
            Map<String, String> current, String key, String instructions) {
        String value = StringUtils.trimToEmpty(instructions);
        if (value.length() > MAX_INSTRUCTIONS_LENGTH) {
            return null;
        }
        Map<String, String> next = new LinkedHashMap<>(current);
        if (value.isEmpty()) {
            next.remove(key);
            return next;
        }
        if (!next.containsKey(key) && next.size() >= MAX_REPOSITORIES) {
            return null;
        }
        next.put(key, value);
        return next;
    }

    /** Places the remembered repository instructions ahead of the per-review or default ones. */
    public static String compose(String repository, String remembered, String base) {
        String rememberedText = StringUtils.trimToEmpty(remembered);
        String baseText = StringUtils.trimToEmpty(base);
        if (rememberedText.isEmpty()) {
            return baseText;
        }
        String section = "Instructions remembered for " + repository + ":\n" + rememberedText;
        return baseText.isEmpty() ? section : section + "\n\n" + baseText;
    }

    private static String keyOf(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        int slash = rawKey.indexOf('/');
        return slash > 0
                ? repositoryKey(rawKey.substring(0, slash), rawKey.substring(slash + 1))
                : null;
    }
}
