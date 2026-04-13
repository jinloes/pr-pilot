package com.jinloes.claudereviews.ui;

import java.awt.*;
import java.awt.event.ActionEvent;
import javax.swing.*;

/**
 * Terminal-style progress bar styled after the Claude CLI output:
 *
 * <pre>│█████████████▋·······│ 67% EXECUTING [32s] · 2,418 chars</pre>
 *
 * <p>Progress is time-based (asymptotic towards 97%) since Claude's {@code --print} mode does not
 * emit completion percentages. The bar never shows 100% until {@link #stop()} is called explicitly.
 */
public class GenerationProgressBar extends JLabel {

    private static final int BAR_COLS = 22;
    private static final char[] FRAC = {'▏', '▎', '▍', '▌', '▋', '▊', '▉'};
    // Time constant for asymptotic curve: 97% reached asymptotically, ~63% at t=TIME_K seconds
    private static final double TIME_K = 28.0;

    private final Timer timer;
    private long startMillis;
    private volatile int charCount;

    public GenerationProgressBar() {
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        setForeground(new Color(0x3fb950)); // GitHub green
        setBackground(new Color(0x0d1117)); // matches ReviewPanel.BG
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(3, 8, 4, 8));
        setVisible(false);

        timer = new Timer(200, this::tick);
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    /** Begin animating. Safe to call from EDT. */
    public void start() {
        startMillis = System.currentTimeMillis();
        charCount = 0;
        setText(render(0, 0));
        setVisible(true);
        timer.start();
    }

    /** Update the character counter shown in the bar. Thread-safe. */
    public void updateChars(int chars) {
        charCount = chars;
    }

    /** Stop animating and hide. Safe to call even if not started. */
    public void stop() {
        timer.stop();
        setVisible(false);
    }

    // ---------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------

    private void tick(ActionEvent ignored) {
        long elapsed = (System.currentTimeMillis() - startMillis) / 1000L;
        setText(render(elapsed, charCount));
    }

    private static String render(long elapsed, int chars) {
        double progress = computeProgress(elapsed);
        int pct = (int) (progress * 100);

        double filled = progress * BAR_COLS;
        int full = (int) filled;
        double frac = filled - full;

        StringBuilder sb = new StringBuilder(BAR_COLS + 32);
        sb.append('│');
        for (int i = 0; i < full; i++) sb.append('█');

        boolean hasFrac = frac > 0.06 && full < BAR_COLS;
        if (hasFrac) {
            int fi = Math.min((int) (frac * 8) - 1, FRAC.length - 1);
            sb.append(fi >= 0 ? FRAC[fi] : '▏');
        }

        int emptyDots = BAR_COLS - full - (hasFrac ? 1 : 0);
        for (int i = 0; i < emptyDots; i++) sb.append('·');
        sb.append('│');

        sb.append(String.format(" %2d%% EXECUTING [%ds]", pct, elapsed));
        if (chars > 0) sb.append(String.format(" · %,d chars", chars));

        return sb.toString();
    }

    /**
     * Asymptotic progress curve: {@code 1 − e^(−t/K)}, capped at 0.97. Gives roughly 50% at 20 s,
     * 75% at 39 s, 90% at 65 s.
     */
    private static double computeProgress(long elapsedSeconds) {
        return Math.min(0.97, 1.0 - Math.exp(-elapsedSeconds / TIME_K));
    }
}
