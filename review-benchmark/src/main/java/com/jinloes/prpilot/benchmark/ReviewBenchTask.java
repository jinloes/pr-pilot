package com.jinloes.prpilot.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/** One pull request from a ReviewBench corpus file. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ReviewBenchTask(
        String repo,
        @JsonProperty("pr_number") int prNumber,
        String base,
        String head,
        String nwo,
        String title,
        String body) {

    static final String SET_TEST = "test";
    static final String SET_FULL = "full";
    static final String MIRROR_ORG_URL = "https://github.com/review-bench/";

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern NWO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    /** Reads the 25-task test set or the full 219-task manifest from a ReviewBench checkout. */
    static List<ReviewBenchTask> load(Path corpusRoot, String set, ObjectMapper mapper)
            throws IOException {
        Path file =
                switch (set) {
                    case SET_TEST -> corpusRoot.resolve("corpus/test/test.json");
                    case SET_FULL -> corpusRoot.resolve("corpus/manifest.json");
                    default -> throw new IllegalArgumentException("Unknown set: " + set);
                };
        if (!Files.isRegularFile(file)) {
            throw new IOException("No ReviewBench corpus at " + file);
        }
        List<ReviewBenchTask> tasks =
                mapper.readValue(file.toFile(), new TypeReference<List<ReviewBenchTask>>() {});
        for (ReviewBenchTask task : tasks) task.validate();
        return tasks;
    }

    /**
     * The golden-set file stem ReviewBench uses, {@code <owner>_<repo>_<pr>-<head8>}. Findings
     * files share it so a run can be lined up with {@code golden/} by eye.
     */
    String key() {
        return nwo.replace('/', '_') + "_" + prNumber + "-" + head.substring(0, 8);
    }

    /** The review-bench mirror that holds this task's base and head commits. */
    String mirrorUrl() {
        return MIRROR_ORG_URL + nwo.replace('/', '_');
    }

    /** Local clone directory name; one clone serves every task from the same repository. */
    String cloneName() {
        return nwo.replace('/', '_');
    }

    String owner() {
        return StringUtils.substringBefore(nwo, "/");
    }

    String name() {
        return StringUtils.substringAfter(nwo, "/");
    }

    private void validate() throws IOException {
        // nwo becomes a directory name and a URL path, so never trust it unchecked.
        if (nwo == null || !NWO.matcher(nwo).matches() || nwo.contains("..")) {
            throw new IOException("Corpus entry has an invalid nwo: " + nwo);
        }
        if (base == null || !SHA.matcher(base).matches()) {
            throw new IOException(nwo + "#" + prNumber + " has an invalid base SHA.");
        }
        if (head == null || !SHA.matcher(head).matches()) {
            throw new IOException(nwo + "#" + prNumber + " has an invalid head SHA.");
        }
        if (prNumber <= 0 || !StringUtils.startsWith(repo, "https://")) {
            throw new IOException(nwo + " has an invalid pull request number or repo URL.");
        }
    }
}
