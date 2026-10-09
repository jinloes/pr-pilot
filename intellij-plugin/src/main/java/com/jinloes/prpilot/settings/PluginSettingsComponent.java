package com.jinloes.prpilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PRNotificationService;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.awt.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;

public class PluginSettingsComponent {

    @FunctionalInterface
    interface ProfileNamePrompt {
        String prompt(
                Component parent, String title, String initialValue, String validationMessage);
    }

    static final String PROFILE_NAME_REQUIRED = ReviewGuidanceEditor.PROFILE_NAME_REQUIRED;
    static final String MODELS_LOADING_HINT = CopilotModelSelector.MODELS_LOADING_HINT;

    /** Seam over {@link CopilotModelDiscovery} so tests don't spawn the Copilot CLI. */
    interface CopilotModelCatalog {
        CopilotModelDiscovery.Result cached();

        CopilotModelDiscovery.Result refresh();

        CopilotModelCatalog DISCOVERY =
                new CopilotModelCatalog() {
                    @Override
                    public CopilotModelDiscovery.Result cached() {
                        return CopilotModelDiscovery.cached();
                    }

                    @Override
                    public CopilotModelDiscovery.Result refresh() {
                        return CopilotModelDiscovery.refresh();
                    }
                };
    }

    private static final int MAX_FORM_WIDTH = 560;

    private JPanel mainPanel;
    private JPanel rootPanel;
    private final JBTextField baseUrlField = new JBTextField("https://github.com");
    private final CopilotModelSelector copilot;
    private final ReviewGuidanceEditor guidance;
    private final RepositoryInstructionsEditor repositoryInstructions =
            new RepositoryInstructionsEditor();
    private final JCheckBox reviewSelfCritiqueBox =
            new JCheckBox("Validate findings with a second pass");
    private final JCheckBox reviewSupervisorBox = new JCheckBox("Re-inspect coverage gaps");
    private final JCheckBox experimentalIntellijAssistedBox =
            new JCheckBox("Enable IntelliJ-assisted review (experimental)");
    private final JCheckBox notificationsEnabledBox =
            new JCheckBox("Enable background PR notifications (experimental)");
    private final JCheckBox notifyReviewRequestedBox =
            new JCheckBox("Notify when a review is requested from me");
    private final JCheckBox notifyStarredReposBox =
            new JCheckBox("Notify for new PRs in starred repositories");
    private final JSpinner pollIntervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JLabel statusLabel = new JBLabel("Checking…");
    private final JButton checkButton = new JButton("Check connection");
    private final JLabel pollStatusLabel = new JBLabel(" ");
    private final AuthCheckCoordinator authChecks;
    private final Supplier<String> pollStatusSupplier;
    private final Function<String, CheckAuthResult> authChecker;
    private final Consumer<Runnable> backgroundExecutor;
    private final Consumer<Runnable> uiExecutor;
    private JPanel notifSubPanel;

    public PluginSettingsComponent() {
        this(
                baseUrl -> IntellijGitHubService.getInstance().checkAuth(baseUrl),
                task -> ApplicationManager.getApplication().executeOnPooledThread(task),
                SwingUtilities::invokeLater,
                () -> PRNotificationService.getInstance().getLastPollStatus(),
                PluginSettingsComponent::showProfileNamePrompt);
    }

    PluginSettingsComponent(
            Function<String, CheckAuthResult> authChecker,
            Consumer<Runnable> backgroundExecutor,
            Consumer<Runnable> uiExecutor) {
        this(
                authChecker,
                backgroundExecutor,
                uiExecutor,
                () -> PRNotificationService.getInstance().getLastPollStatus(),
                PluginSettingsComponent::showProfileNamePrompt);
    }

    PluginSettingsComponent(
            Function<String, CheckAuthResult> authChecker,
            Consumer<Runnable> backgroundExecutor,
            Consumer<Runnable> uiExecutor,
            Supplier<String> pollStatusSupplier) {
        this(
                authChecker,
                backgroundExecutor,
                uiExecutor,
                pollStatusSupplier,
                PluginSettingsComponent::showProfileNamePrompt);
    }

    PluginSettingsComponent(
            Function<String, CheckAuthResult> authChecker,
            Consumer<Runnable> backgroundExecutor,
            Consumer<Runnable> uiExecutor,
            Supplier<String> pollStatusSupplier,
            ProfileNamePrompt profileNamePrompt) {
        this(
                authChecker,
                backgroundExecutor,
                uiExecutor,
                pollStatusSupplier,
                profileNamePrompt,
                CopilotModelCatalog.DISCOVERY);
    }

    PluginSettingsComponent(
            Function<String, CheckAuthResult> authChecker,
            Consumer<Runnable> backgroundExecutor,
            Consumer<Runnable> uiExecutor,
            Supplier<String> pollStatusSupplier,
            ProfileNamePrompt profileNamePrompt,
            CopilotModelCatalog copilotModelCatalog) {
        this.authChecker = authChecker;
        this.backgroundExecutor = backgroundExecutor;
        this.uiExecutor = uiExecutor;
        this.pollStatusSupplier = pollStatusSupplier;
        this.authChecks = new AuthCheckCoordinator(authChecker, backgroundExecutor, uiExecutor);
        this.guidance = new ReviewGuidanceEditor(profileNamePrompt, () -> mainPanel);
        this.copilot =
                new CopilotModelSelector(copilotModelCatalog, backgroundExecutor, uiExecutor);
        checkButton.addActionListener(e -> checkStatus());
        mainPanel = createMainPanel();
    }

    private JPanel createMainPanel() {
        JLabel note =
                SettingsUi.hintLabel(
                        "<html><small>Authentication uses the <b>gh</b> CLI. Run <code>gh auth login</code>"
                                + " if needed. Change the base URL for GitHub Enterprise.</small></html>");
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
        notificationsEnabledBox.addChangeListener(
                (ChangeEvent e) -> updateNotificationSubOptions());
        updateNotificationSubOptions();

        JPanel main =
                FormBuilder.createFormBuilder()
                        .addComponent(sectionTitle("GitHub connection"), 1)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel("Base URL:", baseUrlField),
                                SettingsUi.fieldWithHint(baseUrlField, note),
                                1,
                                false)
                        .addComponent(statusPanel, 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review provider"), 1)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel("Provider:", copilot.providerCombo()),
                                SettingsUi.contentField(copilot.providerCombo()),
                                1,
                                false)
                        .addLabeledComponent(
                                copilot.modelLabel(), copilot.modelComboPanel(), 1, false)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel(
                                        "Second reviewer:", copilot.secondReviewerModelCombo()),
                                SettingsUi.fieldWithHint(
                                        copilot.secondReviewerModelCombo(),
                                        SettingsUi.hintLabel(
                                                "<html><small>Optional. Runs a second Copilot model in parallel"
                                                        + " with either provider; findings are cross-validated."
                                                        + " Choose Off to disable.</small></html>")),
                                1,
                                false)
                        .addComponentToRightColumn(copilot.advancedSection(), 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review guidance"), 1)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel("Profile:", guidance.profileCombo()),
                                SettingsUi.fieldWithHint(
                                        guidance.profileControl(),
                                        SettingsUi.hintLabel(
                                                "<html><small>Save and reuse focus areas and custom"
                                                        + " instructions as one named profile.</small></html>")),
                                1,
                                false)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel("Focus areas:", guidance.focusAreasField()),
                                SettingsUi.fieldWithHint(
                                        guidance.focusAreasField(),
                                        SettingsUi.hintLabel(
                                                "<html><small>Comma-separated areas to prioritize (for example"
                                                        + " security, performance, or test coverage).</small></html>")),
                                1,
                                false)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel(
                                        "Custom instructions:", guidance.customInstructionsArea()),
                                SettingsUi.fieldWithHint(
                                        SettingsUi.boundedTextArea(
                                                guidance.customInstructionsArea()),
                                        SettingsUi.hintLabel(
                                                "<html><small>Extra instructions appended to every review"
                                                        + " prompt, such as team conventions.</small></html>")),
                                1,
                                false)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel(
                                        "Remembered repository instructions:",
                                        repositoryInstructions.combo()),
                                SettingsUi.fieldWithHint(
                                        repositoryInstructions.control(),
                                        SettingsUi.hintLabel(
                                                "<html><small>Added to every review of that repository, together"
                                                        + " with the instructions above. Add a repository from a"
                                                        + " review's instructions with “Remember for this"
                                                        + " repository”.</small></html>")),
                                1,
                                false)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel("Rules folder:", rulesDirectoryField),
                                SettingsUi.fieldWithHint(
                                        rulesDirectoryField,
                                        SettingsUi.hintLabel(
                                                "<html><small>Optional absolute path to a local folder of"
                                                        + " review rules (.md, .yaml, .yml), such as a checkout of"
                                                        + " your team's central review rules. Structured rules"
                                                        + " run when their trigger matches; each rule is applied"
                                                        + " by its own agent.</small></html>")),
                                1,
                                false)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review validation"), 1)
                        .addComponentToRightColumn(
                                SettingsUi.fieldWithHint(
                                        reviewSelfCritiqueBox,
                                        SettingsUi.hintLabel(
                                                "<html><small>Re-checks each finding against the diff to improve"
                                                        + " precision; roughly doubles review time.</small></html>")),
                                1)
                        .addComponentToRightColumn(
                                SettingsUi.fieldWithHint(
                                        reviewSupervisorBox,
                                        SettingsUi.hintLabel(
                                                "<html><small>Runs a bounded coverage check, then targeted"
                                                        + " follow-ups that re-review changed files the review"
                                                        + " never mentioned and its uninspected high-risk hunks."
                                                        + " Only adds latency when the review left a"
                                                        + " gap.</small></html>")),
                                1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Advanced review options"), 1)
                        .addComponentToRightColumn(
                                SettingsUi.fieldWithHint(
                                        experimentalIntellijAssistedBox,
                                        SettingsUi.hintLabel(
                                                "<html><small>Shows the IntelliJ-assisted option in the review"
                                                        + " pane's advanced options. Requires a separate IntelliJ"
                                                        + " IDEA 262+ installation, a hand-written"
                                                        + " ~/.pr-pilot/semantic-review.json, and manually"
                                                        + " importing a retained worktree per review. Off by"
                                                        + " default; ordinary reviews are unaffected. Turning this"
                                                        + " off also hides retained-worktree maintenance; turn it"
                                                        + " back on to remove retained worktrees.</small></html>")),
                                1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Notifications"), 1)
                        .addComponent(notificationsEnabledBox, 1)
                        .addComponent(notifSubPanel, 1)
                        .addComponentFillVertically(new JPanel(), 0)
                        .getPanel();
        refreshPollStatus();
        refreshPollStatus();
        return main;
    }

    private final JBTextField rulesDirectoryField = new JBTextField();

    private void updateNotificationSubOptions() {
        boolean on = notificationsEnabledBox.isSelected();
        if (notifSubPanel != null) {
            notifSubPanel.setVisible(on);
        }
    }

    private static JBLabel sectionTitle(String text) {
        JBLabel label = new JBLabel("<html><b>" + text + "</b></html>");
        label.setBorder(JBUI.Borders.emptyTop(8));
        return label;
    }

    public JPanel getPanel() {
        if (rootPanel == null) {
            mainPanel.setMaximumSize(
                    new java.awt.Dimension(JBUI.scale(MAX_FORM_WIDTH), Integer.MAX_VALUE));
            mainPanel.setAlignmentY(Component.TOP_ALIGNMENT);
            rootPanel = new JPanel();
            rootPanel.setLayout(new BoxLayout(rootPanel, BoxLayout.X_AXIS));
            rootPanel.add(mainPanel);
            rootPanel.add(Box.createHorizontalGlue());
        }
        return rootPanel;
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

    public void setNotificationsEnabled(boolean value) {
        notificationsEnabledBox.setSelected(value);
        updateNotificationSubOptions();
    }

    public boolean isNotifyReviewRequested() {
        return notifyReviewRequestedBox.isSelected();
    }

    public void setNotifyReviewRequested(boolean value) {
        notifyReviewRequestedBox.setSelected(value);
    }

    public boolean isNotifyStarredRepos() {
        return notifyStarredReposBox.isSelected();
    }

    public void setNotifyStarredRepos(boolean value) {
        notifyStarredReposBox.setSelected(value);
    }

    public int getNotificationPollMinutes() {
        return (Integer) pollIntervalSpinner.getValue();
    }

    public void setNotificationPollMinutes(int value) {
        pollIntervalSpinner.setValue(value);
    }

    public String getReviewModel() {
        return copilot.getReviewModel();
    }

    public void setReviewModel(String modelId) {
        copilot.setReviewModel(modelId);
    }

    public String getReviewModelCopilot() {
        return copilot.getReviewModelCopilot();
    }

    public void setReviewModelCopilot(String modelId) {
        copilot.setReviewModelCopilot(modelId);
    }

    public ReviewProvider getReviewProvider() {
        return copilot.getReviewProvider();
    }

    public void setReviewProvider(ReviewProvider provider) {
        copilot.setReviewProvider(provider);
    }

    public String getReviewEffort() {
        return copilot.getReviewEffort();
    }

    public void setReviewEffort(String effort) {
        copilot.setReviewEffort(effort);
    }

    public boolean isCopilotInheritMcp() {
        return copilot.isCopilotInheritMcp();
    }

    public void setCopilotInheritMcp(boolean value) {
        copilot.setCopilotInheritMcp(value);
    }

    public String getCopilotConfigDir() {
        return copilot.getCopilotConfigDir();
    }

    public void setCopilotConfigDir(String value) {
        copilot.setCopilotConfigDir(value);
    }

    public boolean isCopilotAutoEnableMcpOnReview() {
        return copilot.isCopilotAutoEnableMcpOnReview();
    }

    public void setCopilotAutoEnableMcpOnReview(boolean value) {
        copilot.setCopilotAutoEnableMcpOnReview(value);
    }

    public String getReviewRulesDirectory() {
        return rulesDirectoryField.getText().trim();
    }

    public void setReviewRulesDirectory(String value) {
        rulesDirectoryField.setText(value != null ? value : "");
    }

    public String getReviewFocusAreas() {
        return guidance.getFocusAreas();
    }

    public void setReviewFocusAreas(String value) {
        guidance.setFocusAreas(value);
    }

    public String getReviewCustomInstructions() {
        return guidance.getCustomInstructions();
    }

    public void setReviewCustomInstructions(String value) {
        guidance.setCustomInstructions(value);
    }

    public String getReviewGuidanceGlobs() {
        return guidance.getGuidanceGlobs();
    }

    public void setReviewGuidanceGlobs(String value) {
        guidance.setGuidanceGlobs(value);
    }

    public List<PluginSettings.ReviewGuidanceProfile> getReviewGuidanceProfiles() {
        return guidance.getProfiles();
    }

    public void setReviewGuidanceProfiles(List<PluginSettings.ReviewGuidanceProfile> profiles) {
        guidance.setProfiles(profiles);
    }

    public String getActiveReviewGuidanceProfileId() {
        return guidance.getActiveProfileId();
    }

    public void setActiveReviewGuidanceProfileId(String profileId) {
        guidance.setActiveProfileId(profileId);
    }

    public Map<String, String> getRepositoryReviewInstructions() {
        return repositoryInstructions.get();
    }

    public void setRepositoryReviewInstructions(Map<String, String> instructions) {
        repositoryInstructions.set(instructions);
    }

    public String repositoryWithOversizedInstructions() {
        return repositoryInstructions.oversizedRepository();
    }

    public boolean isReviewSelfCritique() {
        return reviewSelfCritiqueBox.isSelected();
    }

    public void setReviewSelfCritique(boolean value) {
        reviewSelfCritiqueBox.setSelected(value);
    }

    public String getReviewSecondReviewerModel() {
        return copilot.getReviewSecondReviewerModel();
    }

    public void setReviewSecondReviewerModel(String value) {
        copilot.setReviewSecondReviewerModel(value);
    }

    public boolean isReviewSupervisorEnabled() {
        return reviewSupervisorBox.isSelected();
    }

    public void setReviewSupervisorEnabled(boolean value) {
        reviewSupervisorBox.setSelected(value);
    }

    public boolean isExperimentalIntellijAssistedReview() {
        return experimentalIntellijAssistedBox.isSelected();
    }

    public void setExperimentalIntellijAssistedReview(boolean value) {
        experimentalIntellijAssistedBox.setSelected(value);
    }

    static String profileNameError(String value) {
        return ReviewGuidanceEditor.profileNameError(value);
    }

    static String copilotModelStatus(CopilotModelDiscovery.Result result, boolean hasPreviousList) {
        return CopilotModelSelector.copilotModelStatus(result, hasPreviousList);
    }

    List<String> getCopilotModelOptions() {
        return copilot.getCopilotModelOptions();
    }

    List<String> getSecondReviewerModelOptions() {
        return copilot.getSecondReviewerModelOptions();
    }

    String getCopilotModelHintText() {
        return copilot.getCopilotModelHintText();
    }

    JButton getRefreshModelsButton() {
        return copilot.getRefreshModelsButton();
    }

    private void checkStatus() {
        long checkId = authChecks.begin();
        checkButton.setEnabled(false);
        statusLabel.setText("Checking…");
        String baseUrl;
        try {
            baseUrl = GithubBaseUrlValidator.normalize(baseUrlField.getText());
        } catch (IllegalArgumentException e) {
            statusLabel.setText("<html><font color='red'>" + e.getMessage() + "</font></html>");
            checkButton.setEnabled(true);
            return;
        }
        authChecks.execute(
                checkId,
                baseUrl,
                result -> {
                    if ("authenticated".equals(result.status()) && result.username() != null) {
                        statusLabel.setText("Signed in as @" + result.username());
                    } else {
                        statusLabel.setText(
                                "<html><font color='red'>" + result.message() + "</font></html>");
                    }
                    checkButton.setEnabled(true);
                });
    }

    private void refreshPollStatus() {
        String status = pollStatusSupplier.get();
        if (status == null) {
            pollStatusLabel.setText(" ");
            return;
        }
        boolean isError = status.contains("Error:");
        String color = isError ? "red" : "gray";
        pollStatusLabel.setText(
                "<html><font color='" + color + "'><small>" + status + "</small></font></html>");
    }

    void refreshAuthStatus() {
        checkStatus();
    }

    static final class AuthCheckCoordinator {
        private final Function<String, CheckAuthResult> authChecker;
        private final Consumer<Runnable> backgroundExecutor;
        private final Consumer<Runnable> uiExecutor;
        private final AtomicLong sequence = new AtomicLong();

        AuthCheckCoordinator(
                Function<String, CheckAuthResult> authChecker,
                Consumer<Runnable> backgroundExecutor,
                Consumer<Runnable> uiExecutor) {
            this.authChecker = authChecker;
            this.backgroundExecutor = backgroundExecutor;
            this.uiExecutor = uiExecutor;
        }

        long begin() {
            return sequence.incrementAndGet();
        }

        void execute(long checkId, String baseUrl, Consumer<CheckAuthResult> resultConsumer) {
            backgroundExecutor.accept(
                    () -> {
                        CheckAuthResult result = authChecker.apply(baseUrl);
                        uiExecutor.accept(
                                () -> {
                                    if (checkId == sequence.get()) {
                                        resultConsumer.accept(result);
                                    }
                                });
                    });
        }
    }

    private static String showProfileNamePrompt(
            Component parent, String title, String initialValue, String validationMessage) {
        String message =
                validationMessage == null
                        ? "Profile name:"
                        : validationMessage + System.lineSeparator() + "Profile name:";
        return (String)
                javax.swing.JOptionPane.showInputDialog(
                        parent,
                        message,
                        title,
                        javax.swing.JOptionPane.PLAIN_MESSAGE,
                        null,
                        null,
                        initialValue);
    }
}
