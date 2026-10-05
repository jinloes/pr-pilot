package com.jinloes.prpilot.settings;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

/** Owns provider selection, model selection, and Copilot-only review options. */
final class CopilotModelSelector {

    private record ModelOption(String label, String id) {}

    static final String MODELS_LOADING_HINT = "Loading models available to your Copilot account…";

    private static final List<ModelOption> CLAUDE_MODELS =
            List.of(
                    new ModelOption("CLI default (unset)", ""),
                    new ModelOption("Haiku — fastest", "claude-haiku-4-5-20251001"),
                    new ModelOption("Sonnet — balanced", "claude-sonnet-4-6"),
                    new ModelOption("Opus — most thorough", "claude-opus-4-7"));

    private static final String[] COPILOT_MODEL_SUGGESTIONS = {
        "", "claude-sonnet-4.6", "claude-opus-4.7", "claude-opus-4.8", "gpt-5.5", "gpt-5.4",
    };

    private static final String[] COPILOT_EFFORTS = {
        "none", "low", "medium", "high", "xhigh", "max"
    };

    private final JComboBox<String> claudeModelCombo =
            new JComboBox<>(CLAUDE_MODELS.stream().map(ModelOption::label).toArray(String[]::new));
    private final JComboBox<String> copilotModelCombo = new JComboBox<>(COPILOT_MODEL_SUGGESTIONS);
    private final JComboBox<String> copilotEffortCombo = new JComboBox<>(COPILOT_EFFORTS);
    private final JCheckBox copilotInheritMcpBox =
            new JCheckBox("Allow Copilot to use MCP tools from your trusted Copilot config");
    private final JCheckBox copilotAutoEnableMcpOnReviewBox =
            new JCheckBox("Always enable MCP for Copilot reviews");
    private final JBTextField copilotConfigDirField = new JBTextField();
    private final JComboBox<String> secondReviewerModelCombo =
            new JComboBox<>(COPILOT_MODEL_SUGGESTIONS);
    private final JComboBox<ReviewProvider> providerCombo =
            new JComboBox<>(ReviewProvider.values());
    private final JBLabel modelLabel = new JBLabel("Model:");
    private final JPanel modelComboPanel = new JPanel(new java.awt.CardLayout());
    private final JPanel copilotModelCard = new JPanel();
    private final JCheckBox showAdvancedCopilotBox = new JCheckBox("Show advanced Copilot options");
    private final JPanel advancedCopilotSection = new JPanel();
    private final JPanel advancedCopilotPanel = new JPanel();
    private final PluginSettingsComponent.CopilotModelCatalog catalog;
    private final Consumer<Runnable> backgroundExecutor;
    private final Consumer<Runnable> uiExecutor;
    private final JButton refreshModelsButton = new JButton("Refresh");
    private final JBLabel copilotModelHint = SettingsUi.hintLabel(MODELS_LOADING_HINT);

    CopilotModelSelector(
            PluginSettingsComponent.CopilotModelCatalog catalog,
            Consumer<Runnable> backgroundExecutor,
            Consumer<Runnable> uiExecutor) {
        this.catalog = catalog;
        this.backgroundExecutor = backgroundExecutor;
        this.uiExecutor = uiExecutor;

        configureControls();
        configureModelCards();
        configureAdvancedOptions();

        CopilotModelDiscovery.Result cachedModels = catalog.cached();
        if (cachedModels != null) {
            applyCopilotModelResult(cachedModels);
        }
        refreshCopilotModels();
    }

    private void configureControls() {
        providerCombo.setRenderer(
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
                        if (value instanceof ReviewProvider p) {
                            setText(p.getDisplayName());
                        }
                        return this;
                    }
                });
        providerCombo.setPrototypeDisplayValue(ReviewProvider.COPILOT);
        SettingsUi.boundContentWidth(providerCombo);

        copilotModelCombo.setEditable(true);
        SettingsUi.boundContentWidth(copilotModelCombo);
        SettingsUi.boundContentWidth(claudeModelCombo);
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
                        if (value == null || value.toString().isBlank()) {
                            setText("Off");
                        }
                        return this;
                    }
                });
        SettingsUi.boundContentWidth(secondReviewerModelCombo);
        copilotModelHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshModelsButton.setToolTipText("Reload the models available to your Copilot account");
        refreshModelsButton.addActionListener(e -> refreshCopilotModels());
    }

    private void configureModelCards() {
        JPanel copilotModelRow = new JPanel();
        copilotModelRow.setLayout(new BoxLayout(copilotModelRow, BoxLayout.X_AXIS));
        copilotModelRow.setFocusable(false);
        copilotModelRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        copilotModelRow.add(copilotModelCombo);
        copilotModelRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        copilotModelRow.add(refreshModelsButton);
        copilotModelRow.setMaximumSize(copilotModelRow.getPreferredSize());

        copilotModelCard.setLayout(new BoxLayout(copilotModelCard, BoxLayout.Y_AXIS));
        copilotModelCard.setFocusable(false);
        copilotModelCard.add(copilotModelRow);
        copilotModelCard.add(copilotModelHint);

        JPanel claudeModelCard = new JPanel();
        claudeModelCard.setLayout(new BoxLayout(claudeModelCard, BoxLayout.Y_AXIS));
        claudeModelCard.setFocusable(false);
        claudeModelCard.add(claudeModelCombo);

        modelComboPanel.add(claudeModelCard, ReviewProvider.CLAUDE.getId());
        modelComboPanel.add(copilotModelCard, ReviewProvider.COPILOT.getId());
        modelLabel.setLabelFor(claudeModelCombo);
        providerCombo.addActionListener(e -> updateActiveModelCombo());
    }

    private void configureAdvancedOptions() {
        JLabel effortHint =
                SettingsUi.hintLabel(
                        "<html><small>Higher effort = deeper review, slower."
                                + " Applies only to GitHub Copilot.</small></html>");
        JPanel effortField = SettingsUi.fieldWithHint(copilotEffortCombo, effortHint);

        JLabel mcpHint =
                SettingsUi.hintLabel(
                        "<html><small>Applies while reviewing untrusted pull request content."
                                + " Servers load only from your own Copilot config"
                                + " (<code>~/.copilot/mcp-config.json</code>); a pull request's"
                                + " <code>.mcp.json</code> is never loaded.</small></html>");
        JLabel advancedHint =
                SettingsUi.hintLabel(
                        "<html><small>Optional controls for reasoning depth, MCP access, and"
                                + " Copilot config discovery.</small></html>");
        advancedHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        showAdvancedCopilotBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        showAdvancedCopilotBox.addActionListener(e -> updateAdvancedCopilotOptionsVisibility());

        JPanel advancedFormPanel =
                FormBuilder.createFormBuilder()
                        .addLabeledComponent(
                                SettingsUi.fieldLabel("Reasoning effort:", copilotEffortCombo),
                                effortField,
                                1,
                                false)
                        .addComponentToRightColumn(
                                SettingsUi.fieldWithHint(copilotInheritMcpBox, mcpHint), 1)
                        .addComponentToRightColumn(copilotAutoEnableMcpOnReviewBox, 1)
                        .addLabeledComponent(
                                SettingsUi.fieldLabel("Copilot config dir:", copilotConfigDirField),
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
        advancedCopilotSection.add(SettingsUi.fieldWithHint(showAdvancedCopilotBox, advancedHint));
        advancedCopilotSection.add(advancedCopilotPanel);
        updateActiveModelCombo();
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

    private void refreshCopilotModels() {
        refreshModelsButton.setEnabled(false);
        refreshModelsButton.setText("Refreshing…");
        if (catalog.cached() == null) {
            copilotModelHint.setText(SettingsUi.boundedHintHtml(MODELS_LOADING_HINT));
        }
        backgroundExecutor.accept(
                () -> {
                    CopilotModelDiscovery.Result result = catalog.refresh();
                    uiExecutor.accept(
                            () -> {
                                refreshModelsButton.setEnabled(true);
                                refreshModelsButton.setText("Refresh");
                                applyCopilotModelResult(result);
                            });
                });
    }

    private void applyCopilotModelResult(CopilotModelDiscovery.Result result) {
        if (!result.models().isEmpty()) {
            mergeCopilotModelOptions(result.models());
        }
        boolean hasPreviousList = catalog.cached() != null;
        copilotModelHint.setText(
                SettingsUi.boundedHintHtml(copilotModelStatus(result, hasPreviousList)));
    }

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
        if (!currentText.isBlank() && !merged.contains(currentText)) {
            merged.add(currentText);
        }
        List<String> ordered = new ArrayList<>(merged);
        combo.setModel(new DefaultComboBoxModel<>(ordered.toArray(new String[0])));
        combo.setSelectedItem(currentText);
        combo.getEditor().setItem(currentText);
    }

    private static List<String> comboItems(JComboBox<String> combo) {
        List<String> options = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            options.add(combo.getItemAt(i));
        }
        return options;
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

    JComboBox<ReviewProvider> providerCombo() {
        return providerCombo;
    }

    JBLabel modelLabel() {
        return modelLabel;
    }

    JPanel modelComboPanel() {
        return modelComboPanel;
    }

    JPanel advancedSection() {
        return advancedCopilotSection;
    }

    JComboBox<String> secondReviewerModelCombo() {
        return secondReviewerModelCombo;
    }

    String getReviewModel() {
        return selectedId(claudeModelCombo, CLAUDE_MODELS);
    }

    void setReviewModel(String modelId) {
        selectId(claudeModelCombo, CLAUDE_MODELS, modelId);
    }

    String getReviewModelCopilot() {
        Object editorValue = copilotModelCombo.getEditor().getItem();
        return editorValue != null ? editorValue.toString().trim() : "";
    }

    void setReviewModelCopilot(String modelId) {
        String id = StringUtils.trimToEmpty(modelId);
        copilotModelCombo.setSelectedItem(id);
        copilotModelCombo.getEditor().setItem(id);
    }

    ReviewProvider getReviewProvider() {
        Object selected = providerCombo.getSelectedItem();
        return selected instanceof ReviewProvider p ? p : ReviewProvider.CLAUDE;
    }

    void setReviewProvider(ReviewProvider provider) {
        providerCombo.setSelectedItem(provider != null ? provider : ReviewProvider.CLAUDE);
        updateActiveModelCombo();
    }

    String getReviewEffort() {
        Object selected = copilotEffortCombo.getSelectedItem();
        return selected instanceof String s && !s.isBlank() ? s : "medium";
    }

    void setReviewEffort(String effort) {
        String value = effort != null && !effort.isBlank() ? effort : "medium";
        copilotEffortCombo.setSelectedItem(value);
    }

    boolean isCopilotInheritMcp() {
        return copilotInheritMcpBox.isSelected();
    }

    void setCopilotInheritMcp(boolean value) {
        copilotInheritMcpBox.setSelected(value);
    }

    String getCopilotConfigDir() {
        return copilotConfigDirField.getText().trim();
    }

    void setCopilotConfigDir(String value) {
        copilotConfigDirField.setText(value != null ? value : "");
    }

    boolean isCopilotAutoEnableMcpOnReview() {
        return copilotAutoEnableMcpOnReviewBox.isSelected();
    }

    void setCopilotAutoEnableMcpOnReview(boolean value) {
        copilotAutoEnableMcpOnReviewBox.setSelected(value);
    }

    String getReviewSecondReviewerModel() {
        Object editorValue = secondReviewerModelCombo.getEditor().getItem();
        return editorValue != null ? editorValue.toString().trim() : "";
    }

    void setReviewSecondReviewerModel(String value) {
        String id = StringUtils.trimToEmpty(value);
        secondReviewerModelCombo.setSelectedItem(id);
        secondReviewerModelCombo.getEditor().setItem(id);
    }

    List<String> getCopilotModelOptions() {
        return comboItems(copilotModelCombo);
    }

    List<String> getSecondReviewerModelOptions() {
        return comboItems(secondReviewerModelCombo);
    }

    String getCopilotModelHintText() {
        return copilotModelHint.getText();
    }

    JButton getRefreshModelsButton() {
        return refreshModelsButton;
    }
}
