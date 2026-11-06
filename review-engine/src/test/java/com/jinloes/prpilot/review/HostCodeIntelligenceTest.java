package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.copilot.rpc.McpHttpServerConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostCodeIntelligenceTest {

    static final String TOKEN = "a".repeat(43);

    @Nested
    class Of {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "http://127.0.0.1:41234/mcp",
                    "http://localhost:8080/mcp",
                    "http://[::1]:9000/"
                })
        void acceptsLoopbackHttpUrls(String url) {
            assertThat(HostCodeIntelligence.of(url, TOKEN))
                    .isEqualTo(new HostCodeIntelligence(url, TOKEN));
        }

        @Test
        void returnsNullWhenTheHostSentNothing() {
            assertThat(HostCodeIntelligence.of(null, null)).isNull();
            assertThat(HostCodeIntelligence.of(" ", "")).isNull();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://127.0.0.1:41234/mcp",
                    "http://example.com:80/mcp",
                    "http://10.0.0.5:41234/mcp",
                    "http://user:pw@127.0.0.1:41234/mcp",
                    "http://127.0.0.1/mcp",
                    "http://127.0.0.1:41234/mcp?x=1",
                    "http://127.0.0.1:41234/mcp#frag",
                    "file:///tmp/socket",
                    "not a url"
                })
        void rejectsNonLoopbackOrUnusualUrls(String url) {
            assertThat(HostCodeIntelligence.of(url, TOKEN)).isNull();
        }

        @Test
        void rejectsAnOverlongUrl() {
            String url = "http://127.0.0.1:41234/" + "a".repeat(600);
            assertThat(HostCodeIntelligence.of(url, TOKEN)).isNull();
        }

        @Test
        void rejectsMissingShortOrHeaderInjectingTokens() {
            String url = "http://127.0.0.1:41234/mcp";
            assertThat(HostCodeIntelligence.of(url, null)).isNull();
            assertThat(HostCodeIntelligence.of(url, "short")).isNull();
            assertThat(HostCodeIntelligence.of(url, TOKEN + "\r\nX-Evil: 1")).isNull();
            assertThat(HostCodeIntelligence.of(url, "a".repeat(257))).isNull();
        }
    }

    @Nested
    class ToMcpServerConfig {

        @Test
        void sendsTheTokenAsABearerHeaderAndExposesAllHostTools() {
            McpHttpServerConfig config =
                    new HostCodeIntelligence("http://127.0.0.1:41234/mcp", TOKEN)
                            .toMcpServerConfig();

            assertThat(config.getUrl()).isEqualTo("http://127.0.0.1:41234/mcp");
            assertThat(config.getHeaders()).containsEntry("Authorization", "Bearer " + TOKEN);
            assertThat(config.getTools()).containsExactly("*");
        }
    }

    @Nested
    class ToStringMethod {

        @Test
        void redactsTheToken() {
            assertThat(new HostCodeIntelligence("http://127.0.0.1:1/mcp", TOKEN).toString())
                    .contains("http://127.0.0.1:1/mcp")
                    .doesNotContain(TOKEN);
        }
    }
}
