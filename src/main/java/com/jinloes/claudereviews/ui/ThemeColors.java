package com.jinloes.claudereviews.ui;

import java.awt.Color;
import javax.swing.UIManager;

/** Centralized theme-aware color palette. All UI classes reference these constants. */
class ThemeColors {

    static final boolean DARK;

    // Backgrounds
    static final Color BG;
    static final Color BG_SUBTLE;
    static final Color BG_USER; // chat user bubble background

    // Foregrounds
    static final Color FG;
    static final Color FG_MUTED;

    // Borders
    static final Color BORDER_COL;

    // Diff line backgrounds
    static final Color ADD_BG;
    static final Color DEL_BG;

    // Diff line foregrounds / accents
    static final Color ADD_FG;
    static final Color DEL_FG;

    // Semantic accents (same in both themes)
    static final Color ACCENT_BLUE = new Color(0x1f6feb);
    static final Color ACCENT_GREEN = new Color(0x238636);
    static final Color ERROR_FG = new Color(0xf85149);

    static {
        Color panelBg = UIManager.getColor("Panel.background");
        double luminance =
                panelBg != null
                        ? (0.299 * panelBg.getRed()
                                        + 0.587 * panelBg.getGreen()
                                        + 0.114 * panelBg.getBlue())
                                / 255.0
                        : 0.0;
        DARK = luminance < 0.5;

        if (DARK) {
            BG = new Color(0x0d1117);
            BG_SUBTLE = new Color(0x161b22);
            BG_USER = new Color(0x1c2128);
            FG = new Color(0xe6edf3);
            FG_MUTED = new Color(0x8b949e);
            BORDER_COL = new Color(0x30363d);
            ADD_BG = new Color(0x0f3d2e);
            DEL_BG = new Color(0x3d1f24);
            ADD_FG = new Color(0x3fb950);
            DEL_FG = new Color(0xf85149);
        } else {
            BG = new Color(0xffffff);
            BG_SUBTLE = new Color(0xf6f8fa);
            BG_USER = new Color(0xeaeef2);
            FG = new Color(0x24292f);
            FG_MUTED = new Color(0x57606a);
            BORDER_COL = new Color(0xd0d7de);
            ADD_BG = new Color(0xe6ffec);
            DEL_BG = new Color(0xffebe9);
            ADD_FG = new Color(0x1a7f37);
            DEL_FG = new Color(0xcf222e);
        }
    }

    private ThemeColors() {}
}
