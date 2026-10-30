package com.jinloes.prpilot.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pull request named by its web URL, e.g. {@code https://github.com/owner/repo/pull/12}. {@code
 * base} is an optional base commit that overrides the one the benchmark would otherwise derive.
 */
record PrRef(String githubBaseUrl, String owner, String repo, int number, String base) {
    private static final Pattern URL =
            Pattern.compile(
                    "(https://[A-Za-z0-9.-]+)/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/(\\d+)"
                            + "(?:[/?#].*)?");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");
    private static final String BASE_OPTION = "base=";

    PrRef(String githubBaseUrl, String owner, String repo, int number) {
        this(githubBaseUrl, owner, repo, number, null);
    }

    static PrRef parse(String value) {
        Matcher matcher = URL.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a pull request URL: " + value.trim());
        }
        return new PrRef(
                matcher.group(1),
                matcher.group(2),
                matcher.group(3),
                Integer.parseInt(matcher.group(4)));
    }

    /**
     * One URL per line, optionally followed by {@code base=<full sha>}; blank lines and {@code #}
     * comments are ignored.
     */
    static List<PrRef> parseList(List<String> lines) {
        List<PrRef> refs = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] tokens = trimmed.split("\\s+");
            PrRef ref = parse(tokens[0]);
            String base = null;
            for (int i = 1; i < tokens.length; i++) {
                if (!tokens[i].startsWith(BASE_OPTION) || base != null) {
                    throw new IllegalArgumentException(
                            "Unexpected \"" + tokens[i] + "\" after " + tokens[0]);
                }
                base = tokens[i].substring(BASE_OPTION.length());
                if (!SHA.matcher(base).matches()) {
                    throw new IllegalArgumentException(
                            "base= needs a full commit SHA, got \"" + base + "\"");
                }
            }
            refs.add(new PrRef(ref.githubBaseUrl(), ref.owner(), ref.repo(), ref.number(), base));
        }
        return refs;
    }

    String label() {
        return owner + "/" + repo + "#" + number;
    }
}
