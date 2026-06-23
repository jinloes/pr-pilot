package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.util.UserDataHolderBase;
import org.junit.jupiter.api.Test;

class PRPilotEditorOpenerTest {

    @Test
    void getOrCreateVirtualFile_reusesSameInstanceForHolder() {
        UserDataHolderBase holder = new UserDataHolderBase();

        PRPilotVirtualFile first = PRPilotEditorOpener.getOrCreateVirtualFile(holder);
        PRPilotVirtualFile second = PRPilotEditorOpener.getOrCreateVirtualFile(holder);

        assertThat(second).isSameAs(first);
    }

    @Test
    void getOrCreateVirtualFile_createsReadonlyPrPilotTabFile() {
        UserDataHolderBase holder = new UserDataHolderBase();

        PRPilotVirtualFile file = PRPilotEditorOpener.getOrCreateVirtualFile(holder);

        assertThat(file.getName()).isEqualTo(PRPilotVirtualFile.TAB_TITLE);
        assertThat(file.isWritable()).isFalse();
    }
}

