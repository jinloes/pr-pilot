package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChatPanelMarkdownTest {

    @Nested
    class InlineHtml {

        @Test
        void plainText_isPassedThrough() {
            assertThat(ChatPanel.inlineHtml("hello world")).isEqualTo("hello world");
        }

        @Test
        void ampersand_isEscaped() {
            assertThat(ChatPanel.inlineHtml("a & b")).isEqualTo("a &amp; b");
        }

        @Test
        void lessThan_isEscaped() {
            assertThat(ChatPanel.inlineHtml("a < b")).isEqualTo("a &lt; b");
        }

        @Test
        void greaterThan_isEscaped() {
            assertThat(ChatPanel.inlineHtml("a > b")).isEqualTo("a &gt; b");
        }

        @Test
        void doubleQuote_isEscaped() {
            assertThat(ChatPanel.inlineHtml("say \"hi\"")).isEqualTo("say &quot;hi&quot;");
        }

        @Test
        void bold_isRendered() {
            assertThat(ChatPanel.inlineHtml("**bold**")).isEqualTo("<b>bold</b>");
        }

        @Test
        void boldWithSurroundingText() {
            assertThat(ChatPanel.inlineHtml("before **bold** after"))
                    .isEqualTo("before <b>bold</b> after");
        }

        @Test
        void inlineCode_isRendered() {
            assertThat(ChatPanel.inlineHtml("`code`")).isEqualTo("<code>code</code>");
        }

        @Test
        void inlineCode_contentIsHtmlEscaped() {
            assertThat(ChatPanel.inlineHtml("`List<String>`"))
                    .isEqualTo("<code>List&lt;String&gt;</code>");
        }

        @Test
        void boldContainingInlineCode_isRenderedNested() {
            assertThat(ChatPanel.inlineHtml("**`method()`**"))
                    .isEqualTo("<b><code>method()</code></b>");
        }

        @Test
        void boldContainingGenerics_htmlEscapedInsideCode() {
            assertThat(ChatPanel.inlineHtml("**`List<String>`**"))
                    .isEqualTo("<b><code>List&lt;String&gt;</code></b>");
        }

        @Test
        void boldItalic_isRendered() {
            assertThat(ChatPanel.inlineHtml("***bold-italic***"))
                    .isEqualTo("<b><em>bold-italic</em></b>");
        }

        @Test
        void unclosedBold_isLiteral() {
            // No closing **, so the ** is treated as literal characters
            String result = ChatPanel.inlineHtml("**no close");
            assertThat(result).doesNotContain("<b>");
            assertThat(result).contains("**");
        }

        @Test
        void unclosedBacktick_isLiteral() {
            String result = ChatPanel.inlineHtml("`no close");
            assertThat(result).doesNotContain("<code>");
            assertThat(result).contains("`");
        }

        @Test
        void emptyString_returnsEmpty() {
            assertThat(ChatPanel.inlineHtml("")).isEmpty();
        }

        @Test
        void ampersandInsideCode_isEscaped() {
            assertThat(ChatPanel.inlineHtml("`a & b`")).isEqualTo("<code>a &amp; b</code>");
        }
    }

    @Nested
    class BuildHtml {

        @Test
        void singleParagraph() {
            String html = ChatPanel.buildHtml("Hello world");
            assertThat(html).contains("<p>Hello world</p>");
        }

        @Test
        void numberedListItems() {
            String html = ChatPanel.buildHtml("1. first\n2. second\n3. third");
            assertThat(html).contains("<ol>");
            assertThat(html).contains("<li>first</li>");
            assertThat(html).contains("<li>second</li>");
            assertThat(html).contains("<li>third</li>");
            assertThat(html).contains("</ol>");
        }

        @Test
        void numberedListWithBlankLines_staysInOneOl() {
            String html = ChatPanel.buildHtml("1. first\n\n2. second\n\n3. third");
            // Should have exactly one <ol> opening (blank lines within list are ignored)
            int count = 0;
            int idx = 0;
            while ((idx = html.indexOf("<ol>", idx)) != -1) {
                count++;
                idx++;
            }
            assertThat(count).isEqualTo(1);
        }

        @Test
        void bulletListItems() {
            String html = ChatPanel.buildHtml("- alpha\n- beta");
            assertThat(html).contains("<ul>");
            assertThat(html).contains("<li>alpha</li>");
            assertThat(html).contains("<li>beta</li>");
            assertThat(html).contains("</ul>");
        }

        @Test
        void h1() {
            assertThat(ChatPanel.buildHtml("# Title")).contains("<h1>Title</h1>");
        }

        @Test
        void h2() {
            assertThat(ChatPanel.buildHtml("## Section")).contains("<h2>Section</h2>");
        }

        @Test
        void h3() {
            assertThat(ChatPanel.buildHtml("### Sub")).contains("<h3>Sub</h3>");
        }

        @Test
        void blockquote() {
            assertThat(ChatPanel.buildHtml("> quoted text"))
                    .contains("<blockquote>quoted text</blockquote>");
        }

        @Test
        void boldInListItem() {
            String html = ChatPanel.buildHtml("1. **important** note");
            assertThat(html).contains("<li><b>important</b> note</li>");
        }

        @Test
        void inlineCodeInListItem() {
            String html = ChatPanel.buildHtml("- call `foo()`");
            assertThat(html).contains("<li>call <code>foo()</code></li>");
        }

        @Test
        void blankLine_outsideList_addsBreak() {
            String html = ChatPanel.buildHtml("para one\n\npara two");
            assertThat(html).contains("<br>");
        }

        @Test
        void wrappedInHtmlBody() {
            String html = ChatPanel.buildHtml("text");
            assertThat(html).startsWith("<html>");
            assertThat(html).endsWith("</html>");
            assertThat(html).contains("<body>");
            assertThat(html).contains("</body>");
        }
    }

    @Nested
    class FencedCodeBlocks {

        @Test
        void fencedBlock_renderedAsPreTag() {
            String html = ChatPanel.buildHtml("```\nA → B\n```");
            assertThat(html).contains("<pre>");
            assertThat(html).contains("</pre>");
            assertThat(html).contains("A → B");
        }

        @Test
        void fencedBlock_withLanguageTag_renderedAsPreTag() {
            String html = ChatPanel.buildHtml("```text\nA → B\n```");
            assertThat(html).contains("<pre>");
            assertThat(html).contains("A → B");
        }

        @Test
        void fencedBlock_htmlCharsEscaped() {
            String html = ChatPanel.buildHtml("```\n<foo> & \"bar\"\n```");
            assertThat(html).contains("&lt;foo&gt;");
            assertThat(html).contains("&amp;");
            assertThat(html).doesNotContain("<foo>");
        }

        @Test
        void fencedBlock_multiLine_preservesAllLines() {
            String html = ChatPanel.buildHtml("```\nA → B\n   ↓\n   C\n```");
            assertThat(html).contains("A → B");
            assertThat(html).contains("   ↓");
            assertThat(html).contains("   C");
        }

        @Test
        void textBeforeAndAfterFence_renderedNormally() {
            String html = ChatPanel.buildHtml("## Header\n```\ncode\n```\nfooter");
            assertThat(html).contains("<h2>Header</h2>");
            assertThat(html).contains("<pre>");
            assertThat(html).contains("code");
            assertThat(html).contains("<p>footer</p>");
        }

        @Test
        void unclosedFence_contentStillRendered() {
            String html = ChatPanel.buildHtml("```\nA → B\n");
            assertThat(html).contains("<pre>");
            assertThat(html).contains("A → B");
        }
    }
}
