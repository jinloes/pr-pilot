package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SemanticSkillBundleTest {
    @Test
    void verifiesFullBundleAndFailsClosedForSubstitutionOrMissingResources() throws Exception {
        assertThat(SemanticSkillBundle.load().instructions())
                .contains(
                        "Resolve the target",
                        "Establish the live session",
                        "Read-only analysis",
                        "Excluded operations",
                        "Never synthesize an executable callable");
        for (String changed :
                new String[] {
                    "manifest.json",
                    "LICENSE",
                    "intellij-mcp-tools/SKILL.md",
                    "intellij-code-intelligence/SKILL.md"
                }) {
            assertThatThrownBy(
                            () ->
                                    SemanticSkillBundle.load(
                                            path ->
                                                    path.equals(changed)
                                                            ? new java.io.ByteArrayInputStream(
                                                                    "untrusted substitution"
                                                                            .getBytes(
                                                                                    java.nio.charset
                                                                                            .StandardCharsets
                                                                                            .UTF_8))
                                                            : getClass()
                                                                    .getResourceAsStream(
                                                                            "/semantic-skills/"
                                                                                    + path)))
                    .isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(
                            () ->
                                    SemanticSkillBundle.load(
                                            path ->
                                                    path.equals(changed)
                                                            ? null
                                                            : getClass()
                                                                    .getResourceAsStream(
                                                                            "/semantic-skills/"
                                                                                    + path)))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void shipsBothFullAdaptedSkillsAndTheirProvenance() throws Exception {
        for (String path :
                new String[] {
                    "manifest.json",
                    "LICENSE",
                    "intellij-mcp-tools/SKILL.md",
                    "intellij-code-intelligence/SKILL.md"
                }) {
            try (var resource = getClass().getResourceAsStream("/semantic-skills/" + path)) {
                assertThat(resource).as("Bundled resource %s", path).isNotNull();
                assertThat(resource.readAllBytes().length).isGreaterThan(100);
            }
        }
    }
}
