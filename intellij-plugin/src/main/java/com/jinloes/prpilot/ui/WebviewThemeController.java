package com.jinloes.prpilot.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.UIUtil;
import javax.swing.UIManager;
import org.apache.commons.lang3.StringUtils;

/** Publishes look-and-feel changes to the webview. */
final class WebviewThemeController {
    private final WebviewPanel panel;

    WebviewThemeController(WebviewPanel panel) {
        this.panel = panel;
    }

    void pushCurrentTheme() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (panel.isDisposedForHost()) {
                                return;
                            }
                            String lafName =
                                    StringUtils.defaultString(
                                                    UIManager.getLookAndFeel() == null
                                                            ? null
                                                            : UIManager.getLookAndFeel().getName())
                                            .toLowerCase(java.util.Locale.ROOT);
                            boolean highContrast = lafName.contains("contrast");
                            boolean dark = UIUtil.isUnderDarcula();
                            String theme = HostThemeClassifier.classify(dark, highContrast);
                            panel.pushMessage(
                                    new WebviewBridgeMessages.ThemeChangedMsg(
                                            "themeChanged", theme));
                        });
    }
}
