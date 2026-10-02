package com.jinloes.prpilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PRNotificationService;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

public class PluginSettingsComponent {

    private record ModelOption(String label, String id) {}

    @FunctionalInterface
    interface ProfileNamePrompt {
        String prompt(
                Component parent, String title, String initialValue, String validationMessage);
    }

    static final String PROFILE_NAME_REQUIRED = "Enter a profile name.";

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

    static final String MODELS_LOADING_HINT = "Loading models available to your Copilot account…";

    private static final List<ModelOption> CLAUDE_MODELS =
            List.of(
                    new ModelOption("CLI default (unset)", ""),
                    new ModelOption("Haiku — fastest", "claude-haiku-4-5-20251001"),
                    new ModelOption("Sonnet — balanced", "claude-sonnet-4-6"),
                    new ModelOption("Opus — most thorough", "claude-opus-4-7"));

    /**
     * Fallback Copilot model IDs shown only until discovery succeeds. This list is intentionally a
     * small subset rather than the full catalog — Copilot's available models change frequently, and
     * the field is editable so users can type any ID.
     */
    private static final String[] COPILOT_MODEL_SUGGESTIONS = {
        "", "claude-sonnet-4.6", "claude-opus-4.7", "claude-opus-4.8", "gpt-5.5", "gpt-5.4",
    };

    /**
     * Reasoning-effort levels accepted by {@code copilot --reasoning-effort}, per the CLI help.
     * "high" is the default — favors catching real issues over latency.
     */
    private static final String[] COPILOT_EFFORTS = {
        "none", "low", "medium", "high", "xhigh", "max"
    };

    /** Caps the settings form width (logical px, before HiDPI scaling) so fields don't sprawl. */
    private static final int MAX_FORM_WIDTH = 560;

    /** Shared content width (logical px, before HiDPI scaling) for primary settings fields. */
    private static final int CONTENT_WIDTH = 320;

    private final JPanel mainPanel;
    private JPanel rootPanel;
    private final JBTextField baseUrlField = new JBTextField("https://github.com");
    private final JComboBox<String> claudeModelCombo =
            new JComboBox<>(CLAUDE_MODELS.stream().map(ModelOption::label).toArray(String[]::new));
    private final JComboBox<String> copilotModelCombo = new JComboBox<>(COPILOT_MODEL_SUGGESTIONS);
    private final JComboBox<String> copilotEffortCombo = new JComboBox<>(COPILOT_EFFORTS);
    private final JCheckBox copilotInheritMcpBox =
            new JCheckBox("Allow Copilot to use MCP tools from your trusted Copilot config");
    private final JCheckBox copilotAutoEnableMcpOnReviewBox =
            new JCheckBox("Always enable MCP for Copilot reviews");
    private final JBTextField copilotConfigDirField = new JBTextField();
    private final JBTextField reviewFocusAreasField = new JBTextField();
    private final JBTextField reviewRulesDirectoryField = new JBTextField();
    private final JComboBox<String> secondReviewerModelCombo =
            new JComboBox<>(COPILOT_MODEL_SUGGESTIONS);
    private final JBTextArea reviewCustomInstructionsArea = new JBTextArea(4, 0);
    private final JBTextArea reviewGuidanceGlobsArea = new JBTextArea(3, 0);
    private final JComboBox<PluginSettings.ReviewGuidanceProfile> reviewGuidanceProfileCombo =
            new JComboBox<>();
    private final JButton addReviewGuidanceProfileButton = new JButton("Save as…");
    private final JButton renameReviewGuidanceProfileButton = new JButton("Rename");
    private final JButton deleteReviewGuidanceProfileButton = new JButton("Delete");
    private final JComboBox<String> repositoryInstructionsCombo = new JComboBox<>();
    private final JBTextArea repositoryInstructionsArea = new JBTextArea(3, 0);
    private final JButton forgetRepositoryInstructionsButton = new JButton("Forget");
    private Map<String, String> repositoryInstructions = new LinkedHashMap<>();
    private String selectedRepository = "";
    private boolean updatingRepositoryInstructions;
    private final ProfileNamePrompt profileNamePrompt;
    private List<PluginSettings.ReviewGuidanceProfile> reviewGuidanceProfiles = new ArrayList<>();
    private String activeReviewGuidanceProfileId = "";
    private String defaultReviewFocusAreas = "";
    private String defaultReviewCustomInstructions = "";
    private String defaultReviewGuidanceGlobs = "";
    private boolean updatingReviewGuidanceProfile;
    private final JCheckBox reviewSelfCritiqueBox =
            new JCheckBox("Validate findings with a second pass");
    private final JCheckBox reviewSupervisorBox = new JCheckBox("Re-inspect coverage gaps");
    private final JCheckBox experimentalIntellijAssistedBox =
            new JCheckBox("Enable IntelliJ-assisted review (experimental)");
    private final JComboBox<ReviewProvider> providerCombo =
            new JComboBox<>(ReviewProvider.values());
    private final JBLabel modelLabel = new JBLabel("Model:");

    private final JPanel modelComboPanel = new JPanel(new java.awt.CardLayout());
    private final JPanel copilotModelCard = new JPanel();
    private final JCheckBox showAdvancedCopilotBox = new JCheckBox("Show advanced Copilot options");
    private final JPanel advancedCopilotSection = new JPanel();
    private final JPanel advancedCopilotPanel = new JPanel();

    private final JLabel statusLabel = new JBLabel("Checking…");
    private final JButton checkButton = new JButton("Check connection");
    private final AuthCheckCoordinator authChecks;
    private final Supplier<String> pollStatusSupplier;
    private final Consumer<Runnable> backgroundExecutor;
    private final Consumer<Runnable> uiExecutor;
    private final CopilotModelCatalog copilotModelCatalog;
    private final JButton refreshModelsButton = new JButton("Refresh");
    private final JBLabel copilotModelHint = hintLabel(hintHtml(MODELS_LOADING_HINT));

    // Notification settings
    private final JCheckBox notificationsEnabledBox =
            new JCheckBox("Enable background PR notifications (experimental)");
    private final JCheckBox notifyReviewRequestedBox =
            new JCheckBox("Notify when a review is requested from me");
    private final JCheckBox notifyStarredReposBox =
            new JCheckBox("Notify for new PRs in starred repositories");
    private final JSpinner pollIntervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JLabel pollStatusLabel = new JBLabel(" ");
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
        this.authChecks = new AuthCheckCoordinator(authChecker, backgroundExecutor, uiExecutor);
        this.pollStatusSupplier = pollStatusSupplier;
        this.profileNamePrompt = profileNamePrompt;
        this.backgroundExecutor = backgroundExecutor;
        this.uiExecutor = uiExecutor;
        this.copilotModelCatalog = copilotModelCatalog;
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
        providerCombo.setPrototypeDisplayValue(ReviewProvider.COPILOT);
        boundContentWidth(providerCombo);

        copilotModelCombo.setEditable(true);
        boundContentWidth(copilotModelCombo);
        boundContentWidth(claudeModelCombo);
        secondReviewerModelCombo.setEditable(true);
        secondReviewerModelCombo.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean isSelected,
                            boolean cellHasFocus) {
                        super.getListCellRendererComponent(
                                list, value, index, isSelected, cellHasFocus);
                        if (value == null || value.toString().isBlank()) setText("Off");
                        return this;
                    }
                });
        boundContentWidth(secondReviewerModelCombo);
        // BoxLayout centers children unless told otherwise. Force LEFT_ALIGNMENT on every child of
        // copilotModelCard or the hint floats to the middle/right of the row.
        copilotModelHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshModelsButton.setToolTipText("Reload the models available to your Copilot account");
        refreshModelsButton.addActionListener(e -> refreshCopilotModels());

        JPanel copilotModelRow = new JPanel();
        copilotModelRow.setLayout(new BoxLayout(copilotModelRow, BoxLayout.X_AXIS));
        copilotModelRow.setFocusable(false);
        copilotModelRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        copilotModelRow.add(copilotModelCombo);
        copilotModelRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        copilotModelRow.add(refreshModelsButton);
        copilotModelRow.setMaximumSize(copilotModelRow.getPreferredSize());

        // Show the last known list instantly, then re-probe on every open: the catalog changes as
        // models roll out, and an IDE-session-long cache left newly available models missing.
        CopilotModelDiscovery.Result cachedModels = copilotModelCatalog.cached();
        if (cachedModels != null) applyCopilotModelResult(cachedModels);
        refreshCopilotModels();

        copilotModelCard.setLayout(new BoxLayout(copilotModelCard, BoxLayout.Y_AXIS));
        copilotModelCard.setFocusable(false);
        copilotModelCard.add(copilotModelRow);
        copilotModelCard.add(copilotModelHint);

        // Wrap the Claude combo in a left-aligned BoxLayout card too: CardLayout ignores
        // maximumSize and would otherwise stretch the bare combo to the full panel width.
        JPanel claudeModelCard = new JPanel();
        claudeModelCard.setLayout(new BoxLayout(claudeModelCard, BoxLayout.Y_AXIS));
        claudeModelCard.setFocusable(false);
        claudeModelCard.add(claudeModelCombo);

        modelComboPanel.add(claudeModelCard, ReviewProvider.CLAUDE.getId());
        modelComboPanel.add(copilotModelCard, ReviewProvider.COPILOT.getId());
        modelLabel.setLabelFor(claudeModelCombo);
        providerCombo.addActionListener(e -> updateActiveModelCombo());

        JLabel effortHint =
                hintLabel(
                        "<html><small>Higher effort = deeper review, slower."
                                + " Applies only to GitHub Copilot.</small></html>");
        JPanel effortField = fieldWithHint(copilotEffortCombo, effortHint);

        JLabel mcpHint =
                hintLabel(
                        "<html><small>Applies while reviewing untrusted pull request content."
                                + " Servers load only from your own Copilot config"
                                + " (<code>~/.copilot/mcp-config.json</code>); a pull request's"
                                + " <code>.mcp.json</code> is never loaded.</small></html>");
        // BoxLayout centers children by default (alignmentX 0.5) unless each child explicitly opts
        // into LEFT_ALIGNMENT — every direct child added below needs it or the row floats to the
        // middle of the form, which is what was happening to the "Show advanced" checkbox.
        JLabel advancedHint =
                hintLabel(
                        "<html><small>Optional controls for reasoning depth, MCP access, and"
                                + " Copilot config discovery.</small></html>");
        advancedHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        showAdvancedCopilotBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        showAdvancedCopilotBox.addActionListener(e -> updateAdvancedCopilotOptionsVisibility());

        JPanel advancedFormPanel =
                FormBuilder.createFormBuilder()
                        .addLabeledComponent(
                                fieldLabel("Reasoning effort:", copilotEffortCombo),
                                effortField,
                                1,
                                false)
                        .addComponentToRightColumn(fieldWithHint(copilotInheritMcpBox, mcpHint), 1)
                        .addComponentToRightColumn(copilotAutoEnableMcpOnReviewBox, 1)
                        .addLabeledComponent(
                                fieldLabel("Copilot config dir:", copilotConfigDirField),
                                copilotConfigDirField,
                                1,
                                false)
                        .getPanel();
        advancedFormPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        advancedCopilotPanel.setLayout(new BoxLayout(advancedCopilotPanel, BoxLayout.Y_AXIS));
        advancedCopilotPanel.setBorder(JBUI.Borders.emptyTop(6));
        advancedCopilotPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        advancedCopilotPanel.add(advancedFormPanel);

        advancedCopilotSection.setLayout(new BoxLayout(advancedCopilotSection, BoxLayout.Y_AXIS));
        advancedCopilotSection.add(fieldWithHint(showAdvancedCopilotBox, advancedHint));
        advancedCopilotSection.add(advancedCopilotPanel);

        JLabel note =
                hintLabel(
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

        // Show/hide sub-options when master checkbox changes
        notificationsEnabledBox.addChangeListener(
                (ChangeEvent e) -> updateNotificationSubOptions());
        updateNotificationSubOptions();

        reviewGuidanceProfileCombo.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean isSelected,
                            boolean cellHasFocus) {
                        super.getListCellRendererComponent(
                                list, value, index, isSelected, cellHasFocus);
                        setText(
                                value instanceof PluginSettings.ReviewGuidanceProfile profile
                                        ? profile.name
                                        : "Default settings");
                        return this;
                    }
                });
        boundContentWidth(baseUrlField);
        boundContentWidth(reviewGuidanceProfileCombo);
        boundContentWidth(reviewFocusAreasField);
        reviewGuidanceProfileCombo.addActionListener(e -> selectReviewGuidanceProfile());
        addReviewGuidanceProfileButton.addActionListener(e -> addReviewGuidanceProfile());
        renameReviewGuidanceProfileButton.addActionListener(e -> renameReviewGuidanceProfile());
        deleteReviewGuidanceProfileButton.addActionListener(e -> deleteReviewGuidanceProfile());
        rebuildReviewGuidanceProfileCombo();
        boundContentWidth(repositoryInstructionsCombo);
        repositoryInstructionsArea
                .getAccessibleContext()
                .setAccessibleName("Instructions for the selected repository");
        repositoryInstructionsCombo.addActionListener(e -> selectRepositoryInstructions());
        forgetRepositoryInstructionsButton.addActionListener(
                e -> forgetSelectedRepositoryInstructions());
        rebuildRepositoryInstructionsCombo();

        JPanel reviewGuidanceProfileActions =
                new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        reviewGuidanceProfileActions.add(addReviewGuidanceProfileButton);
        reviewGuidanceProfileActions.add(renameReviewGuidanceProfileButton);
        reviewGuidanceProfileActions.add(deleteReviewGuidanceProfileButton);
        reviewGuidanceProfileActions.setFocusable(false);
        reviewGuidanceProfileActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        reviewGuidanceProfileActions.setMaximumSize(
                reviewGuidanceProfileActions.getPreferredSize());

        JPanel reviewGuidanceProfilePanel = new JPanel();
        reviewGuidanceProfilePanel.setLayout(
                new BoxLayout(reviewGuidanceProfilePanel, BoxLayout.Y_AXIS));
        reviewGuidanceProfilePanel.setFocusable(false);
        reviewGuidanceProfilePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        reviewGuidanceProfilePanel.add(reviewGuidanceProfileCombo);
        reviewGuidanceProfilePanel.add(reviewGuidanceProfileActions);

        JBScrollPane customInstructionsScrollPane = boundedTextArea(reviewCustomInstructionsArea);
        JPanel repositoryInstructionsRow =
                new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        repositoryInstructionsRow.add(repositoryInstructionsCombo);
        repositoryInstructionsRow.add(forgetRepositoryInstructionsButton);
        repositoryInstructionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        repositoryInstructionsRow.setMaximumSize(repositoryInstructionsRow.getPreferredSize());
        JPanel repositoryInstructionsPanel = new JPanel();
        repositoryInstructionsPanel.setLayout(
                new BoxLayout(repositoryInstructionsPanel, BoxLayout.Y_AXIS));
        repositoryInstructionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        repositoryInstructionsPanel.add(repositoryInstructionsRow);
        repositoryInstructionsPanel.add(boundedTextArea(repositoryInstructionsArea));
        JPanel baseUrlFieldBlock = fieldWithHint(baseUrlField, note);
        JPanel profileField =
                fieldWithHint(
                        reviewGuidanceProfilePanel,
                        hintLabel(
                                "<html><small>Save and reuse focus areas and custom"
                                        + " instructions as one named profile.</small></html>"));
        JPanel focusAreasField =
                fieldWithHint(
                        reviewFocusAreasField,
                        hintLabel(
                                "<html><small>Comma-separated areas to prioritize (for example"
                                        + " security, performance, or test coverage).</small></html>"));
        JPanel customInstructionsField =
                fieldWithHint(
                        customInstructionsScrollPane,
                        hintLabel(
                                "<html><small>Extra instructions appended to every review"
                                        + " prompt, such as team conventions.</small></html>"));
        JPanel repositoryInstructionsField =
                fieldWithHint(
                        repositoryInstructionsPanel,
                        hintLabel(
                                "<html><small>Added to every review of that repository, together"
                                        + " with the instructions above. Add a repository from a"
                                        + " review's instructions with “Remember for this"
                                        + " repository”.</small></html>"));
        JPanel rulesDirectoryField =
                fieldWithHint(
                        reviewRulesDirectoryField,
                        hintLabel(
                                "<html><small>Optional absolute path to a local folder of"
                                        + " review rules (.md, .yaml, .yml), such as a checkout of"
                                        + " your team's central review rules. Added to every"
                                        + " review's guidance.</small></html>"));
        JPanel validationField =
                fieldWithHint(
                        reviewSelfCritiqueBox,
                        hintLabel(
                                "<html><small>Re-checks each finding against the diff to improve"
                                        + " precision; roughly doubles review time.</small></html>"));
        JPanel supervisorField =
                fieldWithHint(
                        reviewSupervisorBox,
                        hintLabel(
                                "<html><small>Runs a bounded coverage check, then targeted"
                                        + " follow-ups that re-review changed files the review"
                                        + " never mentioned and its uninspected high-risk hunks."
                                        + " Only adds latency when the review left a"
                                        + " gap.</small></html>"));
        JPanel secondReviewerField =
                fieldWithHint(
                        secondReviewerModelCombo,
                        hintLabel(
                                "<html><small>Optional. Runs a second Copilot model in parallel"
                                        + " with either provider; findings are cross-validated."
                                        + " Choose Off to disable.</small></html>"));
        JPanel intellijAssistedField =
                fieldWithHint(
                        experimentalIntellijAssistedBox,
                        hintLabel(
                                "<html><small>Shows the IntelliJ-assisted option in the review"
                                        + " pane's advanced options. Requires a separate IntelliJ"
                                        + " IDEA 262+ installation, a hand-written"
                                        + " ~/.pr-pilot/semantic-review.json, and manually"
                                        + " importing a retained worktree per review. Off by"
                                        + " default; ordinary reviews are unaffected. Turning this"
                                        + " off also hides retained-worktree maintenance; turn it"
                                        + " back on to remove retained worktrees.</small></html>"));
        JPanel providerField = contentField(providerCombo);

        mainPanel =
                FormBuilder.createFormBuilder()
                        .addComponent(sectionTitle("GitHub connection"), 1)
                        .addLabeledComponent(
                                fieldLabel("Base URL:", baseUrlField), baseUrlFieldBlock, 1, false)
                        .addComponent(statusPanel, 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review provider"), 1)
                        .addLabeledComponent(
                                fieldLabel("Provider:", providerCombo), providerField, 1, false)
                        .addLabeledComponent(modelLabel, modelComboPanel, 1, false)
                        .addLabeledComponent(
                                fieldLabel("Second reviewer:", secondReviewerModelCombo),
                                secondReviewerField,
                                1,
                                false)
                        .addComponentToRightColumn(advancedCopilotSection, 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review guidance"), 1)
                        .addLabeledComponent(
                                fieldLabel("Profile:", reviewGuidanceProfileCombo),
                                profileField,
                                1,
                                false)
                        .addLabeledComponent(
                                fieldLabel("Focus areas:", reviewFocusAreasField),
                                focusAreasField,
                                1,
                                false)
                        .addLabeledComponent(
                                fieldLabel("Custom instructions:", reviewCustomInstructionsArea),
                                customInstructionsField,
                                1,
                                false)
                        .addLabeledComponent(
                                fieldLabel(
                                        "Remembered repository instructions:",
                                        repositoryInstructionsCombo),
                                repositoryInstructionsField,
                                1,
                                false)
                        .addLabeledComponent(
                                fieldLabel("Rules folder:", reviewRulesDirectoryField),
                                rulesDirectoryField,
                                1,
                                false)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review validation"), 1)
                        .addComponentToRightColumn(validationField, 1)
                        .addComponentToRightColumn(supervisorField, 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Advanced review options"), 1)
                        .addComponentToRightColumn(intellijAssistedField, 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Notifications"), 1)
                        .addComponent(notificationsEnabledBox, 1)
                        .addComponent(notifSubPanel, 1)
                        .addComponentFillVertically(new JPanel(), 0)
                        .getPanel();
        refreshPollStatus();
        refreshPollStatus();
        updateAdvancedCopilotOptionsVisibility();
    }

    private void updateNotificationSubOptions() {
        boolean on = notificationsEnabledBox.isSelected();
        if (notifSubPanel != null) notifSubPanel.setVisible(on);
    }

    public JPanel getPanel() {
        if (rootPanel == null) {
            // Hard-cap the form width so input fields don't stretch across a wide Settings dialog.
            // BoxLayout is the one standard layout that honors maximumSize, so it clamps the form
            // to
            // MAX_FORM_WIDTH even when its preferred width is larger; the trailing glue eats the
            // remaining horizontal space. Height is left unbounded so toggling advanced options can
            // still grow the panel.
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

    public boolean isCopilotInheritMcp() {
        return copilotInheritMcpBox.isSelected();
    }

    public void setCopilotInheritMcp(boolean v) {
        copilotInheritMcpBox.setSelected(v);
    }

    public String getCopilotConfigDir() {
        return copilotConfigDirField.getText().trim();
    }

    public boolean isCopilotAutoEnableMcpOnReview() {
        return copilotAutoEnableMcpOnReviewBox.isSelected();
    }

    public void setCopilotAutoEnableMcpOnReview(boolean v) {
        copilotAutoEnableMcpOnReviewBox.setSelected(v);
    }

    public String getReviewRulesDirectory() {
        return reviewRulesDirectoryField.getText().trim();
    }

    public void setReviewRulesDirectory(String dir) {
        reviewRulesDirectoryField.setText(dir != null ? dir : "");
    }

    public void setCopilotConfigDir(String dir) {
        copilotConfigDirField.setText(dir != null ? dir : "");
    }

    public String getReviewFocusAreas() {
        syncCurrentReviewGuidanceProfile();
        return defaultReviewFocusAreas;
    }

    public void setReviewFocusAreas(String value) {
        defaultReviewFocusAreas = value != null ? value.trim() : "";
        if (activeReviewGuidanceProfileId.isBlank()) {
            reviewFocusAreasField.setText(defaultReviewFocusAreas);
        }
    }

    public String getReviewCustomInstructions() {
        syncCurrentReviewGuidanceProfile();
        return defaultReviewCustomInstructions;
    }

    public void setReviewCustomInstructions(String value) {
        defaultReviewCustomInstructions = value != null ? value.trim() : "";
        if (activeReviewGuidanceProfileId.isBlank()) {
            reviewCustomInstructionsArea.setText(defaultReviewCustomInstructions);
        }
    }

    public String getReviewGuidanceGlobs() {
        syncCurrentReviewGuidanceProfile();
        return defaultReviewGuidanceGlobs;
    }

    public void setReviewGuidanceGlobs(String value) {
        defaultReviewGuidanceGlobs = value != null ? value.trim() : "";
        if (activeReviewGuidanceProfileId.isBlank()) {
            reviewGuidanceGlobsArea.setText(defaultReviewGuidanceGlobs);
        }
    }

    public List<PluginSettings.ReviewGuidanceProfile> getReviewGuidanceProfiles() {
        syncCurrentReviewGuidanceProfile();
        return reviewGuidanceProfiles.stream()
                .map(PluginSettings.ReviewGuidanceProfile::copy)
                .toList();
    }

    public void setReviewGuidanceProfiles(List<PluginSettings.ReviewGuidanceProfile> profiles) {
        reviewGuidanceProfiles =
                profiles == null
                        ? new ArrayList<>()
                        : profiles.stream()
                                .map(PluginSettings.ReviewGuidanceProfile::copy)
                                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        rebuildReviewGuidanceProfileCombo();
    }

    public String getActiveReviewGuidanceProfileId() {
        return activeReviewGuidanceProfileId;
    }

    public void setActiveReviewGuidanceProfileId(String profileId) {
        String candidate = profileId != null ? profileId.trim() : "";
        activeReviewGuidanceProfileId =
                reviewGuidanceProfiles.stream().anyMatch(profile -> profile.id.equals(candidate))
                        ? candidate
                        : "";
        loadActiveReviewGuidanceProfile();
    }

    /** Normalized remembered instructions, including any edit to the selected repository. */
    public Map<String, String> getRepositoryReviewInstructions() {
        syncSelectedRepositoryInstructions();
        return RepositoryReviewInstructions.normalize(repositoryInstructions);
    }

    public void setRepositoryReviewInstructions(Map<String, String> instructions) {
        repositoryInstructions =
                new LinkedHashMap<>(RepositoryReviewInstructions.normalize(instructions));
        selectedRepository = "";
        rebuildRepositoryInstructionsCombo();
    }

    /** The first repository whose edited instructions exceed the limit, or null when all fit. */
    public String repositoryWithOversizedInstructions() {
        syncSelectedRepositoryInstructions();
        return repositoryInstructions.entrySet().stream()
                .filter(
                        entry ->
                                entry.getValue().trim().length()
                                        > RepositoryReviewInstructions.MAX_INSTRUCTIONS_LENGTH)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void syncSelectedRepositoryInstructions() {
        if (!selectedRepository.isEmpty()
                && repositoryInstructions.containsKey(selectedRepository)) {
            repositoryInstructions.put(selectedRepository, repositoryInstructionsArea.getText());
        }
    }

    private void selectRepositoryInstructions() {
        if (updatingRepositoryInstructions) {
            return;
        }
        syncSelectedRepositoryInstructions();
        Object selected = repositoryInstructionsCombo.getSelectedItem();
        selectedRepository = selected instanceof String repository ? repository : "";
        loadSelectedRepositoryInstructions();
    }

    private void forgetSelectedRepositoryInstructions() {
        if (selectedRepository.isEmpty()) {
            return;
        }
        repositoryInstructions.remove(selectedRepository);
        selectedRepository = "";
        rebuildRepositoryInstructionsCombo();
    }

    private void rebuildRepositoryInstructionsCombo() {
        updatingRepositoryInstructions = true;
        List<String> repositories = repositoryInstructions.keySet().stream().sorted().toList();
        if (!repositories.contains(selectedRepository)) {
            selectedRepository = repositories.isEmpty() ? "" : repositories.get(0);
        }
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        if (repositories.isEmpty()) {
            model.addElement("No remembered repositories");
        }
        repositories.forEach(model::addElement);
        repositoryInstructionsCombo.setModel(model);
        repositoryInstructionsCombo.setSelectedItem(
                repositories.isEmpty() ? "No remembered repositories" : selectedRepository);
        updatingRepositoryInstructions = false;
        loadSelectedRepositoryInstructions();
    }

    private void loadSelectedRepositoryInstructions() {
        boolean hasSelection = !selectedRepository.isEmpty();
        repositoryInstructionsCombo.setEnabled(hasSelection);
        repositoryInstructionsArea.setEnabled(hasSelection);
        forgetRepositoryInstructionsButton.setEnabled(hasSelection);
        repositoryInstructionsArea.setText(
                hasSelection ? repositoryInstructions.getOrDefault(selectedRepository, "") : "");
    }

    public boolean isReviewSelfCritique() {
        return reviewSelfCritiqueBox.isSelected();
    }

    public void setReviewSelfCritique(boolean v) {
        reviewSelfCritiqueBox.setSelected(v);
    }

    public String getReviewSecondReviewerModel() {
        Object editorValue = secondReviewerModelCombo.getEditor().getItem();
        return editorValue != null ? editorValue.toString().trim() : "";
    }

    public void setReviewSecondReviewerModel(String value) {
        String id = StringUtils.trimToEmpty(value);
        secondReviewerModelCombo.setSelectedItem(id);
        secondReviewerModelCombo.getEditor().setItem(id);
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

    private static JBLabel sectionTitle(String text) {
        JBLabel label = new JBLabel("<html><b>" + text + "</b></html>");
        label.setBorder(JBUI.Borders.emptyTop(8));
        return label;
    }

    private void selectReviewGuidanceProfile() {
        if (updatingReviewGuidanceProfile) {
            return;
        }
        syncCurrentReviewGuidanceProfile();
        Object selected = reviewGuidanceProfileCombo.getSelectedItem();
        activeReviewGuidanceProfileId =
                selected instanceof PluginSettings.ReviewGuidanceProfile profile ? profile.id : "";
        loadActiveReviewGuidanceProfile();
    }

    private void addReviewGuidanceProfile() {
        syncCurrentReviewGuidanceProfile();
        String name = promptForProfileName("Save guidance profile", "");
        if (name == null) {
            return;
        }
        PluginSettings.ReviewGuidanceProfile profile =
                new PluginSettings.ReviewGuidanceProfile(
                        UUID.randomUUID().toString(),
                        name,
                        reviewFocusAreasField.getText(),
                        reviewCustomInstructionsArea.getText(),
                        reviewGuidanceGlobsArea.getText());
        reviewGuidanceProfiles.add(profile);
        activeReviewGuidanceProfileId = profile.id;
        rebuildReviewGuidanceProfileCombo();
    }

    private void renameReviewGuidanceProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveReviewGuidanceProfile();
        if (active == null) {
            return;
        }
        String name = promptForProfileName("Rename guidance profile", active.name);
        if (name == null) {
            return;
        }
        active.name = name;
        rebuildReviewGuidanceProfileCombo();
    }

    private String promptForProfileName(String title, String initialValue) {
        String candidate = initialValue;
        String validationMessage = null;
        while (true) {
            candidate = profileNamePrompt.prompt(mainPanel, title, candidate, validationMessage);
            if (candidate == null) {
                return null;
            }
            validationMessage = profileNameError(candidate);
            if (validationMessage == null) {
                return candidate.trim();
            }
        }
    }

    static String profileNameError(String value) {
        return value == null || value.trim().isEmpty() ? PROFILE_NAME_REQUIRED : null;
    }

    private static String showProfileNamePrompt(
            Component parent, String title, String initialValue, String validationMessage) {
        String message =
                validationMessage == null
                        ? "Profile name:"
                        : validationMessage + System.lineSeparator() + "Profile name:";
        return (String)
                JOptionPane.showInputDialog(
                        parent,
                        message,
                        title,
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        null,
                        initialValue);
    }

    private void deleteReviewGuidanceProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveReviewGuidanceProfile();
        if (active == null) {
            return;
        }
        int answer =
                JOptionPane.showConfirmDialog(
                        mainPanel,
                        "Delete guidance profile \"" + active.name + "\"?",
                        "Delete guidance profile",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        reviewGuidanceProfiles.remove(active);
        activeReviewGuidanceProfileId = "";
        rebuildReviewGuidanceProfileCombo();
        loadActiveReviewGuidanceProfile();
    }

    private void syncCurrentReviewGuidanceProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveReviewGuidanceProfile();
        if (active == null) {
            defaultReviewFocusAreas = reviewFocusAreasField.getText().trim();
            defaultReviewCustomInstructions = reviewCustomInstructionsArea.getText().trim();
            defaultReviewGuidanceGlobs = reviewGuidanceGlobsArea.getText().trim();
            return;
        }
        active.focusAreas = reviewFocusAreasField.getText().trim();
        active.customInstructions = reviewCustomInstructionsArea.getText().trim();
        active.guidanceGlobs = reviewGuidanceGlobsArea.getText().trim();
    }

    private void loadActiveReviewGuidanceProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveReviewGuidanceProfile();
        reviewFocusAreasField.setText(active != null ? active.focusAreas : defaultReviewFocusAreas);
        reviewCustomInstructionsArea.setText(
                active != null ? active.customInstructions : defaultReviewCustomInstructions);
        reviewGuidanceGlobsArea.setText(
                active != null ? active.guidanceGlobs : defaultReviewGuidanceGlobs);
        renameReviewGuidanceProfileButton.setEnabled(active != null);
        deleteReviewGuidanceProfileButton.setEnabled(active != null);
    }

    private PluginSettings.ReviewGuidanceProfile findActiveReviewGuidanceProfile() {
        return reviewGuidanceProfiles.stream()
                .filter(profile -> profile.id.equals(activeReviewGuidanceProfileId))
                .findFirst()
                .orElse(null);
    }

    private void rebuildReviewGuidanceProfileCombo() {
        updatingReviewGuidanceProfile = true;
        DefaultComboBoxModel<PluginSettings.ReviewGuidanceProfile> model =
                new DefaultComboBoxModel<>();
        model.addElement(null);
        reviewGuidanceProfiles.forEach(model::addElement);
        reviewGuidanceProfileCombo.setModel(model);
        PluginSettings.ReviewGuidanceProfile active = findActiveReviewGuidanceProfile();
        reviewGuidanceProfileCombo.setSelectedItem(active);
        updatingReviewGuidanceProfile = false;
        loadActiveReviewGuidanceProfile();
    }

    private static void boundContentWidth(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        java.awt.Dimension size =
                new java.awt.Dimension(
                        JBUI.scale(CONTENT_WIDTH), component.getPreferredSize().height);
        component.setPreferredSize(size);
        component.setMinimumSize(size);
        component.setMaximumSize(size);
    }

    private static JBScrollPane boundedTextArea(JBTextArea textArea) {
        JBScrollPane scrollPane =
                new JBScrollPane(
                        textArea,
                        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        java.awt.Dimension viewportSize = textArea.getPreferredScrollableViewportSize();
        scrollPane
                .getViewport()
                .setPreferredSize(
                        new java.awt.Dimension(JBUI.scale(CONTENT_WIDTH), viewportSize.height));
        boundContentWidth(scrollPane);
        return scrollPane;
    }

    private static JPanel fieldWithHint(JComponent control, JLabel hint) {
        JPanel field = contentField(control);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setFocusable(false);
        field.add(hint);
        return field;
    }

    private static JPanel contentField(JComponent control) {
        control.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel field = new JPanel();
        field.setLayout(new BoxLayout(field, BoxLayout.Y_AXIS));
        field.setFocusable(false);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.add(control);
        return field;
    }

    private static JBLabel fieldLabel(String text, JComponent control) {
        JBLabel label = new JBLabel(text);
        label.setLabelFor(control);
        return label;
    }

    private static JBLabel hintLabel(String html) {
        JBLabel label = new JBLabel(boundedHintHtml(html));
        label.setBorder(JBUI.Borders.emptyTop(2));
        label.setFocusable(false);
        return label;
    }

    private static String boundedHintHtml(String html) {
        // Constrain hint width so long hints wrap instead of widening the whole settings panel
        // (an unbounded single-line HTML label reports a huge preferred width, which forces a
        // horizontal scrollbar and pushes full-width fields like the model dropdown off-screen).
        String inner = html;
        if (inner.startsWith("<html>")) {
            inner = inner.substring("<html>".length());
        }
        if (inner.endsWith("</html>")) {
            inner = inner.substring(0, inner.length() - "</html>".length());
        }
        return "<html><div style='width:" + JBUI.scale(480) + "px'>" + inner + "</div></html>";
    }

    private void updateActiveModelCombo() {
        ReviewProvider active = getReviewProvider();
        ((java.awt.CardLayout) modelComboPanel.getLayout()).show(modelComboPanel, active.getId());
        modelLabel.setLabelFor(
                active == ReviewProvider.COPILOT ? copilotModelCombo : claudeModelCombo);
        updateAdvancedCopilotOptionsVisibility();
    }

    private void updateAdvancedCopilotOptionsVisibility() {
        boolean copilotProvider = getReviewProvider() == ReviewProvider.COPILOT;
        boolean showAdvanced = showAdvancedCopilotBox.isSelected();
        advancedCopilotSection.setVisible(copilotProvider);
        advancedCopilotPanel.setVisible(copilotProvider && showAdvanced);
        copilotEffortCombo.setEnabled(copilotProvider);
        copilotInheritMcpBox.setEnabled(copilotProvider);
        copilotAutoEnableMcpOnReviewBox.setEnabled(copilotProvider);
        copilotConfigDirField.setEnabled(copilotProvider);
    }

    /**
     * Re-probes the Copilot model catalog off the EDT. The dropdown keeps its current entries while
     * loading, and the button stays disabled so refreshes never overlap.
     */
    private void refreshCopilotModels() {
        refreshModelsButton.setEnabled(false);
        refreshModelsButton.setText("Refreshing…");
        if (copilotModelCatalog.cached() == null) {
            copilotModelHint.setText(boundedHintHtml(hintHtml(MODELS_LOADING_HINT)));
        }
        backgroundExecutor.accept(
                () -> {
                    CopilotModelDiscovery.Result result = copilotModelCatalog.refresh();
                    uiExecutor.accept(
                            () -> {
                                refreshModelsButton.setEnabled(true);
                                refreshModelsButton.setText("Refresh");
                                applyCopilotModelResult(result);
                            });
                });
    }

    private void applyCopilotModelResult(CopilotModelDiscovery.Result result) {
        if (!result.models().isEmpty()) mergeCopilotModelOptions(result.models());
        boolean hasPreviousList = copilotModelCatalog.cached() != null;
        copilotModelHint.setText(
                boundedHintHtml(hintHtml(copilotModelStatus(result, hasPreviousList))));
    }

    /** User-facing status for a discovery result. Package-private for tests. */
    static String copilotModelStatus(CopilotModelDiscovery.Result result, boolean hasPreviousList) {
        String reason =
                result.failureReason().isBlank()
                        ? ""
                        : " (" + StringEscapeUtils.escapeHtml4(result.failureReason()) + ")";
        return switch (result.source()) {
            case ACCOUNT ->
                    result.models().size()
                            + " models available to your Copilot account."
                            + " Type any model ID to override.";
            case CLI_HELP ->
                    "Couldn't load your account's models"
                            + reason
                            + ". Showing the Copilot CLI's built-in list, which may miss newer"
                            + " models.";
            case NONE ->
                    hasPreviousList
                            ? "Couldn't refresh models" + reason + ". Showing the last loaded list."
                            : "Couldn't load models"
                                    + reason
                                    + ". Showing suggestions; type any model ID to override.";
        };
    }

    private static String hintHtml(String text) {
        return "<html><small>" + text + "</small></html>";
    }

    List<String> getCopilotModelOptions() {
        return comboItems(copilotModelCombo);
    }

    List<String> getSecondReviewerModelOptions() {
        return comboItems(secondReviewerModelCombo);
    }

    private static List<String> comboItems(JComboBox<String> combo) {
        List<String> options = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            options.add(combo.getItemAt(i));
        }
        return options;
    }

    String getCopilotModelHintText() {
        return copilotModelHint.getText();
    }

    JButton getRefreshModelsButton() {
        return refreshModelsButton;
    }

    /**
     * Replaces the Copilot model dropdown entries with `[""] + discovered`, preserving the user's
     * current editor text. Called on the EDT from the discovery probe callback. Always keeps the
     * empty-string entry first (it represents "CLI default routing").
     */
    private void mergeCopilotModelOptions(List<String> discovered) {
        mergeModelOptions(copilotModelCombo, discovered);
        mergeModelOptions(secondReviewerModelCombo, discovered);
    }

    private static void mergeModelOptions(JComboBox<String> combo, List<String> discovered) {
        Object currentEditorValue = combo.getEditor().getItem();
        String currentText = currentEditorValue != null ? currentEditorValue.toString() : "";

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add("");
        merged.addAll(discovered);
        // Preserve any user-typed model that isn't in the discovered list so we don't lose it.
        if (!currentText.isBlank() && !merged.contains(currentText)) merged.add(currentText);

        List<String> ordered = new ArrayList<>(merged);
        combo.setModel(new DefaultComboBoxModel<>(ordered.toArray(new String[0])));
        combo.setSelectedItem(currentText);
        combo.getEditor().setItem(currentText);
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
}
