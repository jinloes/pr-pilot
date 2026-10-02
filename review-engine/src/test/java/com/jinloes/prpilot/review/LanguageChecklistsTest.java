package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LanguageChecklistsTest {

    @Nested
    class LanguageFor {
        @Test
        void mapsKnownExtensionsCaseInsensitively() {
            assertThat(LanguageChecklists.languageFor("src/A.java")).isEqualTo("Java");
            assertThat(LanguageChecklists.languageFor("build.gradle.KTS")).isEqualTo("Kotlin");
            assertThat(LanguageChecklists.languageFor("web/App.tsx"))
                    .isEqualTo("JavaScript/TypeScript");
            assertThat(LanguageChecklists.languageFor("tool.py")).isEqualTo("Python");
            assertThat(LanguageChecklists.languageFor("cmd/main.go")).isEqualTo("Go");
            assertThat(LanguageChecklists.languageFor("lib.rs")).isEqualTo("Rust");
        }

        @Test
        void returnsNullForUncoveredOrMissingExtensions() {
            assertThat(LanguageChecklists.languageFor("README.md")).isNull();
            assertThat(LanguageChecklists.languageFor("Makefile")).isNull();
            assertThat(LanguageChecklists.languageFor("dir.java/Makefile")).isNull();
            assertThat(LanguageChecklists.languageFor(".java")).isNull();
            assertThat(LanguageChecklists.languageFor("trailing.")).isNull();
            assertThat(LanguageChecklists.languageFor(null)).isNull();
        }
    }

    @Nested
    class SectionFor {
        @Test
        void isEmptyWhenNoCoveredLanguageChanged() {
            assertThat(LanguageChecklists.sectionFor(List.of("README.md", "x.yaml"))).isEmpty();
            assertThat(LanguageChecklists.sectionFor(List.of())).isEmpty();
        }

        @Test
        void includesOnlyTheChangedLanguagesOnceEach() {
            String section =
                    LanguageChecklists.sectionFor(
                            List.of("a/A.java", "a/B.java", "web/x.ts", "README.md"));

            assertThat(section).startsWith("\nLanguage-specific checks.");
            assertThat(section).contains("\nJava:\n").contains("\nJavaScript/TypeScript:\n");
            assertThat(section).doesNotContain("\nPython:\n").doesNotContain("\nKotlin:\n");
            assertThat(section.indexOf("\nJava:\n")).isEqualTo(section.lastIndexOf("\nJava:\n"));
        }

        @Test
        void usesAFixedOrderRegardlessOfInputOrder() {
            assertThat(LanguageChecklists.sectionFor(List.of("x.py", "A.java")))
                    .isEqualTo(LanguageChecklists.sectionFor(List.of("A.java", "x.py")));
        }
    }
}
