package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.ProviderSetupProbe;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link WebviewPanelSupport} and {@link WebviewBridgeMessages.ProviderReadinessDto}. */
class WebviewProviderReadinessTest {

    @Nested
    class ProviderReadinessDto {

        @Test
        void threeArgConstructorDerivesAntigravityAuthCommand() {
            WebviewBridgeMessages.ProviderReadinessDto dto =
                    new WebviewBridgeMessages.ProviderReadinessDto("antigravity", true, "Ready");

            assertThat(dto.provider()).isEqualTo("antigravity");
            assertThat(dto.available()).isTrue();
            assertThat(dto.binaryStatus()).isEqualTo("ready");
            assertThat(dto.authenticationStatus()).isEqualTo("unverified");
            assertThat(dto.authCommand()).isEqualTo("agy");
        }
    }

    @Nested
    class ProviderReadinessMapping {

        @Test
        void unverifiedAntigravityProbeMapsToAuthCommandGuidance() {
            WebviewBridgeMessages.ProviderReadinessDto dto =
                    WebviewPanelSupport.providerReadiness(
                            ReviewProvider.ANTIGRAVITY,
                            new ProviderSetupProbe.Result(true, "ready", "unverified", "agy"));

            assertThat(dto.provider()).isEqualTo("antigravity");
            assertThat(dto.available()).isTrue();
            assertThat(dto.detail()).contains("run 'agy'");
            assertThat(dto.authCommand()).isEqualTo("agy");
        }

        @Test
        void missingAntigravityProbeNamesAgyInstallGuidance() {
            WebviewBridgeMessages.ProviderReadinessDto dto =
                    WebviewPanelSupport.providerReadiness(
                            ReviewProvider.ANTIGRAVITY,
                            new ProviderSetupProbe.Result(false, "missing", "unavailable", "agy"));

            assertThat(dto.provider()).isEqualTo("antigravity");
            assertThat(dto.available()).isFalse();
            assertThat(dto.detail()).contains("'agy'").contains("Antigravity CLI");
        }
    }
}
