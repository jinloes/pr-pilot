package com.jinloes.prpilot.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrRefTest {

    @Nested
    class Parse {
        @Test
        void parsesAPullRequestUrl() {
            PrRef pr = PrRef.parse("https://github.example.com/org/my-repo/pull/42/files");
            assertThat(pr).isEqualTo(new PrRef("https://github.example.com", "org", "my-repo", 42));
            assertThat(pr.label()).isEqualTo("org/my-repo#42");
        }

        @Test
        void rejectsAnIssueUrl() {
            assertThatThrownBy(() -> PrRef.parse("https://github.com/org/repo/issues/1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not a pull request URL");
        }

        @Test
        void rejectsPlainHttp() {
            assertThatThrownBy(() -> PrRef.parse("http://github.com/org/repo/pull/1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ParseList {
        @Test
        void skipsBlankLinesAndComments() {
            List<PrRef> prs =
                    PrRef.parseList(
                            List.of(
                                    "# benchmark set",
                                    "",
                                    "  https://github.com/a/b/pull/1  ",
                                    "https://github.com/a/c/pull/2"));
            assertThat(prs).extracting(PrRef::label).containsExactly("a/b#1", "a/c#2");
        }
    }
}
