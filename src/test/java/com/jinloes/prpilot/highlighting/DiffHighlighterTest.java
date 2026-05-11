package com.jinloes.prpilot.highlighting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DiffHighlighterTest {

    @Nested
    class HtmlEscape {

        @Test
        void ampersand() {
            assertThat(DiffHighlighter.htmlEscape("x&y")).isEqualTo("x&amp;y");
        }

        @Test
        void lessThan() {
            assertThat(DiffHighlighter.htmlEscape("List<String>")).isEqualTo("List&lt;String&gt;");
        }

        @Test
        void greaterThan() {
            assertThat(DiffHighlighter.htmlEscape("x>y")).isEqualTo("x&gt;y");
        }

        @Test
        void space() {
            assertThat(DiffHighlighter.htmlEscape("a b")).isEqualTo("a&nbsp;b");
        }

        @Test
        void nullInput_returnsEmpty() {
            assertThat(DiffHighlighter.htmlEscape(null)).isEmpty();
        }

        @Test
        void noSpecialChars_unchanged() {
            assertThat(DiffHighlighter.htmlEscape("abc123")).isEqualTo("abc123");
        }
    }
}
