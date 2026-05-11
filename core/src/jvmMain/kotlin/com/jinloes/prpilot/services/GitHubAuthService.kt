package com.jinloes.prpilot.services

import com.jinloes.prpilot.util.ProcessUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

class GitHubAuthService {

    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /**
     * Resolves a GitHub token by running `gh auth token`. For GitHub Enterprise, passes
     * `--hostname <host>` derived from the base URL.
     *
     * @param githubBaseUrl e.g. "https://github.com" or "https://github.mycompany.com"
     * @throws IOException if the gh CLI is not installed, not authenticated, or returns a non-zero
     *     exit code
     */
    @Throws(IOException::class, InterruptedException::class)
    fun resolveToken(githubBaseUrl: String?): String {
        val cmd = mutableListOf("gh", "auth", "token")
        if (githubBaseUrl != null && githubBaseUrl != "https://github.com") {
            val hostname = URI.create(githubBaseUrl).host
            if (!hostname.isNullOrBlank()) {
                cmd.addAll(listOf("--hostname", hostname))
            }
        }

        // Replace the first element ("gh") with the resolved binary path so the
        // plugin works when IntelliJ is launched from the GUI and doesn't inherit
        // the user's shell PATH (e.g. Homebrew on /opt/homebrew/bin).
        cmd[0] = findGhBinary()
        val pb = ProcessBuilder(cmd)
        // Ensure HOME is set so gh can locate its config / keychain entry.
        pb.environment()["HOME"] = System.getProperty("user.home")
        pb.redirectErrorStream(true)
        val process = pb.start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IOException("gh auth token timed out — check your GitHub authentication in a terminal.")
        }
        val output = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8).trim()
        val exitCode = process.exitValue()

        if (exitCode != 0 || output.isBlank()) {
            throw IOException("gh auth token failed — run 'gh auth login' in a terminal first.\n$output")
        }
        return output
    }

    /** Fetches the authenticated user's login name to confirm the token works. */
    @Throws(IOException::class, InterruptedException::class)
    fun getAuthenticatedUsername(apiBaseUrl: String, token: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBaseUrl/user"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "pr-pilot/1.0")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw IOException(
                "GitHub auth check failed (${response.statusCode()}) — run 'gh auth login' in a terminal."
            )
        }
        return Json.parseToJsonElement(response.body())
            .jsonObject["login"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    companion object {

        /**
         * Returns the absolute path to the `gh` binary by probing common install locations. Falls
         * back to `"gh"` (plain name) as a last resort so the OS can try PATH resolution.
         */
        private fun findGhBinary(): String =
            ProcessUtil.findBinary(
                "gh",
                listOf(
                    "/opt/homebrew/bin/gh", // Apple Silicon Homebrew
                    "/usr/local/bin/gh", // Intel Homebrew / manual install
                    "/usr/bin/gh", // system package managers
                    "/home/linuxbrew/.linuxbrew/bin/gh",
                )
            )
    }
}
