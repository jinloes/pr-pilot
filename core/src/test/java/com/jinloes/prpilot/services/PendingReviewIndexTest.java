package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingReviewIndexTest {

    @TempDir Path tmp;

    private PendingReviewIndex index() {
        return new PendingReviewIndex(tmp.resolve("pending-prs.json"));
    }

    @Nested
    class List_ {

        @Test
        void returnsEmpty_whenFileDoesNotExist() {
            assertThat(index().list()).isEmpty();
        }
    }

    @Nested
    class Add {

        @Test
        void createsEntry() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "My PR", "");
            List<PendingReviewIndex.Entry> entries = idx.list();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).owner()).isEqualTo("owner");
            assertThat(entries.get(0).repo()).isEqualTo("repo");
            assertThat(entries.get(0).number()).isEqualTo(1);
            assertThat(entries.get(0).title()).isEqualTo("My PR");
        }

        @Test
        void deduplicates_sameOwnerRepoNumber() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "Old title", "");
            idx.add("owner", "repo", 1, "New title", "");
            List<PendingReviewIndex.Entry> entries = idx.list();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).title()).isEqualTo("New title");
        }

        @Test
        void differentPRs_bothKept() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR one", "");
            idx.add("owner", "repo", 2, "PR two", "");
            assertThat(idx.list()).hasSize(2);
        }

        @Test
        void putsNewestFirst() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "first", "");
            idx.add("owner", "repo", 2, "second", "");
            assertThat(idx.list().get(0).number()).isEqualTo(2);
        }

        @Test
        void storesAndReturnsHeadSha() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "My PR", "abc123");
            assertThat(idx.list().get(0).headSha()).isEqualTo("abc123");
        }

        @Test
        void nullHeadSha_returnedAsEmpty() {
            PendingReviewIndex.Entry entry =
                    new PendingReviewIndex.Entry("o", "r", 1, "t", "2024-01-01", null);
            assertThat(entry.headSha()).isEmpty();
        }
    }

    @Nested
    class HasDraft {

        @Test
        void returnsFalse_whenEmpty() {
            assertThat(index().hasDraft("owner", "repo", 1)).isFalse();
        }

        @Test
        void returnsTrue_whenMatchingEntryExists() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 42, "My PR", "");
            assertThat(idx.hasDraft("owner", "repo", 42)).isTrue();
        }

        @Test
        void returnsFalse_whenNoPRMatch() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 42, "My PR", "");
            assertThat(idx.hasDraft("owner", "repo", 99)).isFalse();
        }

        @Test
        void returnsFalse_whenOwnerDiffers() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR", "");
            assertThat(idx.hasDraft("other", "repo", 1)).isFalse();
        }

        @Test
        void returnsFalse_whenRepoDiffers() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR", "");
            assertThat(idx.hasDraft("owner", "other", 1)).isFalse();
        }
    }

    @Nested
    class Remove {

        @Test
        void removesMatchingEntry() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR one", "");
            idx.add("owner", "repo", 2, "PR two", "");
            idx.remove("owner", "repo", 1);
            List<PendingReviewIndex.Entry> entries = idx.list();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).number()).isEqualTo(2);
        }

        @Test
        void nonExistentEntry_isNoOp() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR", "");
            idx.remove("owner", "repo", 99);
            assertThat(idx.list()).hasSize(1);
        }
    }

    @Nested
    class Persistence {

        @Test
        void entries_persistAcrossInstances() {
            Path file = tmp.resolve("pending-prs.json");
            PendingReviewIndex first = new PendingReviewIndex(file);
            first.add("owner", "repo", 7, "Saved PR", "");

            PendingReviewIndex second = new PendingReviewIndex(file);
            assertThat(second.list()).hasSize(1);
            assertThat(second.list().get(0).number()).isEqualTo(7);
        }
    }

    @Nested
    class DisplayLabel {

        @Test
        void containsOwnerRepoAndNumber() {
            PendingReviewIndex idx = index();
            idx.add("myorg", "myrepo", 42, "Fix bug", "");
            String label = idx.list().get(0).displayLabel();
            assertThat(label).contains("myorg/myrepo #42");
            assertThat(label).contains("Fix bug");
        }
    }
}
