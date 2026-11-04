package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class SidecarConfigurationTest {

    static class KnownFields {
        public String name;
    }

    @Nested
    class JsonRpcObjectMapper {
        @Test
        void ignoresFieldsTheSidecarDoesNotKnow() throws Exception {
            ObjectMapper mapper = SidecarConfiguration.jsonRpcObjectMapper();
            ObjectNode payload = mapper.createObjectNode().put("name", "pr").put("addedLater", 1);

            KnownFields bound = mapper.treeToValue(payload, KnownFields.class);

            assertThat(bound.name).isEqualTo("pr");
        }

        @Test
        void isJackson2SoEngineTreeTypesFlowThrough() {
            ObjectMapper mapper = SidecarConfiguration.jsonRpcObjectMapper();

            JsonNode node = mapper.valueToTree(Map.of("ok", true));

            assertThat(node.path("ok").asBoolean()).isTrue();
        }
    }

    @Nested
    class ApplicationContext {
        @Test
        void bootsWithoutAWebServerAndWiresTheJsonRpcServerWithTheConfiguredMapper() {
            try (ConfigurableApplicationContext context =
                    new SpringApplicationBuilder(PrPilotSidecarApplication.class)
                            .web(WebApplicationType.NONE)
                            .bannerMode(Banner.Mode.OFF)
                            .logStartupInfo(false)
                            .run()) {
                assertThat(context.getBeansOfType(ObjectMapper.class)).hasSize(1);
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                assertThat(
                                mapper.getDeserializationConfig()
                                        .isEnabled(
                                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                        .isFalse();
                assertThat(context.getBean(StdioJsonRpcServer.class)).isNotNull();
            }
        }
    }
}
