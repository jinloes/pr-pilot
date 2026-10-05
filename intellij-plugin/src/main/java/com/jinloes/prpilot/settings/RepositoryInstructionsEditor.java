package com.jinloes.prpilot.settings;

import com.intellij.ui.components.JBTextArea;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;

/** Edits remembered per-repository review instructions. */
final class RepositoryInstructionsEditor {

    private final JComboBox<String> repositoryInstructionsCombo = new JComboBox<>();
    private final JBTextArea repositoryInstructionsArea = new JBTextArea(3, 0);
    private final JButton forgetRepositoryInstructionsButton = new JButton("Forget");
    private Map<String, String> repositoryInstructions = new LinkedHashMap<>();
    private String selectedRepository = "";
    private boolean updatingRepositoryInstructions;

    RepositoryInstructionsEditor() {
        SettingsUi.boundContentWidth(repositoryInstructionsCombo);
        repositoryInstructionsArea
                .getAccessibleContext()
                .setAccessibleName("Instructions for the selected repository");
        repositoryInstructionsCombo.addActionListener(e -> selectRepositoryInstructions());
        forgetRepositoryInstructionsButton.addActionListener(
                e -> forgetSelectedRepositoryInstructions());
        rebuildRepositoryInstructionsCombo();
    }

    JPanel control() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(repositoryInstructionsCombo);
        row.add(forgetRepositoryInstructionsButton);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(row.getPreferredSize());
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(row);
        panel.add(SettingsUi.boundedTextArea(repositoryInstructionsArea));
        return panel;
    }

    Map<String, String> get() {
        syncSelectedRepositoryInstructions();
        return RepositoryReviewInstructions.normalize(repositoryInstructions);
    }

    void set(Map<String, String> instructions) {
        repositoryInstructions =
                new LinkedHashMap<>(RepositoryReviewInstructions.normalize(instructions));
        selectedRepository = "";
        rebuildRepositoryInstructionsCombo();
    }

    String oversizedRepository() {
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

    JComboBox<String> combo() {
        return repositoryInstructionsCombo;
    }
}
