package com.jinloes.prpilot.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LineCommentTest {

    @Nested
    class GetSources {

        @Test
        void defaultsToEmpty() {
            assertThat(new LineComment().getSources()).isEmpty();
            assertThat(new LineComment("a.java", 1, "issue", "body").getSources()).isEmpty();
        }

        @Test
        void isUnmodifiable() {
            LineComment comment = new LineComment();
            comment.setSources(List.of("claude"));

            assertThatThrownBy(() -> comment.getSources().add("gpt"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class SetSources {

        @Test
        void nullBecomesEmpty() {
            LineComment comment = new LineComment();
            comment.setSources(List.of("claude"));

            comment.setSources(null);

            assertThat(comment.getSources()).isEmpty();
        }

        @Test
        void copiesTheInput() {
            List<String> input = new ArrayList<>(List.of("claude", "gpt"));
            LineComment comment = new LineComment();

            comment.setSources(input);
            input.add("other");

            assertThat(comment.getSources()).containsExactly("claude", "gpt");
        }

        @Test
        void dropsNullEntries() {
            LineComment comment = new LineComment();

            comment.setSources(Arrays.asList("claude", null));

            assertThat(comment.getSources()).containsExactly("claude");
        }
    }
}
