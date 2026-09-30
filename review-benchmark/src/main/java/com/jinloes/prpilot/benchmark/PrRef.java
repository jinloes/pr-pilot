package com.jinloes.prpilot.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A pull request named by its web URL, e.g. {@code https://github.com/owner/repo/pull/12}. */
record PrRef(String githubBaseUrl, String owner, String repo, int number) {
    private static final Pattern URL =
            Pattern.compile(
                    "(https://[A-Za-z0-9.-]+)/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/(\\d+)"
                            + "(?:[/?#].*)?");

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

    /** One URL per line; blank lines and {@code #} comments are ignored. */
    static List<PrRef> parseList(List<String> lines) {
        List<PrRef> refs = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            refs.add(parse(trimmed));
        }
        return refs;
    }

    String label() {
        return owner + "/" + repo + "#" + number;
    }
}
