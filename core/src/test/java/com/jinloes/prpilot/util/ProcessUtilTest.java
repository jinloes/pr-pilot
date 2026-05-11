package com.jinloes.prpilot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessUtilTest {

    @Nested
    class FindBinary {

        @Test
        void firstExistingCandidate_isReturned(@TempDir Path dir) throws IOException {
            Path bin = dir.resolve("mybinary");
            Files.createFile(bin);
            assertThat(ProcessUtil.findBinary("fallback", List.of(bin.toString())))
                    .isEqualTo(bin.toString());
        }

        @Test
        void skipsNonExistentPaths_returnsFirstExisting(@TempDir Path dir) throws IOException {
            Path bin = dir.resolve("real");
            Files.createFile(bin);
            assertThat(
                            ProcessUtil.findBinary(
                                    "fallback",
                                    List.of("/no/such/path", bin.toString(), "/also/missing")))
                    .isEqualTo(bin.toString());
        }

        @Test
        void noCandidateExists_returnsFallbackName() {
            assertThat(
                            ProcessUtil.findBinary(
                                    "mybinary", List.of("/no/such/path", "/another/missing")))
                    .isEqualTo("mybinary");
        }

        @Test
        void emptyCandidateList_returnsFallbackName() {
            assertThat(ProcessUtil.findBinary("mybinary", List.of())).isEqualTo("mybinary");
        }
    }
}
