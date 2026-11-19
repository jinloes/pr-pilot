package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.ui.JBColor;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicLookAndFeel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewThemeControllerTest {

    @Nested
    class ResolveCurrentTheme {

        @Test
        void usesJbColorBrightnessAndLookAndFeelContrastName() throws Exception {
            boolean previousBright = !JBColor.isBright();
            LookAndFeel previousLaf = UIManager.getLookAndFeel();
            try {
                JBColor.setDark(true);
                UIManager.setLookAndFeel(namedLookAndFeel("Custom Midnight Theme"));
                assertThat(WebviewThemeController.resolveCurrentTheme()).isEqualTo("dark");

                UIManager.setLookAndFeel(namedLookAndFeel("High Contrast Dark"));
                assertThat(WebviewThemeController.resolveCurrentTheme())
                        .isEqualTo("highContrastDark");

                JBColor.setDark(false);
                UIManager.setLookAndFeel(namedLookAndFeel("IntelliJ Light"));
                assertThat(WebviewThemeController.resolveCurrentTheme()).isEqualTo("light");

                UIManager.setLookAndFeel(namedLookAndFeel("High Contrast Light"));
                assertThat(WebviewThemeController.resolveCurrentTheme())
                        .isEqualTo("highContrastLight");
            } finally {
                JBColor.setDark(previousBright);
                if (previousLaf != null) {
                    UIManager.setLookAndFeel(previousLaf);
                }
            }
        }

        private static LookAndFeel namedLookAndFeel(String name) {
            return new BasicLookAndFeel() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public String getID() {
                    return name;
                }

                @Override
                public String getDescription() {
                    return name;
                }

                @Override
                public boolean isNativeLookAndFeel() {
                    return false;
                }

                @Override
                public boolean isSupportedLookAndFeel() {
                    return true;
                }
            };
        }
    }
}
