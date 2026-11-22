package com.jinloes.prpilot.review;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Resolves provider CLI binaries (`claude`, `copilot`, `agy`). Probes known hard-coded install
 * paths before falling back to the bare command name, because GUI-launched IDEs often have an
 * incomplete {@code PATH}. Mirrors {@code github-engine}'s own binary-resolution convention rather
 * than reaching across module boundaries for the tiny amount of logic involved.
 */
final class BinaryLocator {

    private static final List<String> DEFAULT_WINDOWS_EXTS = List.of(".exe", ".cmd", ".bat");
    private static final Pattern UNSAFE_WINDOWS_BATCH_CHARS =
            Pattern.compile("[\"&|<>^%!\\r\\n\\u0000]");

    private BinaryLocator() {}

    static boolean isWindows() {
        return isWindowsOs(System.getProperty("os.name", ""));
    }

    static boolean isWindowsOs(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static String findBinary(String name, List<String> candidates) {
        return findBinary(name, candidates, isWindows(), System.getenv());
    }

    static String findBinary(
            String name, List<String> candidates, boolean windows, Map<String, String> env) {
        List<String> extensions = windows ? windowsExtensions(env) : List.of("");
        for (String candidate : candidates) {
            String match = resolveExecutableInCandidate(candidate, extensions, windows);
            if (match != null) {
                return match;
            }
        }
        String pathValue = envValue(env, "PATH");
        if (pathValue != null) {
            String separator = windows ? ";" : ":";
            for (String dir : pathValue.split(Pattern.quote(separator))) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String ext : extensions) {
                    File file = new File(dir.trim(), name + ext);
                    if (isExecutableFile(file, windows)) {
                        return windows ? file.getAbsolutePath() : name;
                    }
                }
            }
        }
        return name;
    }

    /**
     * Reports whether {@code name} is resolvable without spawning it: true when one of the
     * hard-coded {@code candidates} is an existing file, or when {@code name} is found on the
     * process {@code PATH}. Mirrors the resolution a later spawn would use, so it is a faithful
     * preflight for provider CLIs.
     */
    static boolean isBinaryAvailable(String name, List<String> candidates) {
        return isBinaryAvailable(name, candidates, isWindows(), System.getenv());
    }

    static boolean isBinaryAvailable(
            String name, List<String> candidates, boolean windows, Map<String, String> env) {
        List<String> extensions = windows ? windowsExtensions(env) : List.of("");
        for (String candidate : candidates) {
            if (resolveExecutableInCandidate(candidate, extensions, windows) != null) {
                return true;
            }
        }
        String pathValue = envValue(env, "PATH");
        if (pathValue == null) {
            return false;
        }
        String separator = windows ? ";" : ":";
        for (String dir : pathValue.split(Pattern.quote(separator))) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : extensions) {
                File candidate = new File(dir.trim(), name + ext);
                if (isExecutableFile(candidate, windows)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<String> candidatePaths(String name) {
        return candidatePaths(
                name, System.getProperty("user.home", ""), isWindows(), System.getenv());
    }

    static List<String> candidatePaths(
            String name, String userHome, boolean windows, Map<String, String> env) {
        if (!windows) {
            return List.of(
                    userHome + "/.local/bin/" + name,
                    userHome + "/.npm-global/bin/" + name,
                    userHome + "/.volta/bin/" + name,
                    "/usr/local/bin/" + name,
                    "/opt/homebrew/bin/" + name,
                    "/usr/bin/" + name);
        }
        List<String> candidates = new ArrayList<>();
        String localAppData = envValue(env, "LOCALAPPDATA");
        String appData = envValue(env, "APPDATA");
        String programFiles = envValue(env, "ProgramFiles");
        if (userHome != null && !userHome.isBlank()) {
            candidates.add(new File(new File(userHome, ".local\\bin"), name).getPath());
            candidates.add(new File(new File(userHome, ".volta\\bin"), name).getPath());
        }
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(new File(new File(localAppData, name + "\\bin"), name).getPath());
            candidates.add(new File(new File(localAppData, "Programs\\" + name), name).getPath());
            candidates.add(new File(new File(localAppData, "Volta\\bin"), name).getPath());
        }
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(new File(new File(programFiles, "Volta"), name).getPath());
        }
        if (appData != null && !appData.isBlank()) {
            candidates.add(new File(new File(appData, "npm"), name).getPath());
        }
        return candidates;
    }

    static String providerPath(String userHome, String existingPath) {
        return providerPathForRuntime(userHome, existingPath);
    }

    static String providerPathForRuntime(String userHome, String existingPath) {
        return providerPath(
                userHome, existingPath, isWindows(), System.getenv(), File.pathSeparator);
    }

    static void putPathEnv(Map<String, String> env, String userHome) {
        String existingPath = envValue(env, "PATH");
        env.keySet().removeIf(key -> key.equalsIgnoreCase("PATH") && !key.equals("PATH"));
        env.put("PATH", providerPathForRuntime(userHome, existingPath == null ? "" : existingPath));
    }

    static String providerPath(
            String userHome,
            String existingPath,
            boolean windows,
            Map<String, String> env,
            String pathSeparator) {
        List<String> directories = new ArrayList<>();
        if (windows) {
            if (userHome != null && !userHome.isBlank()) {
                directories.add(new File(userHome, ".local\\bin").getPath());
                directories.add(new File(userHome, ".volta\\bin").getPath());
            }
            String localAppData = envValue(env, "LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                directories.add(new File(localAppData, "agy\\bin").getPath());
                directories.add(new File(localAppData, "Volta\\bin").getPath());
            }
            String appData = envValue(env, "APPDATA");
            if (appData != null && !appData.isBlank()) {
                directories.add(new File(appData, "npm").getPath());
            }
        } else {
            directories.add(userHome + "/.local/bin");
            directories.add(userHome + "/.npm-global/bin");
            directories.add(userHome + "/.volta/bin");
            directories.add("/opt/homebrew/bin");
            directories.add("/usr/local/bin");
        }
        if (existingPath != null && !existingPath.isBlank()) {
            directories.add(existingPath);
        }
        return String.join(pathSeparator, directories);
    }

    static boolean isBatchShimPath(String binary) {
        if (binary == null) {
            return false;
        }
        String lower = binary.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    static boolean isWindowsBatchShim(String binary) {
        return isWindows() && isBatchShimPath(binary);
    }

    static String resolveComSpec(Map<String, String> env) {
        return StringUtils.defaultIfBlank(envValue(env, "ComSpec"), "cmd.exe");
    }

    static void validateWindowsBatchToken(String value, String label) throws IOException {
        if (value == null || UNSAFE_WINDOWS_BATCH_CHARS.matcher(value).find()) {
            throw new IOException(
                    "Refusing to invoke Windows batch shim with unsafe "
                            + label
                            + " characters: "
                            + value);
        }
    }

    static List<String> buildCommand(String binary, List<String> args) throws IOException {
        return buildCommand(binary, isWindows(), System.getenv(), args);
    }

    static List<String> buildCommand(
            String binary, boolean windows, Map<String, String> env, List<String> args)
            throws IOException {
        if (windows && isBatchShimPath(binary)) {
            validateWindowsBatchToken(binary, "path");
            List<String> command = new ArrayList<>(args.size() + 5);
            command.add(resolveComSpec(env));
            command.add("/d");
            command.add("/c");
            command.add("call");
            command.add(binary);
            for (String arg : args) {
                validateWindowsBatchToken(arg, "argument");
                command.add(arg);
            }
            return command;
        }
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(binary);
        command.addAll(args);
        return command;
    }

    private static String resolveExecutableInCandidate(
            String candidate, List<String> extensions, boolean windows) {
        if (!windows) {
            File file = new File(candidate);
            return file.isFile() ? candidate : null;
        }
        String fileName = new File(candidate).getName().toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (fileName.endsWith(ext)) {
                File file = new File(candidate);
                return file.isFile() ? file.getPath() : null;
            }
        }
        for (String ext : extensions) {
            File file = new File(candidate + ext);
            if (file.isFile()) {
                return file.getPath();
            }
        }
        return null;
    }

    private static boolean isExecutableFile(File file, boolean windows) {
        return file.isFile() && (windows || file.canExecute());
    }

    private static List<String> windowsExtensions(Map<String, String> env) {
        String pathExt = envValue(env, "PATHEXT");
        if (pathExt == null || pathExt.isBlank()) {
            return DEFAULT_WINDOWS_EXTS;
        }
        List<String> result = new ArrayList<>(DEFAULT_WINDOWS_EXTS);
        for (String raw : pathExt.split(";")) {
            String ext = raw.trim().toLowerCase(Locale.ROOT);
            if (ext.startsWith(".") && !result.contains(ext)) {
                result.add(ext);
            }
        }
        return result;
    }

    static String envValue(Map<String, String> env, String key) {
        if (env == null) return null;
        String direct = env.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
