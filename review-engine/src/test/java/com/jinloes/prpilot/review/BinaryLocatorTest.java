package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryLocatorTest {

    @Nested
    class ProviderPath {

        @Test
        void includesGuiToolAndRuntimeLocationsBeforeTheInheritedPath() {
            String path =
                    BinaryLocator.providerPath(
                            "/Users/tester", "/usr/bin:/bin", false, Map.of(), ":");

            assertThat(path)
                    .isEqualTo(
                            String.join(
                                    ":",
                                    "/Users/tester/.local/bin",
                                    "/Users/tester/.npm-global/bin",
                                    "/Users/tester/.volta/bin",
                                    "/opt/homebrew/bin",
                                    "/usr/local/bin",
                                    "/usr/bin:/bin"));
        }

        @Test
        void omitsTheInheritedPathWhenItIsBlank() {
            String path = BinaryLocator.providerPath("/Users/tester", " ", false, Map.of(), ":");

            assertThat(path)
                    .isEqualTo(
                            String.join(
                                    ":",
                                    "/Users/tester/.local/bin",
                                    "/Users/tester/.npm-global/bin",
                                    "/Users/tester/.volta/bin",
                                    "/opt/homebrew/bin",
                                    "/usr/local/bin"));
        }

        @Test
        void twoArgProviderPathDelegatesToRuntimeAndPutPathEnvNormalizesMixedCasePath() {
            assertThat(BinaryLocator.providerPath("/Users/tester", "existing"))
                    .isEqualTo(BinaryLocator.providerPathForRuntime("/Users/tester", "existing"));

            LinkedHashMap<String, String> env = new LinkedHashMap<>();
            env.put("Path", "C:\\Tools\\Bin");
            BinaryLocator.putPathEnv(env, "C:\\Users\\tester");
            assertThat(env).containsKey("PATH").doesNotContainKey("Path");
            assertThat(env.get("PATH")).contains("C:\\Tools\\Bin");
        }

        @Test
        void candidatePathsIncludeVoltaOnPosixAndProgramFilesVoltaOnWindows() {
            assertThat(BinaryLocator.candidatePaths("claude", "/Users/tester", false, Map.of()))
                    .contains("/Users/tester/.volta/bin/claude");
            assertThat(
                            BinaryLocator.candidatePaths(
                                    "claude",
                                    "C:\\Users\\tester",
                                    true,
                                    Map.of("ProgramFiles", "C:\\Program Files")))
                    .contains(new File("C:\\Program Files\\Volta", "claude").getPath());
            assertThat(
                            BinaryLocator.candidatePaths(
                                    "agy",
                                    "C:\\Users\\tester",
                                    true,
                                    Map.of("LOCALAPPDATA", "C:\\Users\\tester\\AppData\\Local")))
                    .contains(
                            new File("C:\\Users\\tester\\AppData\\Local\\agy\\bin", "agy")
                                    .getPath());
        }

        @Test
        void buildsWindowsProviderPathWithoutPosixDirectories() {
            String path =
                    BinaryLocator.providerPath(
                            "C:\\Users\\tester",
                            "C:\\Windows\\System32",
                            true,
                            Map.of(
                                    "LOCALAPPDATA", "C:\\Users\\tester\\AppData\\Local",
                                    "APPDATA", "C:\\Users\\tester\\AppData\\Roaming"),
                            ";");

            assertThat(path)
                    .isEqualTo(
                            String.join(
                                    ";",
                                    "C:\\Users\\tester\\.local\\bin",
                                    "C:\\Users\\tester\\.volta\\bin",
                                    "C:\\Users\\tester\\AppData\\Local\\agy\\bin",
                                    "C:\\Users\\tester\\AppData\\Local\\Volta\\bin",
                                    "C:\\Users\\tester\\AppData\\Roaming\\npm",
                                    "C:\\Windows\\System32"));
        }
    }

    @Nested
    class FindAndAvailability {

        @TempDir Path tempDir;

        @Test
        void resolvesWindowsExeBeforeCmdShimAndFindsCmdOnPath() throws IOException {
            Path npmDir = Files.createDirectories(tempDir.resolve("npm"));
            Path cmdShim = Files.writeString(npmDir.resolve("claude.cmd"), "@echo off\r\n");

            Map<String, String> env =
                    Map.of("Path", npmDir.toString(), "PATHEXT", ".COM;.EXE;.CMD");

            assertThat(BinaryLocator.isBinaryAvailable("claude", List.of(), true, env)).isTrue();
            assertThat(BinaryLocator.findBinary("claude", List.of(), true, env))
                    .isEqualTo(cmdShim.toFile().getAbsolutePath());

            Path exeFile = Files.writeString(npmDir.resolve("claude.exe"), "MZ");
            assertThat(
                            BinaryLocator.findBinary(
                                    "claude",
                                    List.of(npmDir.resolve("claude").toString()),
                                    true,
                                    env))
                    .isEqualTo(exeFile.toFile().getPath());
        }

        @Test
        void ignoresExtensionlessPosixShimOnWindows() throws IOException {
            Path npmDir = Files.createDirectories(tempDir.resolve("npm"));
            Path posixShim = Files.writeString(npmDir.resolve("claude"), "#!/bin/sh\n");
            Map<String, String> env =
                    Map.of("Path", npmDir.toString(), "PATHEXT", ".COM;.EXE;.BAT;.CMD");

            assertThat(
                            BinaryLocator.isBinaryAvailable(
                                    "claude", List.of(posixShim.toString()), true, env))
                    .isFalse();
            assertThat(BinaryLocator.findBinary("claude", List.of(posixShim.toString()), true, env))
                    .isEqualTo("claude");
        }

        @Test
        void returnsBareNameWhenMissingEverywhere() {
            assertThat(BinaryLocator.isBinaryAvailable("claude", List.of(), true, Map.of()))
                    .isFalse();
            assertThat(BinaryLocator.findBinary("claude", List.of(), false, Map.of()))
                    .isEqualTo("claude");
        }
    }

    @Nested
    class BuildCommand {

        @Test
        void wrapsBatchShimsAllowingParenthesesAndRejectsMetacharactersInPathOrArgs()
                throws IOException {
            Map<String, String> env = Map.of("ComSpec", "C:\\Windows\\System32\\cmd.exe");
            String x86Shim = "C:\\Program Files (x86)\\nodejs\\claude.cmd";
            String userShim = "C:\\Users\\Jane (Contractor)\\AppData\\Roaming\\npm\\claude.bat";

            assertThat(BinaryLocator.buildCommand(x86Shim, true, env, List.of("auth", "status")))
                    .containsExactly(
                            "C:\\Windows\\System32\\cmd.exe",
                            "/d",
                            "/c",
                            "call",
                            x86Shim,
                            "auth",
                            "status");
            assertThat(BinaryLocator.buildCommand(userShim, true, env, List.of("help", "config")))
                    .containsExactly(
                            "C:\\Windows\\System32\\cmd.exe",
                            "/d",
                            "/c",
                            "call",
                            userShim,
                            "help",
                            "config");
            assertThat(
                            BinaryLocator.buildCommand(
                                    "C:\\Program Files\\nodejs\\claude.exe",
                                    true,
                                    env,
                                    List.of("auth", "status")))
                    .containsExactly("C:\\Program Files\\nodejs\\claude.exe", "auth", "status");

            for (String unsafePath :
                    List.of(
                            "C:\\tools\\bad&calc.cmd",
                            "C:\\tools\\bad|calc.cmd",
                            "C:\\tools\\bad%PATH%.cmd",
                            "C:\\tools\\bad!var!.bat",
                            "C:\\tools\\bad\"quote.cmd")) {
                assertThatThrownBy(
                                () ->
                                        BinaryLocator.buildCommand(
                                                unsafePath, true, env, List.of("help")))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("unsafe path characters");
            }
            for (String unsafeArg : List.of("model&calc", "sess%PATH%", "line\nbreak", "a\"b")) {
                assertThatThrownBy(
                                () ->
                                        BinaryLocator.buildCommand(
                                                x86Shim, true, env, List.of("--model", unsafeArg)))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("unsafe argument characters");
            }
        }
    }
}
