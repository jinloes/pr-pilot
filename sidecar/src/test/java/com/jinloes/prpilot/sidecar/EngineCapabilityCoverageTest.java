package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.engine.ReviewSessionService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Enforces the capability-parity boundary described in {@code AGENTS.md} "Host parity obligations".
 *
 * <p>Hosts are allowed to lag in <em>consuming</em> an engine capability, but the engine's whole
 * surface must always be reachable over JSON-RPC — otherwise a host that needs a capability the
 * sidecar does not expose has no option but to re-implement it locally, which is exactly the drift
 * this boundary exists to prevent. These tests replace the hand-maintained parity mapping table
 * that used to live in {@code AGENTS.md}.
 */
class EngineCapabilityCoverageTest {

    private ExecutorService reviewExecutor;
    private StdioJsonRpcServer server;

    @BeforeEach
    void setUp() {
        reviewExecutor = Executors.newSingleThreadExecutor();
        server =
                new StdioJsonRpcServer(
                        new ObjectMapper(),
                        new StdioFrameCodec(),
                        new SidecarBootstrapService(),
                        new GitHubEngine(),
                        new ReviewSessionService(),
                        reviewExecutor);
    }

    @AfterEach
    void tearDown() {
        reviewExecutor.shutdownNow();
    }

    private static Set<String> declaredMethodNames(Class<?> api) {
        return Arrays.stream(api.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    /** Every wire name declared by either engine. */
    private static Set<String> declaredWireNames() {
        Set<String> names = new HashSet<>(GitHubEngineApi.RPC_METHODS.values());
        names.addAll(ReviewEngineApi.RPC_METHODS.values());
        return names;
    }

    @Nested
    class RpcMethodsCompleteness {

        @Test
        void everyGitHubEngineMethodDeclaresAWireName() {
            assertThat(GitHubEngineApi.RPC_METHODS.keySet())
                    .as(
                            "every GitHubEngineApi method needs an RPC_METHODS entry — add the new"
                                    + " capability's wire name there")
                    .containsExactlyInAnyOrderElementsOf(
                            declaredMethodNames(GitHubEngineApi.class));
        }

        @Test
        void everyReviewEngineMethodDeclaresAWireName() {
            assertThat(ReviewEngineApi.RPC_METHODS.keySet())
                    .as(
                            "every ReviewEngineApi method needs an RPC_METHODS entry — add the new"
                                    + " capability's wire name there")
                    .containsExactlyInAnyOrderElementsOf(
                            declaredMethodNames(ReviewEngineApi.class));
        }

        @Test
        void wireNamesAreUniqueAcrossEngines() {
            Set<String> github = Set.copyOf(GitHubEngineApi.RPC_METHODS.values());
            Set<String> review = Set.copyOf(ReviewEngineApi.RPC_METHODS.values());
            assertThat(github).doesNotContainAnyElementsOf(review);
        }
    }

    @Nested
    class SidecarExposure {

        @Test
        void sidecarExposesEveryGitHubCapability() {
            assertThat(server.registeredMethodNames())
                    .as(
                            "StdioJsonRpcServer must register a handler for every GitHub engine"
                                    + " capability — hosts cannot reach an unexposed capability"
                                    + " without re-implementing it")
                    .containsAll(GitHubEngineApi.RPC_METHODS.values());
        }

        @Test
        void sidecarExposesEveryReviewCapability() {
            assertThat(server.registeredMethodNames())
                    .as("StdioJsonRpcServer must register a handler for every review capability")
                    .containsAll(ReviewEngineApi.RPC_METHODS.values());
        }

        /**
         * Guards the other direction: a handler that is not backed by a declared capability is
         * either dead protocol surface or an undeclared capability. {@code initialize} is the one
         * legitimate exception — it is sidecar bootstrap, not an engine capability.
         */
        @Test
        void sidecarExposesNothingBeyondTheDeclaredCapabilities() {
            Set<String> allowed = declaredWireNames();
            allowed.add("initialize");

            assertThat(server.registeredMethodNames())
                    .as("undeclared wire methods must be added to an engine API or removed")
                    .isSubsetOf(allowed);
        }
    }

    /**
     * The {@code initialize} handshake advertises a capability map that clients gate startup on. It
     * used to be fifteen hand-written literals related to nothing, so it could drift from the
     * engines silently — a capability could be reachable over RPC but unadvertised, or advertised
     * but absent. These tests close that gap by pinning the derivation.
     */
    @Nested
    class BootstrapCapabilityMap {

        /** Every wire method named by some capability group. */
        private Set<String> coveredWireNames() {
            return SidecarBootstrapService.CAPABILITY_METHODS.values().stream()
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());
        }

        @Test
        void everyDeclaredWireMethodBelongsToACapability() {
            assertThat(coveredWireNames())
                    .as(
                            "a capability reachable over RPC but not advertised by initialize is"
                                    + " invisible to clients — add the new wire method to a group"
                                    + " in SidecarBootstrapService.CAPABILITY_METHODS")
                    .containsAll(declaredWireNames());
        }

        @Test
        void noCapabilityNamesAnUndeclaredWireMethod() {
            assertThat(coveredWireNames())
                    .as(
                            "a capability advertising a wire method no engine declares promises"
                                    + " clients something the sidecar cannot answer")
                    .isSubsetOf(declaredWireNames());
        }

        /**
         * The grouping is only meaningful if each method has one owner; two capabilities covering
         * the same method would make "is this advertised?" ambiguous.
         */
        @Test
        void eachWireMethodBelongsToExactlyOneCapability() {
            List<String> allNames =
                    SidecarBootstrapService.CAPABILITY_METHODS.values().stream()
                            .flatMap(Set::stream)
                            .toList();

            assertThat(allNames).doesNotHaveDuplicates();
        }

        @Test
        void advertisedCapabilitiesAreExactlyTheDeclaredGroups() {
            Map<String, Boolean> advertised =
                    new SidecarBootstrapService().initialize().capabilities();

            assertThat(advertised.keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            SidecarBootstrapService.CAPABILITY_METHODS.keySet());
            assertThat(advertised.values()).allMatch(Boolean::booleanValue);
        }
    }
}
