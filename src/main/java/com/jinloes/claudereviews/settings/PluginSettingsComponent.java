package com.jinloes.claudereviews.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.jinloes.claudereviews.services.GitHubAuthService;
import com.jinloes.claudereviews.services.PRNotificationService;
import java.util.List;
import javax.swing.*;
import javax.swing.event.ChangeEvent;

public class PluginSettingsComponent {

    private record ModelOption(String label, String id) {}

    private static final List<ModelOption> MODELS =
            List.of(
                    new ModelOption("CLI default (unset)", ""),
                    new ModelOption("Haiku — fastest", "claude-haiku-4-5-20251001"),
                    new ModelOption("Sonnet — balanced", "claude-sonnet-4-6"),
                    new ModelOption("Opus — most thorough", "claude-opus-4-7"));

    private final JPanel mainPanel;
    private final JBTextField baseUrlField = new JBTextField("https://github.com");
    private final JComboBox<String> modelCombo =
            new JComboBox<>(MODELS.stream().map(ModelOption::label).toArray(String[]::new));

    private final JLabel statusLabel = new JBLabel("Checking…");
    private final JButton checkButton = new JButton("Check Status");

    // Notification settings
    private final JCheckBox notificationsEnabledBox =
            new JCheckBox("Enable background PR notifications");
    private final JCheckBox notifyReviewRequestedBox =
            new JCheckBox("Notify when a review is requested from me");
    private final JCheckBox notifyStarredReposBox =
            new JCheckBox("Notify when a new PR is opened on a starred repo");
    private final JSpinner pollIntervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JLabel pollStatusLabel = new JBLabel(" ");

    private final GitHubAuthService authService = new GitHubAuthService();

    public PluginSettingsComponent() {
        checkButton.addActionListener(e -> checkStatus());

        JLabel note =
                new JBLabel(
                        "<html><small>Authentication is handled by the <b>gh</b> CLI.<br>"
                                + "Run <code>gh auth login</code> in a terminal if not signed in.<br>"
                                + "For GitHub Enterprise, change the Base URL to your company's GitHub host.</small></html>");
        note.setBorder(JBUI.Borders.emptyTop(4));

        JPanel statusPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        statusPanel.add(checkButton);
        statusPanel.add(statusLabel);

        // Enable/disable sub-options when master checkbox changes
        notificationsEnabledBox.addChangeListener(
                (ChangeEvent e) -> updateNotificationSubOptions());
        updateNotificationSubOptions();

        JPanel pollPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        pollPanel.add(new JBLabel("Poll every"));
        pollPanel.add(pollIntervalSpinner);
        pollPanel.add(new JBLabel("minutes"));

        mainPanel =
                FormBuilder.createFormBuilder()
                        .addLabeledComponent(
                                new JBLabel("GitHub Base URL:"), baseUrlField, 1, false)
                        .addComponent(note, 1)
                        .addSeparator(8)
                        .addComponent(statusPanel, 1)
                        .addSeparator(8)
                        .addLabeledComponent(new JBLabel("Review model:"), modelCombo, 1, false)
                        .addSeparator(8)
                        .addComponent(notificationsEnabledBox, 1)
                        .addComponent(notifyReviewRequestedBox, 1)
                        .addComponent(notifyStarredReposBox, 1)
                        .addComponent(pollPanel, 1)
                        .addComponent(pollStatusLabel, 1)
                        .addComponentFillVertically(new JPanel(), 0)
                        .getPanel();

        refreshPollStatus();
        refreshAuthStatus();
    }

    private void updateNotificationSubOptions() {
        boolean on = notificationsEnabledBox.isSelected();
        notifyReviewRequestedBox.setEnabled(on);
        notifyStarredReposBox.setEnabled(on);
        pollIntervalSpinner.setEnabled(on);
    }

    public JPanel getPanel() {
        return mainPanel;
    }

    public JComponent getPreferredFocusedComponent() {
        return baseUrlField;
    }

    public String getGithubBaseUrl() {
        return baseUrlField.getText().trim();
    }

    public void setGithubBaseUrl(String url) {
        baseUrlField.setText(url);
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabledBox.isSelected();
    }

    public void setNotificationsEnabled(boolean v) {
        notificationsEnabledBox.setSelected(v);
        updateNotificationSubOptions();
    }

    public boolean isNotifyReviewRequested() {
        return notifyReviewRequestedBox.isSelected();
    }

    public void setNotifyReviewRequested(boolean v) {
        notifyReviewRequestedBox.setSelected(v);
    }

    public boolean isNotifyStarredRepos() {
        return notifyStarredReposBox.isSelected();
    }

    public void setNotifyStarredRepos(boolean v) {
        notifyStarredReposBox.setSelected(v);
    }

    public int getNotificationPollMinutes() {
        return (Integer) pollIntervalSpinner.getValue();
    }

    public void setNotificationPollMinutes(int v) {
        pollIntervalSpinner.setValue(v);
    }

    public String getReviewModel() {
        int idx = modelCombo.getSelectedIndex();
        return idx >= 0 && idx < MODELS.size() ? MODELS.get(idx).id() : "";
    }

    public void setReviewModel(String modelId) {
        String id = modelId != null ? modelId : "";
        for (int i = 0; i < MODELS.size(); i++) {
            if (MODELS.get(i).id().equals(id)) {
                modelCombo.setSelectedIndex(i);
                return;
            }
        }
        modelCombo.setSelectedIndex(0);
    }

    private void checkStatus() {
        checkButton.setEnabled(false);
        statusLabel.setText("Checking…");

        String baseUrl = baseUrlField.getText().trim().replaceAll("/$", "");
        String apiUrl =
                baseUrl.equals("https://github.com")
                        ? "https://api.github.com"
                        : baseUrl + "/api/v3";

        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                String token = authService.resolveToken(baseUrl);
                                String username =
                                        authService.getAuthenticatedUsername(apiUrl, token);
                                PluginSettings.getInstance().setGithubUsername(username);
                                SwingUtilities.invokeLater(
                                        () -> {
                                            statusLabel.setText("Signed in as @" + username);
                                            checkButton.setEnabled(true);
                                        });
                            } catch (Exception ex) {
                                SwingUtilities.invokeLater(
                                        () -> {
                                            statusLabel.setText(
                                                    "<html><font color='red'>Not signed in — run 'gh auth login'</font></html>");
                                            checkButton.setEnabled(true);
                                        });
                            }
                        });
    }

    private void refreshPollStatus() {
        PRNotificationService svc = PRNotificationService.getInstance();
        String status = svc.getLastPollStatus();
        if (status == null) {
            pollStatusLabel.setText(" ");
            return;
        }
        boolean isError = status.contains("Error:");
        String color = isError ? "red" : "gray";
        pollStatusLabel.setText(
                "<html><font color='" + color + "'><small>" + status + "</small></font></html>");
    }

    private void refreshAuthStatus() {
        PluginSettings settings = PluginSettings.getInstance();
        String username = settings.getGithubUsername();
        if (!username.isBlank()) {
            statusLabel.setText("Signed in as @" + username);
        } else {
            checkStatus();
        }
    }
}
