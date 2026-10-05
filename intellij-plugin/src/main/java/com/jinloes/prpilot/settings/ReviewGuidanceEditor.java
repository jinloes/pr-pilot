package com.jinloes.prpilot.settings;

import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/** Owns reusable guidance profiles and their focus/custom-instruction controls. */
final class ReviewGuidanceEditor {

    static final String PROFILE_NAME_REQUIRED = "Enter a profile name.";

    private final JBTextField reviewFocusAreasField = new JBTextField();
    private final JBTextArea reviewCustomInstructionsArea = new JBTextArea(4, 0);
    private final JBTextArea reviewGuidanceGlobsArea = new JBTextArea(3, 0);
    private final JComboBox<PluginSettings.ReviewGuidanceProfile> profileCombo = new JComboBox<>();
    private final JButton addProfileButton = new JButton("Save as…");
    private final JButton renameProfileButton = new JButton("Rename");
    private final JButton deleteProfileButton = new JButton("Delete");
    private final PluginSettingsComponent.ProfileNamePrompt profileNamePrompt;
    private final Supplier<Component> parentSupplier;
    private final JPanel profileControl;
    private List<PluginSettings.ReviewGuidanceProfile> profiles = new ArrayList<>();
    private String activeProfileId = "";
    private String defaultFocusAreas = "";
    private String defaultCustomInstructions = "";
    private String defaultGuidanceGlobs = "";
    private boolean updatingProfile;

    ReviewGuidanceEditor(
            PluginSettingsComponent.ProfileNamePrompt profileNamePrompt,
            Supplier<Component> parentSupplier) {
        this.profileNamePrompt = profileNamePrompt;
        this.parentSupplier = parentSupplier;
        configureControls();
        profileControl = createProfileControl();
        rebuildProfileCombo();
    }

    private void configureControls() {
        SettingsUi.boundContentWidth(reviewFocusAreasField);
        SettingsUi.boundContentWidth(profileCombo);
        profileCombo.setRenderer(
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
        profileCombo.addActionListener(e -> selectProfile());
        addProfileButton.addActionListener(e -> addProfile());
        renameProfileButton.addActionListener(e -> renameProfile());
        deleteProfileButton.addActionListener(e -> deleteProfile());
    }

    private JPanel createProfileControl() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(addProfileButton);
        actions.add(renameProfileButton);
        actions.add(deleteProfileButton);
        actions.setFocusable(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setMaximumSize(actions.getPreferredSize());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setFocusable(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(profileCombo);
        panel.add(actions);
        return panel;
    }

    private void selectProfile() {
        if (updatingProfile) {
            return;
        }
        syncCurrentProfile();
        Object selected = profileCombo.getSelectedItem();
        activeProfileId =
                selected instanceof PluginSettings.ReviewGuidanceProfile profile ? profile.id : "";
        loadActiveProfile();
    }

    private void addProfile() {
        syncCurrentProfile();
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
        profiles.add(profile);
        activeProfileId = profile.id;
        rebuildProfileCombo();
    }

    private void renameProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveProfile();
        if (active == null) {
            return;
        }
        String name = promptForProfileName("Rename guidance profile", active.name);
        if (name == null) {
            return;
        }
        active.name = name;
        rebuildProfileCombo();
    }

    private String promptForProfileName(String title, String initialValue) {
        String candidate = initialValue;
        String validationMessage = null;
        while (true) {
            candidate =
                    profileNamePrompt.prompt(
                            parentSupplier.get(), title, candidate, validationMessage);
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

    private void deleteProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveProfile();
        if (active == null) {
            return;
        }
        int answer =
                JOptionPane.showConfirmDialog(
                        parentSupplier.get(),
                        "Delete guidance profile \"" + active.name + "\"?",
                        "Delete guidance profile",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        profiles.remove(active);
        activeProfileId = "";
        rebuildProfileCombo();
        loadActiveProfile();
    }

    private void syncCurrentProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveProfile();
        if (active == null) {
            defaultFocusAreas = reviewFocusAreasField.getText().trim();
            defaultCustomInstructions = reviewCustomInstructionsArea.getText().trim();
            defaultGuidanceGlobs = reviewGuidanceGlobsArea.getText().trim();
            return;
        }
        active.focusAreas = reviewFocusAreasField.getText().trim();
        active.customInstructions = reviewCustomInstructionsArea.getText().trim();
        active.guidanceGlobs = reviewGuidanceGlobsArea.getText().trim();
    }

    private void loadActiveProfile() {
        PluginSettings.ReviewGuidanceProfile active = findActiveProfile();
        reviewFocusAreasField.setText(active != null ? active.focusAreas : defaultFocusAreas);
        reviewCustomInstructionsArea.setText(
                active != null ? active.customInstructions : defaultCustomInstructions);
        reviewGuidanceGlobsArea.setText(
                active != null ? active.guidanceGlobs : defaultGuidanceGlobs);
        renameProfileButton.setEnabled(active != null);
        deleteProfileButton.setEnabled(active != null);
    }

    private PluginSettings.ReviewGuidanceProfile findActiveProfile() {
        return profiles.stream()
                .filter(profile -> profile.id.equals(activeProfileId))
                .findFirst()
                .orElse(null);
    }

    private void rebuildProfileCombo() {
        updatingProfile = true;
        DefaultComboBoxModel<PluginSettings.ReviewGuidanceProfile> model =
                new DefaultComboBoxModel<>();
        model.addElement(null);
        profiles.forEach(model::addElement);
        profileCombo.setModel(model);
        profileCombo.setSelectedItem(findActiveProfile());
        updatingProfile = false;
        loadActiveProfile();
    }

    JPanel profileControl() {
        return profileControl;
    }

    JComboBox<PluginSettings.ReviewGuidanceProfile> profileCombo() {
        return profileCombo;
    }

    JBTextField focusAreasField() {
        return reviewFocusAreasField;
    }

    JBTextArea customInstructionsArea() {
        return reviewCustomInstructionsArea;
    }

    JBTextArea guidanceGlobsArea() {
        return reviewGuidanceGlobsArea;
    }

    List<PluginSettings.ReviewGuidanceProfile> getProfiles() {
        syncCurrentProfile();
        return profiles.stream().map(PluginSettings.ReviewGuidanceProfile::copy).toList();
    }

    void setProfiles(List<PluginSettings.ReviewGuidanceProfile> values) {
        profiles =
                values == null
                        ? new ArrayList<>()
                        : values.stream()
                                .map(PluginSettings.ReviewGuidanceProfile::copy)
                                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        rebuildProfileCombo();
    }

    String getActiveProfileId() {
        return activeProfileId;
    }

    void setActiveProfileId(String profileId) {
        String candidate = profileId != null ? profileId.trim() : "";
        activeProfileId =
                profiles.stream().anyMatch(profile -> profile.id.equals(candidate))
                        ? candidate
                        : "";
        loadActiveProfile();
    }

    String getFocusAreas() {
        syncCurrentProfile();
        return defaultFocusAreas;
    }

    void setFocusAreas(String value) {
        defaultFocusAreas = value != null ? value.trim() : "";
        if (activeProfileId.isBlank()) {
            reviewFocusAreasField.setText(defaultFocusAreas);
        }
    }

    String getCustomInstructions() {
        syncCurrentProfile();
        return defaultCustomInstructions;
    }

    void setCustomInstructions(String value) {
        defaultCustomInstructions = value != null ? value.trim() : "";
        if (activeProfileId.isBlank()) {
            reviewCustomInstructionsArea.setText(defaultCustomInstructions);
        }
    }

    String getGuidanceGlobs() {
        syncCurrentProfile();
        return defaultGuidanceGlobs;
    }

    void setGuidanceGlobs(String value) {
        defaultGuidanceGlobs = value != null ? value.trim() : "";
        if (activeProfileId.isBlank()) {
            reviewGuidanceGlobsArea.setText(defaultGuidanceGlobs);
        }
    }
}
