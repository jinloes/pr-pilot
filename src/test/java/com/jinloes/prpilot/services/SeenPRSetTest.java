package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PullRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeenPRSetTest {

    @TempDir Path tmp;

    private SeenPRSet set() {
        return new SeenPRSet(tmp.resolve("seen-prs.json"));
    }

    private static PullRequest pr(String owner, String repo, int number) {
        return new PullRequest(
                "title", "http://example.com", owner, repo, number, "", "author", "2024-01-01");
    }

    @Nested
    class InitialState {

        @Test
        void newSet_isNotSeeded() {
            assertThat(set().isSeeded()).isFalse();
        }

        @Test
        void newSet_doesNotContainAnything() {
            assertThat(set().contains(pr("o", "r", 1))).isFalse();
        }
    }

    @Nested
    class AddAndContains {

        @Test
        void add_thenContains_returnsTrue() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            assertThat(s.contains(pr("o", "r", 1))).isTrue();
        }

        @Test
        void contains_differentNumber_returnsFalse() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            assertThat(s.contains(pr("o", "r", 2))).isFalse();
        }

        @Test
        void multipleDistinctPRs_allContained() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            s.add(pr("o", "r", 2));
            s.add(pr("other", "repo", 5));
            assertThat(s.contains(pr("o", "r", 1))).isTrue();
            assertThat(s.contains(pr("o", "r", 2))).isTrue();
            assertThat(s.contains(pr("other", "repo", 5))).isTrue();
            assertThat(s.contains(pr("o", "r", 3))).isFalse();
        }
    }

    @Nested
    class Seeded {

        @Test
        void markSeeded_setsSeeded() {
            SeenPRSet s = set();
            s.markSeeded();
            assertThat(s.isSeeded()).isTrue();
        }
    }

    @Nested
    class Persistence {

        @Test
        void afterSaveAndReload_isSeeded() {
            Path file = tmp.resolve("seen-prs.json");
            SeenPRSet first = new SeenPRSet(file);
            first.add(pr("o", "r", 1));
            first.markSeeded();
            first.save();

            SeenPRSet second = new SeenPRSet(file);
            assertThat(second.isSeeded()).isTrue();
        }

        @Test
        void afterSaveAndReload_containsAddedPRs() {
            Path file = tmp.resolve("seen-prs.json");
            SeenPRSet first = new SeenPRSet(file);
            first.add(pr("org", "repo", 42));
            first.markSeeded();
            first.save();

            SeenPRSet second = new SeenPRSet(file);
            assertThat(second.contains(pr("org", "repo", 42))).isTrue();
            assertThat(second.contains(pr("org", "repo", 99))).isFalse();
        }
    }
}
