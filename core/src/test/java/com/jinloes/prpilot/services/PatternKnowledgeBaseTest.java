package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternKnowledgeBaseTest {

    @Nested
    class Load {

        @Test
        void returnsEmpty_whenNoFileExists(@TempDir File dir) {
            PatternKnowledgeBase kb = new PatternKnowledgeBase(dir);
            assertThat(kb.load("owner", "repo")).isEmpty();
        }

        @Test
        void returnsContents_afterAppend(@TempDir File dir) {
            PatternKnowledgeBase kb = new PatternKnowledgeBase(dir);
            kb.append("owner", "repo", "the comment", "the finding");
            assertThat(kb.load("owner", "repo")).isNotBlank();
        }

        @Test
        void separatesReposByFile(@TempDir File dir) {
            PatternKnowledgeBase kb = new PatternKnowledgeBase(dir);
            kb.append("owner", "repo-a", "comment a", "finding a");
            kb.append("owner", "repo-b", "comment b", "finding b");

            assertThat(kb.load("owner", "repo-a")).contains("finding a");
            assertThat(kb.load("owner", "repo-a")).doesNotContain("finding b");
            assertThat(kb.load("owner", "repo-b")).contains("finding b");
        }
    }

    @Nested
    class FileFor {

        @Test
        void pathTraversal_throwsSecurityException(@TempDir File dir) {
            PatternKnowledgeBase kb = new PatternKnowledgeBase(dir);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> kb.append("../evil", "repo", "ctx", "response"))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        void normalOwnerRepo_doesNotThrow(@TempDir File dir) {
            PatternKnowledgeBase kb = new PatternKnowledgeBase(dir);
            // Should not throw for well-formed owner/repo names
            kb.append("owner", "repo", "ctx", "response");
        }
    }

    @Nested
    class Append {

        @Test
        void createsFileAndParentDirs(@TempDir File dir) {
            File nested = new File(dir, "sub/patterns");
            PatternKnowledgeBase kb = new PatternKnowledgeBase(nested);
            kb.append("owner", "repo", "comment", "finding");
            assertThat(kb.load("owner", "repo")).isNotBlank();
        }

        @Test
        void accumulatesMultipleEntries(@TempDir File dir) {
            PatternKnowledgeBase kb = new PatternKnowledgeBase(dir);
            kb.append("owner", "repo", "comment one", "finding one");
            kb.append("owner", "repo", "comment two", "finding two");

            String contents = kb.load("owner", "repo");
            assertThat(contents).contains("finding one");
            assertThat(contents).contains("finding two");
        }
    }

    @Nested
    class FormatEntry {

        @Test
        void containsQuotedContext() {
            String entry = PatternKnowledgeBase.formatEntry("the comment", "the finding");
            assertThat(entry).contains("> the comment");
        }

        @Test
        void containsResponse() {
            String entry = PatternKnowledgeBase.formatEntry("q", "the finding");
            assertThat(entry).contains("the finding");
        }

        @Test
        void multiLineContext_quotedPerLine() {
            String entry = PatternKnowledgeBase.formatEntry("line one\nline two", "finding");
            assertThat(entry).contains("> line one\n> line two");
        }

        @Test
        void containsDate() {
            String entry = PatternKnowledgeBase.formatEntry("q", "r");
            // ISO date like 2025-01-15 — just check the format
            assertThat(entry).containsPattern("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        void startsWithSeparator() {
            String entry = PatternKnowledgeBase.formatEntry("q", "r");
            assertThat(entry).startsWith("\n---\n");
        }
    }
}
