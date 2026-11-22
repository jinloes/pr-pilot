package com.jinloes.prpilot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.rpc.McpHttpServerConfig;
import com.microsoft.copilot.rpc.McpServerConfig;
import com.microsoft.copilot.rpc.McpStdioServerConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads MCP server definitions from the <em>trusted</em> Copilot config directory (the user's own
 * {@code ~/.copilot/mcp-config.json}, or an explicit {@code configDir} override) so a review can
 * inherit the user's MCP servers without ever enabling the SDK's on-disk config discovery.
 *
 * <p>Why not just enable discovery: the SDK's config discovery scans the session working directory
 * for a repo-local {@code .mcp.json}, and for a review that working directory is the untrusted
 * PR-branch worktree. A malicious PR could therefore ship an {@code .mcp.json} that defines an MCP
 * server whose {@code command} is an arbitrary process — code execution triggered merely by
 * generating a review, bypassing the read-only permission gate (which only sees later tool-call
 * kinds, not server launch). Reading only the trusted config file and injecting the servers via
 * {@link com.microsoft.copilot.rpc.SessionMcpOptions#setServers} closes that vector while
 * preserving the "inherit my MCP servers" behavior. See {@code ARCHITECTURE.md} "Provider
 * capability isolation".
 */
final class CopilotMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(CopilotMcpConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String CONFIG_FILE_NAME = "mcp-config.json";
    private static final String DEFAULT_CONFIG_DIR_NAME = ".copilot";

    private CopilotMcpConfig() {}

    /**
     * Resolves the trusted {@code mcp-config.json} for an optional {@code configDir} override.
     * Blank falls back to {@code ~/.copilot}. The returned file is not guaranteed to exist.
     */
    static File resolveConfigFile(String configDir) {
        File dir =
                StringUtils.isNotBlank(configDir)
                        ? new File(configDir.trim())
                        : new File(System.getProperty("user.home", "/"), DEFAULT_CONFIG_DIR_NAME);
        return new File(dir, CONFIG_FILE_NAME);
    }

    /**
     * Reads and parses the trusted MCP servers. Returns an empty map when the file is missing,
     * unreadable, or malformed — inheriting nothing is always the safe default.
     */
    static Map<String, McpServerConfig> loadTrustedServers(String configDir) {
        File file = resolveConfigFile(configDir);
        if (!file.isFile()) {
            return Map.of();
        }
        try {
            return parseServers(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Could not read trusted MCP config {}; inheriting no MCP servers", file, e);
            return Map.of();
        }
    }

    /**
     * Parses the {@code mcpServers} object of an {@code mcp-config.json} document into typed SDK
     * configs. Unknown/blank entries are skipped. Package-private for tests.
     */
    static Map<String, McpServerConfig> parseServers(String json) {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            log.warn("Malformed MCP config JSON; inheriting no MCP servers", e);
            return Map.of();
        }
        JsonNode serversNode = root == null ? null : root.get("mcpServers");
        if (serversNode == null || !serversNode.isObject()) {
            return servers;
        }
        for (Map.Entry<String, JsonNode> entry : serversNode.properties()) {
            McpServerConfig config = toServerConfig(entry.getValue());
            if (config != null) {
                servers.put(entry.getKey(), config);
            }
        }
        return servers;
    }

    private static McpServerConfig toServerConfig(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (!validStringArray(node, "tools") || !validPositiveInt(node, "timeout")) {
            log.warn("Skipping malformed MCP server entry: tools or timeout has an invalid shape");
            return null;
        }
        Integer timeout = node.has("timeout") ? node.path("timeout").intValue() : null;
        String url = textOrNull(node, "url");
        if (StringUtils.isNotBlank(url)) {
            McpHttpServerConfig http = new McpHttpServerConfig().setUrl(url);
            Map<String, String> headers = stringMap(node.get("headers"));
            if (!headers.isEmpty()) {
                http.setHeaders(headers);
            }
            if (node.has("tools")) http.setTools(stringList(node.get("tools")));
            if (timeout != null) http.setTimeout(timeout);
            return http;
        }
        String command = textOrNull(node, "command");
        if (StringUtils.isNotBlank(command)) {
            McpStdioServerConfig stdio = new McpStdioServerConfig().setCommand(command);
            List<String> args = stringList(node.get("args"));
            if (!args.isEmpty()) {
                stdio.setArgs(args);
            }
            Map<String, String> env = stringMap(node.get("env"));
            if (!env.isEmpty()) {
                stdio.setEnv(env);
            }
            if (node.has("tools")) stdio.setTools(stringList(node.get("tools")));
            if (timeout != null) stdio.setTimeout(timeout);
            return stdio;
        }
        return null;
    }

    private static boolean validStringArray(JsonNode node, String key) {
        if (!node.has(key)) return true;
        JsonNode value = node.get(key);
        if (value == null || !value.isArray()) return false;
        for (JsonNode element : value) {
            if (!element.isTextual()) return false;
        }
        return true;
    }

    private static boolean validPositiveInt(JsonNode node, String key) {
        if (!node.has(key)) return true;
        JsonNode value = node.get(key);
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToInt()
                && value.intValue() > 0;
    }

    private static String textOrNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    out.add(element.textValue());
                }
            }
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (entry.getValue().isTextual()) {
                    out.put(entry.getKey(), entry.getValue().textValue());
                }
            }
        }
        return out;
    }
}
