package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChatPanelMarkdownTest {

    @Nested
    class BuildHtml {

        @Test
        void singleParagraph() {
            assertThat(ChatPanel.buildHtml("Hello world")).contains("<p>Hello world</p>");
        }

        @Test
        void numberedListItems() {
            String html = ChatPanel.buildHtml("1. first\n2. second\n3. third");
            assertThat(html).contains("<ol>");
            assertThat(html).contains("first");
            assertThat(html).contains("second");
            assertThat(html).contains("third");
            assertThat(html).contains("</ol>");
        }

        @Test
        void numberedListWithBlankLines_staysInOneOl() {
            String html = ChatPanel.buildHtml("1. first\n\n2. second\n\n3. third");
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
            assertThat(html).contains("alpha");
            assertThat(html).contains("beta");
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
            // commonmark wraps blockquote content in <p>
            String html = ChatPanel.buildHtml("> quoted text");
            assertThat(html).contains("<blockquote>");
            assertThat(html).contains("quoted text");
            assertThat(html).contains("</blockquote>");
        }

        @Test
        void boldInListItem() {
            // commonmark emits <strong> not <b>
            String html = ChatPanel.buildHtml("1. **important** note");
            assertThat(html).contains("<strong>important</strong>");
            assertThat(html).contains("note");
        }

        @Test
        void inlineCodeInListItem() {
            String html = ChatPanel.buildHtml("- call `foo()`");
            assertThat(html).contains("<code>foo()</code>");
        }

        @Test
        void blankLine_outsideList_separatesParagraphs() {
            // commonmark produces two <p> elements, not a <br>
            String html = ChatPanel.buildHtml("para one\n\npara two");
            assertThat(html).contains("<p>para one</p>");
            assertThat(html).contains("<p>para two</p>");
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
            assertThat(html).contains("footer");
        }

        @Test
        void unclosedFence_contentStillRendered() {
            // commonmark closes unclosed fences at end of document
            String html = ChatPanel.buildHtml("```\nA → B\n");
            assertThat(html).contains("<pre>");
            assertThat(html).contains("A → B");
        }
    }
}
