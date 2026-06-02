package com.jinloes.prpilot.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jinloes.prpilot.model.ReviewProvider;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/** Maps low-level runtime/network exceptions to user-actionable copy suitable for UI surfaces. */
public final class UserFacingErrors {

    private static final String TEMPLATE_RESOURCE = "user-facing-errors.yaml";
    private static final String TEMPLATE_GITHUB_AUTH_FAILED = "github_auth_failed";
    private static final String TEMPLATE_PROVIDER_BINARY_MISSING = "provider_binary_missing";
    private static final String TEMPLATE_REQUEST_TIMED_OUT = "request_timed_out";
    private static final String TEMPLATE_INVALID_REVIEW_FORMAT = "invalid_review_format";
    private static final String TEMPLATE_NETWORK_ERROR = "network_error";
    private static final String TEMPLATE_GENERIC_FAILURE = "generic_failure";
    private static final Map<String, String> TEMPLATES = loadTemplates();

    private UserFacingErrors() {}

    public static String forProvider(ReviewProvider provider, Exception e, String operation) {
        String msg = normalize(e);
        String op = StringUtils.defaultIfBlank(operation, "run request");

        if (containsAny(msg, "no such file", "error=2")) {
            String binary = provider == ReviewProvider.COPILOT ? "copilot" : "claude";
            return template(
                    TEMPLATE_PROVIDER_BINARY_MISSING,
                    Map.of("binary", binary, "provider_cli", provider.getDisplayName() + " CLI"));
        }
        if (containsAny(msg, "timed out", "timeout")) {
            return template(TEMPLATE_REQUEST_TIMED_OUT, Map.of("operation", op));
        }
        if (containsAny(
                msg, "failed to parse review json", "unexpected token", "produced no output")) {
            return template(TEMPLATE_INVALID_REVIEW_FORMAT, Map.of());
        }
        if (containsAny(msg, "authentication", "unauthorized", "forbidden", "not signed in")) {
            return template(
                    TEMPLATE_GITHUB_AUTH_FAILED, Map.of("auth_command", "provider auth command"));
        }
        if (containsAny(msg, "connection refused", "unknownhost", "enotfound", "network")) {
            return template(TEMPLATE_NETWORK_ERROR, Map.of("operation", op));
        }

        return template(TEMPLATE_GENERIC_FAILURE, Map.of("operation", op));
    }

    public static String forGitHub(Exception e, String operation) {
        String msg = normalize(e);
        String op = StringUtils.defaultIfBlank(operation, "complete this GitHub operation");

        if (containsAny(
                msg,
                "no github token configured",
                "gh auth",
                "authentication",
                "unauthorized",
                "forbidden")) {
            return template(TEMPLATE_GITHUB_AUTH_FAILED, Map.of("auth_command", "gh auth login"));
        }
        if (containsAny(msg, "timed out", "timeout")) {
            return template(TEMPLATE_REQUEST_TIMED_OUT, Map.of("operation", op));
        }
        if (containsAny(msg, "connection refused", "unknownhost", "enotfound", "network")) {
            return template(TEMPLATE_NETWORK_ERROR, Map.of("operation", op));
        }
        return template(TEMPLATE_GENERIC_FAILURE, Map.of("operation", op));
    }

    static String normalize(Exception e) {
        return StringUtils.defaultString(e.getMessage()).trim().toLowerCase();
    }

    private static boolean containsAny(String source, String... needles) {
        for (String needle : needles) {
            if (source.contains(needle)) return true;
        }
        return false;
    }

    private static Map<String, String> loadTemplates() {
        try (InputStream in =
                UserFacingErrors.class.getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Bundled resource not found on classpath: " + TEMPLATE_RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> root =
                    mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            Object templatesObj = root.get("templates");
            if (!(templatesObj instanceof Map<?, ?> templates)) {
                throw new IllegalStateException(
                        TEMPLATE_RESOURCE + " is missing the 'templates' map");
            }
            Map<String, String> loaded = new HashMap<>();
            for (Map.Entry<?, ?> entry : templates.entrySet()) {
                if (entry.getKey() instanceof String key
                        && entry.getValue() instanceof String value) {
                    loaded.put(key, value);
                }
            }
            return Collections.unmodifiableMap(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + TEMPLATE_RESOURCE, e);
        }
    }

    private static String template(String key, Map<String, String> vars) {
        String rendered = TEMPLATES.getOrDefault(key, key);
        for (Map.Entry<String, String> var : vars.entrySet()) {
            rendered =
                    rendered.replace(
                            "{" + var.getKey() + "}", StringUtils.defaultString(var.getValue()));
        }
        return rendered;
    }
}
