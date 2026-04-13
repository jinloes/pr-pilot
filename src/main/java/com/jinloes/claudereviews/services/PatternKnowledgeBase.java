package com.jinloes.claudereviews.services;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;

/**
 * Persists verified pattern findings per repository so future reviews can benefit from prior
 * verification work. Stored as Markdown at {@code ~/.claude-reviews/patterns/{owner}_{repo}.md}.
 *
 * <p>Each entry records the comment that was verified and Claude's conclusion. The full file is
 * injected into the review prompt so future reviews know which patterns are established.
 */
public class PatternKnowledgeBase {

    private final File baseDir;

    public PatternKnowledgeBase() {
        this(new File(System.getProperty("user.home"), ".claude-reviews/patterns"));
    }

    /** Package-private constructor for tests. */
    PatternKnowledgeBase(File baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Appends a new verified finding. {@code questionContext} is the comment body that was
     * verified; {@code response} is Claude's conclusion.
     */
    public void append(String owner, String repo, String questionContext, String response) {
        File file = fileFor(owner, repo);
        file.getParentFile().mkdirs();
        String entry = formatEntry(questionContext, response);
        try (FileWriter fw = new FileWriter(file, StandardCharsets.UTF_8, true)) {
            fw.write(entry);
        } catch (IOException ignored) {
            // Best-effort — not critical if the write fails
        }
    }

    /**
     * Returns the full contents of the knowledge file for this repo, or an empty string if none
     * exists yet.
     */
    public String load(String owner, String repo) {
        File file = fileFor(owner, repo);
        if (!file.isFile()) return "";
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Formats a single knowledge entry as a Markdown block. Visible for testing. */
    static String formatEntry(String questionContext, String response) {
        return "\n---\n**Verified "
                + LocalDate.now()
                + "**\n\n"
                + "> "
                + questionContext.strip().replace("\n", "\n> ")
                + "\n\n"
                + response.strip()
                + "\n";
    }

    private File fileFor(String owner, String repo) {
        return new File(baseDir, owner + "_" + repo + ".md");
    }
}
