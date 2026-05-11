package com.jinloes.prpilot.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.util.ProcessUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class GitHubAuthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /**
     * Resolves a GitHub token by running {@code gh auth token}. For GitHub Enterprise, passes
     * {@code --hostname <host>} derived from the base URL.
     *
     * @param githubBaseUrl e.g. "https://github.com" or "https://github.mycompany.com"
     * @throws IOException if the gh CLI is not installed, not authenticated, or returns a non-zero
     *     exit code
     */
    public String resolveToken(String githubBaseUrl) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("gh", "auth", "token"));
        if (githubBaseUrl != null && !githubBaseUrl.equals("https://github.com")) {
            String hostname = URI.create(githubBaseUrl).getHost();
            if (hostname != null && !hostname.isBlank()) {
                cmd.addAll(List.of("--hostname", hostname));
            }
        }

        // Replace the first element ("gh") with the resolved binary path so the
        // plugin works when IntelliJ is launched from the GUI and doesn't inherit
        // the user's shell PATH (e.g. Homebrew on /opt/homebrew/bin).
        cmd.set(0, findGhBinary());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Ensure HOME is set so gh can locate its config / keychain entry.
        pb.environment().put("HOME", System.getProperty("user.home"));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();

        if (exitCode != 0 || output.isBlank()) {
            throw new IOException(
                    "gh auth token failed — run 'gh auth login' in a terminal first.\n" + output);
        }
        return output;
    }

    /**
     * Returns the absolute path to the {@code gh} binary by probing common install locations. Falls
     * back to {@code "gh"} (plain name) as a last resort so the OS can try PATH resolution.
     */
    private static String findGhBinary() {
        return ProcessUtil.findBinary(
                "gh",
                List.of(
                        "/opt/homebrew/bin/gh", // Apple Silicon Homebrew
                        "/usr/local/bin/gh", // Intel Homebrew / manual install
                        "/usr/bin/gh", // system package managers
                        "/home/linuxbrew/.linuxbrew/bin/gh"));
    }

    /** Fetches the authenticated user's login name to confirm the token works. */
    public String getAuthenticatedUsername(String apiBaseUrl, String token)
            throws IOException, InterruptedException {

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(apiBaseUrl + "/user"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "intellij-claude-reviews/1.0")
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "GitHub auth check failed ("
                            + response.statusCode()
                            + ") — run 'gh auth login' in a terminal.");
        }
        return MAPPER.readTree(response.body()).path("login").asText();
    }
}
