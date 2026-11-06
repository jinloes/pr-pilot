package com.jinloes.prpilot.review;

import com.github.copilot.rpc.McpHttpServerConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A validated host-run MCP endpoint that serves code-intelligence lookups from the host IDE's
 * language services. Only loopback {@code http} URLs and opaque bearer tokens are accepted: the
 * host passes them over the sidecar, and a review must never be pointed at a remote server or carry
 * header-injection characters into the provider's MCP client.
 */
public record HostCodeIntelligence(String url, String token) {

    private static final Logger log = LoggerFactory.getLogger(HostCodeIntelligence.class);

    /** MCP server name the Copilot session registers; reserved over any inherited entry. */
    static final String SERVER_NAME = "pr-pilot-code-intelligence";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,256}");
    private static final int MAX_URL_LENGTH = 512;

    /**
     * Returns the validated endpoint, or null when either value is absent or invalid. Invalid input
     * degrades to a review without host code intelligence rather than failing it.
     */
    public static HostCodeIntelligence of(String url, String token) {
        if (StringUtils.isAllBlank(url, token)) {
            return null;
        }
        if (!validUrl(url) || token == null || !TOKEN.matcher(token).matches()) {
            log.warn("Ignoring invalid host code-intelligence endpoint");
            return null;
        }
        return new HostCodeIntelligence(url, token);
    }

    static boolean validUrl(String url) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return "http".equals(uri.getScheme())
                    && uri.getRawUserInfo() == null
                    && uri.getHost() != null
                    && LOOPBACK_HOSTS.contains(uri.getHost())
                    && uri.getPort() > 0
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    McpHttpServerConfig toMcpServerConfig() {
        return new McpHttpServerConfig()
                .setUrl(url)
                .setHeaders(Map.of("Authorization", "Bearer " + token))
                .setTools(List.of("*"));
    }

    @Override
    public String toString() {
        return "HostCodeIntelligence[url=" + url + ", token=<redacted>]";
    }
}
