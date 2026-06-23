package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.util.UserDataHolderBase;
import org.junit.jupiter.api.Test;

class PRPilotFileEditorProviderTest {

    @Test
    void tryMarkPrimaryEditorOpen_marksHolderOnce() {
        UserDataHolderBase holder = new UserDataHolderBase();

        boolean first = PRPilotFileEditorProvider.tryMarkPrimaryEditorOpen(holder);
        boolean second = PRPilotFileEditorProvider.tryMarkPrimaryEditorOpen(holder);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(PRPilotFileEditorProvider.isPrimaryEditorOpen(holder)).isTrue();
    }

    @Test
    void clearPrimaryEditorOpen_resetsOpenFlag() {
        UserDataHolderBase holder = new UserDataHolderBase();
        PRPilotFileEditorProvider.tryMarkPrimaryEditorOpen(holder);

        PRPilotFileEditorProvider.clearPrimaryEditorOpen(holder);

        assertThat(PRPilotFileEditorProvider.isPrimaryEditorOpen(holder)).isFalse();
        assertThat(PRPilotFileEditorProvider.tryMarkPrimaryEditorOpen(holder)).isTrue();
    }
}

