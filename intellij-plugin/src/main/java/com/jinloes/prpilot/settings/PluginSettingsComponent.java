package com.jinloes.prpilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.services.CopilotModelDiscovery;
import com.jinloes.prpilot.services.GitHubAuthService;
import com.jinloes.prpilot.services.PRNotificationService;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.*;
import javax.swing.event.ChangeEvent;

public class PluginSettingsComponent {

    private record ModelOption(String label, String id) {}

    private static final List<ModelOption> CLAUDE_MODELS =
            List.of(
                    new ModelOption("CLI default (unset)", ""),
                    new ModelOption("Haiku — fastest", "claude-haiku-4-5-20251001"),
                    new ModelOption("Sonnet — balanced", "claude-sonnet-4-6"),
                    new ModelOption("Opus — most thorough", "claude-opus-4-7"));

    /**
     * Recent Copilot CLI model IDs offered as autocomplete suggestions. This list is intentionally
     * a small subset rather than the full catalog — Copilot's available models change frequently,
     * and the field is editable so users can type any ID. Run {@code copilot help config} to see
     * what the installed CLI actually supports.
     */
    private static final String[] COPILOT_MODEL_SUGGESTIONS = {
        "", "claude-sonnet-4.6", "claude-opus-4.7", "gpt-5.5", "gpt-5.4",
    };

    /**
     * Reasoning-effort levels accepted by {@code copilot --reasoning-effort}, per the CLI help.
     * "medium" is the default — balances catching real issues against latency.
     */
    private static final String[] COPILOT_EFFORTS = {
        "none", "low", "medium", "high", "xhigh", "max"
    };

    private final JPanel mainPanel;
    private final JBTextField baseUrlField = new JBTextField("https://github.com");
    private final JComboBox<String> claudeModelCombo =
            new JComboBox<>(CLAUDE_MODELS.stream().map(ModelOption::label).toArray(String[]::new));
    private final JComboBox<String> copilotModelCombo = new JComboBox<>(COPILOT_MODEL_SUGGESTIONS);
    private final JComboBox<String> copilotEffortCombo = new JComboBox<>(COPILOT_EFFORTS);
    private final JComboBox<ReviewProvider> providerCombo =
            new JComboBox<>(ReviewProvider.values());

    private final JPanel modelComboPanel = new JPanel(new java.awt.CardLayout());
    private final JPanel copilotModelCard = new JPanel();
    private JPanel effortRowPanel;

    private final JLabel statusLabel = new JBLabel("Checking…");
    private final JButton checkButton = new JButton("Check Status");

    // Notification settings
    private final JCheckBox notificationsEnabledBox =
            new JCheckBox("Enable background PR notifications (experimental)");
    private final JCheckBox notifyReviewRequestedBox =
            new JCheckBox("Notify when a review is requested from me");
    private final JCheckBox notifyStarredReposBox =
            new JCheckBox("Notify when a new PR is opened on a starred repo");
    private final JSpinner pollIntervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JLabel pollStatusLabel = new JBLabel(" ");
    private JPanel notifSubPanel;

    private final GitHubAuthService authService = new GitHubAuthService();

    public PluginSettingsComponent() {
        checkButton.addActionListener(e -> checkStatus());

        providerCombo.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public java.awt.Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean isSelected,
                            boolean cellHasFocus) {
                        super.getListCellRendererComponent(
                                list, value, index, isSelected, cellHasFocus);
                        if (value instanceof ReviewProvider p) setText(p.getDisplayName());
                        return this;
                    }
                });

        copilotModelCombo.setEditable(true);
        copilotModelCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel copilotHint =
                new JBLabel(
                        "<html><small>Auto-populated from <code>copilot help config</code>;"
                                + " type any model ID to override.</small></html>");
        copilotHint.setBorder(JBUI.Borders.emptyTop(2));
        // BoxLayout centers children unless told otherwise. Force LEFT_ALIGNMENT on every child of
        // copilotModelCard or the hint floats to the middle/right of the row.
        copilotHint.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Probe the CLI off the EDT — its first call can take up to 10 seconds. The dropdown
        // starts with the hardcoded suggestions so users see something immediately; results from
        // the probe (cached for the session) augment the list when they arrive.
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            List<String> discovered = CopilotModelDiscovery.listModels();
                            if (discovered.isEmpty()) return;
                            SwingUtilities.invokeLater(() -> mergeCopilotModelOptions(discovered));
                        });

        copilotModelCard.setLayout(new BoxLayout(copilotModelCard, BoxLayout.Y_AXIS));
        copilotModelCard.add(copilotModelCombo);
        copilotModelCard.add(copilotHint);

        modelComboPanel.add(claudeModelCombo, ReviewProvider.CLAUDE.getId());
        modelComboPanel.add(copilotModelCard, ReviewProvider.COPILOT.getId());
        providerCombo.addActionListener(e -> updateActiveModelCombo());

        // Effort lives on its own FormBuilder row so the "Reasoning effort:" label aligns with
        // "Review provider:" / "Review model:" in the left column. Combo is disabled when the
        // active provider is Claude (since `claude` doesn't support --reasoning-effort) rather
        // than hidden, so the form doesn't reflow as the user toggles providers.
        effortRowPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        effortRowPanel.add(copilotEffortCombo);
        JLabel effortHint =
                new JBLabel(
                        "<html><small>Higher effort = deeper review, slower."
                                + " Applies only to GitHub Copilot.</small></html>");
        effortRowPanel.add(effortHint);

        JLabel note =
                new JBLabel(
                        "<html><small>Authentication is handled by the <b>gh</b> CLI.<br>"
                                + "Run <code>gh auth login</code> in a terminal if not signed in.<br>"
                                + "For GitHub Enterprise, change the Base URL to your company's GitHub host.</small></html>");
        note.setBorder(JBUI.Borders.emptyTop(4));

        JPanel statusPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        statusPanel.add(checkButton);
        statusPanel.add(statusLabel);

        JPanel pollPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        pollPanel.add(new JBLabel("Poll every"));
        pollPanel.add(pollIntervalSpinner);
        pollPanel.add(new JBLabel("minutes"));

        notifSubPanel = new JPanel();
        notifSubPanel.setLayout(new BoxLayout(notifSubPanel, BoxLayout.Y_AXIS));
        notifSubPanel.add(notifyReviewRequestedBox);
        notifSubPanel.add(notifyStarredReposBox);
        notifSubPanel.add(pollPanel);
        notifSubPanel.add(pollStatusLabel);

        // Show/hide sub-options when master checkbox changes
        notificationsEnabledBox.addChangeListener(
                (ChangeEvent e) -> updateNotificationSubOptions());
        updateNotificationSubOptions();

        mainPanel =
                FormBuilder.createFormBuilder()
                        .addLabeledComponent(
                                new JBLabel("GitHub Base URL:"), baseUrlField, 1, false)
                        .addComponent(note, 1)
                        .addSeparator(8)
                        .addComponent(statusPanel, 1)
                        .addSeparator(8)
                        .addLabeledComponent(
                                new JBLabel("Review provider:"), providerCombo, 1, false)
                        .addLabeledComponent(
                                new JBLabel("Review model:"), modelComboPanel, 1, false)
                        .addLabeledComponent(
                                new JBLabel("Reasoning effort:"), effortRowPanel, 1, false)
                        .addSeparator(8)
                        .addComponent(notificationsEnabledBox, 1)
                        .addComponent(notifSubPanel, 1)
                        .addComponentFillVertically(new JPanel(), 0)
                        .getPanel();

        refreshPollStatus();
        refreshAuthStatus();
    }

    private void updateNotificationSubOptions() {
        boolean on = notificationsEnabledBox.isSelected();
        if (notifSubPanel != null) notifSubPanel.setVisible(on);
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
        return selectedId(claudeModelCombo, CLAUDE_MODELS);
    }

    public void setReviewModel(String modelId) {
        selectId(claudeModelCombo, CLAUDE_MODELS, modelId);
    }

    public String getReviewModelCopilot() {
        Object editorValue = copilotModelCombo.getEditor().getItem();
        return editorValue != null ? editorValue.toString().trim() : "";
    }

    public void setReviewModelCopilot(String modelId) {
        String id = modelId != null ? modelId.trim() : "";
        copilotModelCombo.setSelectedItem(id);
        copilotModelCombo.getEditor().setItem(id);
    }

    public ReviewProvider getReviewProvider() {
        Object selected = providerCombo.getSelectedItem();
        return selected instanceof ReviewProvider p ? p : ReviewProvider.CLAUDE;
    }

    public void setReviewProvider(ReviewProvider provider) {
        providerCombo.setSelectedItem(provider != null ? provider : ReviewProvider.CLAUDE);
        updateActiveModelCombo();
    }

    public String getReviewEffort() {
        Object selected = copilotEffortCombo.getSelectedItem();
        return selected instanceof String s && !s.isBlank() ? s : "medium";
    }

    public void setReviewEffort(String effort) {
        String value = effort != null && !effort.isBlank() ? effort : "medium";
        copilotEffortCombo.setSelectedItem(value);
    }

    private void updateActiveModelCombo() {
        ReviewProvider active = getReviewProvider();
        ((java.awt.CardLayout) modelComboPanel.getLayout()).show(modelComboPanel, active.getId());
        // Effort only applies to Copilot — disable the combo for Claude so the form doesn't
        // reflow on every provider toggle. The hint label explains why.
        copilotEffortCombo.setEnabled(active == ReviewProvider.COPILOT);
    }

    /**
     * Replaces the Copilot model dropdown entries with `[""] + discovered`, preserving the user's
     * current editor text. Called on the EDT from the discovery probe callback. Always keeps the
     * empty-string entry first (it represents "CLI default routing").
     */
    private void mergeCopilotModelOptions(List<String> discovered) {
        Object currentEditorValue = copilotModelCombo.getEditor().getItem();
        String currentText = currentEditorValue != null ? currentEditorValue.toString() : "";

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add("");
        merged.addAll(discovered);
        // Preserve any user-typed model that isn't in the discovered list so we don't lose it.
        if (!currentText.isBlank() && !merged.contains(currentText)) merged.add(currentText);

        List<String> ordered = new ArrayList<>(merged);
        copilotModelCombo.setModel(new DefaultComboBoxModel<>(ordered.toArray(new String[0])));
        copilotModelCombo.setSelectedItem(currentText);
        copilotModelCombo.getEditor().setItem(currentText);
    }

    private static String selectedId(JComboBox<String> combo, List<ModelOption> options) {
        int idx = combo.getSelectedIndex();
        return idx >= 0 && idx < options.size() ? options.get(idx).id() : "";
    }

    private static void selectId(
            JComboBox<String> combo, List<ModelOption> options, String modelId) {
        String id = modelId != null ? modelId : "";
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(id)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
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
