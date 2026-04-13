package com.jinloes.claudereviews.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftServiceTest {

    @TempDir Path tmp;

    private DraftService service;

    @BeforeEach
    void setUp() {
        service = new DraftService(tmp.resolve("drafts"));
    }

    private static PullRequest pr(String owner, String repo, int number) {
        return new PullRequest("Fix bug", "", owner, repo, number, "body", "author", "2026-01-01");
    }

    private static ReviewResult review() {
        return new ReviewResult("summary", "APPROVE", List.of());
    }

    // ---------------------------------------------------------------

    @Nested
    class ListDrafts {

        @Test
        void returnsEmpty_whenDirectoryDoesNotExist() throws IOException {
            assertThat(service.listDrafts()).isEmpty();
        }

        @Test
        void returnsEmpty_whenNoJsonFiles() throws IOException {
            service.save(pr("owner", "repo", 1), review(), "diff");
            // Replace the saved file with a non-JSON file
            Path draftsDir = tmp.resolve("drafts");
            draftsDir.toFile().listFiles()[0].renameTo(draftsDir.resolve("notes.txt").toFile());
            assertThat(service.listDrafts()).isEmpty();
        }

        @Test
        void returnsSavedDraft() throws IOException {
            service.save(pr("owner", "repo", 1), review(), "diff");
            List<DraftService.DraftEntry> entries = service.listDrafts();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).draft().owner).isEqualTo("owner");
            assertThat(entries.get(0).draft().repo).isEqualTo("repo");
            assertThat(entries.get(0).draft().number).isEqualTo(1);
        }

        @Test
        void returnsNewestFirst() throws IOException {
            service.save(pr("owner", "repo", 1), review(), "diff1");
            // Small sleep to ensure distinct savedAt timestamps
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
            service.save(pr("owner", "repo", 2), review(), "diff2");
            List<DraftService.DraftEntry> entries = service.listDrafts();
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).draft().number).isEqualTo(2);
        }

        @Test
        void skipsCorruptFiles() throws IOException {
            service.save(pr("owner", "repo", 1), review(), "diff");
            Files.writeString(tmp.resolve("drafts").resolve("corrupt.json"), "not json");
            assertThat(service.listDrafts()).hasSize(1);
        }
    }

    @Nested
    class Save {

        @Test
        void persistsAllFields() throws IOException {
            PullRequest pullRequest =
                    new PullRequest("My title", "", "myorg", "myrepo", 42, "pr body", "dev", "");
            service.save(pullRequest, review(), "raw diff");
            DraftService.DraftEntry entry = service.listDrafts().get(0);
            assertThat(entry.draft().owner).isEqualTo("myorg");
            assertThat(entry.draft().repo).isEqualTo("myrepo");
            assertThat(entry.draft().number).isEqualTo(42);
            assertThat(entry.draft().title).isEqualTo("My title");
            assertThat(entry.draft().author).isEqualTo("dev");
            assertThat(entry.draft().prBody).isEqualTo("pr body");
            assertThat(entry.draft().rawDiff).isEqualTo("raw diff");
            assertThat(entry.draft().savedAt).isNotBlank();
            assertThat(entry.draft().review).isNotNull();
        }

        @Test
        void createsDirectoryIfAbsent() throws IOException {
            assertThat(tmp.resolve("drafts").toFile()).doesNotExist();
            service.save(pr("owner", "repo", 1), review(), "diff");
            assertThat(tmp.resolve("drafts").toFile()).isDirectory();
        }

        @Test
        void filenameContainsOwnerRepoAndNumber() throws IOException {
            service.save(pr("myorg", "myrepo", 7), review(), "diff");
            String[] files = tmp.resolve("drafts").toFile().list();
            assertThat(files).isNotNull();
            assertThat(files[0]).startsWith("myorg__myrepo__7__");
            assertThat(files[0]).endsWith(".json");
        }
    }

    @Nested
    class Delete {

        @Test
        void removesFile() throws IOException {
            service.save(pr("owner", "repo", 1), review(), "diff");
            DraftService.DraftEntry entry = service.listDrafts().get(0);
            service.delete(entry);
            assertThat(service.listDrafts()).isEmpty();
        }

        @Test
        void noOp_whenFileAlreadyGone() throws IOException {
            service.save(pr("owner", "repo", 1), review(), "diff");
            DraftService.DraftEntry entry = service.listDrafts().get(0);
            service.delete(entry);
            // Second delete on the same entry should not throw
            service.delete(entry);
        }
    }

    @Nested
    class FormatSavedAt {

        @Test
        void returnsEmpty_forNull() {
            assertThat(DraftService.formatSavedAt(null)).isEmpty();
        }

        @Test
        void returnsEmpty_forBlank() {
            assertThat(DraftService.formatSavedAt("   ")).isEmpty();
        }

        @Test
        void formatsValidIso() {
            assertThat(DraftService.formatSavedAt("2026-04-08T14:30:00"))
                    .isEqualTo("2026-04-08 14:30");
        }

        @Test
        void returnsOriginal_forUnparseable() {
            assertThat(DraftService.formatSavedAt("not-a-date")).isEqualTo("not-a-date");
        }
    }

    @Nested
    class DisplayLabel {

        @Test
        void containsOwnerRepoNumberAndTimestamp() throws IOException {
            service.save(pr("myorg", "myrepo", 5), review(), "diff");
            String label = service.listDrafts().get(0).displayLabel();
            assertThat(label).contains("myorg/myrepo #5");
            assertThat(label).contains("Fix bug");
            // Formatted timestamp should appear in parentheses
            assertThat(label).matches(".*\\(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\)");
        }
    }
}
