package com.jinloes.claudereviews.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ThemeColorsTest {

    private static double luminance(java.awt.Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    @Test
    void darkFlagConsistentWithBgLuminance() {
        double bgLuminance = luminance(ThemeColors.BG);
        if (ThemeColors.DARK) {
            assertThat(bgLuminance).isLessThan(0.5);
        } else {
            assertThat(bgLuminance).isGreaterThanOrEqualTo(0.5);
        }
    }

    @Test
    void accentColorsAreSameInBothThemes() {
        // Semantic accents are always the GitHub values regardless of theme
        assertThat(ThemeColors.ACCENT_BLUE.getRGB())
                .isEqualTo(new java.awt.Color(0x1f6feb).getRGB());
        assertThat(ThemeColors.ACCENT_GREEN.getRGB())
                .isEqualTo(new java.awt.Color(0x238636).getRGB());
        assertThat(ThemeColors.ERROR_FG.getRGB()).isEqualTo(new java.awt.Color(0xf85149).getRGB());
    }

    @Test
    void addBgAndDelBgAreDifferent() {
        assertThat(ThemeColors.ADD_BG).isNotEqualTo(ThemeColors.DEL_BG);
    }

    @Test
    void fgAndFgMutedAreDifferent() {
        assertThat(ThemeColors.FG).isNotEqualTo(ThemeColors.FG_MUTED);
    }
}
