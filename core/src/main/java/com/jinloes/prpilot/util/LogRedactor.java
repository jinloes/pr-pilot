package com.jinloes.prpilot.util;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Redacts credentials, bearer headers, URL userinfo, and API tokens from diagnostic log strings and
 * {@link Throwable} graphs while preserving exception class names, stack traces, causes, and
 * suppressed exceptions.
 */
public final class LogRedactor {

    private static final String REDACTED = "<redacted>";

    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9_]{8,}|github_pat_[A-Za-z0-9_]{8,})\\b");
    private static final Pattern PROVIDER_API_KEY =
            Pattern.compile("\\b(?:sk-ant-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{16,})\\b");
    private static final Pattern AUTH_SCHEME =
            Pattern.compile(
                    "(?i)(\\b(?:authorization\\s*[:=]\\s*(?:bearer|token|basic)\\s+|bearer\\s+))"
                            + "[^\\s\"'&,;)\\]]+");
    private static final Pattern KEY_VALUE_SECRET =
            Pattern.compile(
                    "(?i)((?<![A-Za-z0-9_-])(?:access_token|refresh_token|client_secret|api[_-]?key"
                            + "|x-api-key|password|secret|token)\\s*[:=]\\s*)[^\\s\"'&,;)\\]]+");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)(https?://)[^\\s/@]+@");

    private LogRedactor() {}

    /**
     * Masks tokens, credentials, and bearer headers in {@code text}; returns {@code null} as-is.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String sanitized = URL_USERINFO.matcher(text).replaceAll("$1" + REDACTED + "@");
        sanitized = GITHUB_TOKEN.matcher(sanitized).replaceAll(REDACTED);
        sanitized = PROVIDER_API_KEY.matcher(sanitized).replaceAll(REDACTED);
        sanitized = AUTH_SCHEME.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = KEY_VALUE_SECRET.matcher(sanitized).replaceAll("$1" + REDACTED);
        return sanitized;
    }

    /**
     * Simple exception type name suitable for user-facing diagnostics, walking superclasses when
     * {@code throwable} is an anonymous subclass whose {@link Class#getSimpleName()} is empty.
     */
    public static String errorType(Throwable throwable) {
        if (throwable == null) {
            return "Exception";
        }
        Class<?> current = throwable.getClass();
        while (current != null) {
            String simple = current.getSimpleName();
            if (!simple.isEmpty()) {
                return simple;
            }
            current = current.getSuperclass();
        }
        return throwable.getClass().getName();
    }

    /**
     * Returns {@code throwable} unchanged when neither it nor any reachable cause/suppressed
     * throwable contains credentials; otherwise returns a stack-preserving copy with every message
     * and {@code toString()} representation redacted.
     */
    public static Throwable sanitize(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        if (!needsRedaction(throwable, new IdentityHashMap<>())) {
            return throwable;
        }
        return copySanitized(throwable, new IdentityHashMap<>());
    }

    private static boolean needsRedaction(
            Throwable current, IdentityHashMap<Throwable, Boolean> visited) {
        if (current == null || visited.put(current, Boolean.TRUE) != null) {
            return false;
        }
        String message = current.getMessage();
        if (message != null && !Objects.equals(message, redact(message))) {
            return true;
        }
        String rendered = safeToString(current);
        if (!Objects.equals(rendered, redact(rendered))) {
            return true;
        }
        if (needsRedaction(current.getCause(), visited)) {
            return true;
        }
        for (Throwable suppressed : current.getSuppressed()) {
            if (needsRedaction(suppressed, visited)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable copySanitized(
            Throwable current, IdentityHashMap<Throwable, Throwable> copies) {
        Throwable existing = copies.get(current);
        if (existing != null) {
            return existing;
        }
        String redactedMessage = redact(current.getMessage());
        String redactedToString = redact(safeToString(current));
        SanitizedThrowable sanitized =
                new SanitizedThrowable(redactedMessage, redactedToString, current.getStackTrace());
        copies.put(current, sanitized);

        Throwable cause = current.getCause();
        if (cause != null) {
            Throwable sanitizedCause = copySanitized(cause, copies);
            if (sanitizedCause != sanitized) {
                sanitized.initCause(sanitizedCause);
            }
        }
        for (Throwable suppressed : current.getSuppressed()) {
            if (suppressed != null) {
                Throwable sanitizedSuppressed = copySanitized(suppressed, copies);
                if (sanitizedSuppressed != sanitized) {
                    sanitized.addSuppressed(sanitizedSuppressed);
                }
            }
        }
        return sanitized;
    }

    private static String safeToString(Throwable throwable) {
        try {
            return String.valueOf(throwable.toString());
        } catch (RuntimeException brokenToString) {
            return throwable.getClass().getName();
        }
    }

    private static final class SanitizedThrowable extends Throwable {
        private final String rendered;

        private SanitizedThrowable(
                String message, String rendered, StackTraceElement[] stackTrace) {
            super(message);
            this.rendered = rendered;
            setStackTrace(stackTrace);
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }

        @Override
        public String toString() {
            return rendered;
        }
    }
}
