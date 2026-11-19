package com.jinloes.prpilot.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.JBColor;
import java.util.Locale;
import javax.swing.UIManager;
import org.apache.commons.lang3.StringUtils;

/** Publishes look-and-feel changes to the webview. */
final class WebviewThemeController {
    private final WebviewPanel panel;

    WebviewThemeController(WebviewPanel panel) {
        this.panel = panel;
    }

    static String resolveCurrentTheme() {
        String lafName =
                StringUtils.defaultString(
                                UIManager.getLookAndFeel() == null
                                        ? null
                                        : UIManager.getLookAndFeel().getName())
                        .toLowerCase(Locale.ROOT);
        boolean highContrast = lafName.contains("contrast");
        boolean dark = !JBColor.isBright();
        return HostThemeClassifier.classify(dark, highContrast);
    }

    void pushCurrentTheme() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (panel.isDisposedForHost()) {
                                return;
                            }
                            panel.pushMessage(
                                    new WebviewBridgeMessages.ThemeChangedMsg(
                                            "themeChanged", resolveCurrentTheme()));
                        });
    }
}
