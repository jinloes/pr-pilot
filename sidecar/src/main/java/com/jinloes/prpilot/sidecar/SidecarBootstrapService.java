package com.jinloes.prpilot.sidecar;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class SidecarBootstrapService {

    /**
     * Logical capability name to the set of JSON-RPC wire methods it covers.
     *
     * <p>The {@code initialize} payload advertises <em>logical</em> capabilities rather than raw
     * wire names because that is the client-visible contract: {@code sidecar.ts} gates startup on
     * these names, and several deliberately cover more than one method — {@code
     * draftReviewMutations} is only useful if save, submit, and delete are all present, so
     * advertising them separately would let a client start against a half-implemented surface.
     *
     * <p>This map exists so the advertised set is <em>derived</em> rather than hand-written a
     * second time. Before it, {@code initialize()} held fifteen free-floating string literals with
     * nothing relating them to the engines' {@code RPC_METHODS}, so a capability added to an engine
     * interface was silently absent from the handshake.
     *
     * <p>{@code EngineCapabilityCoverageTest} enforces both directions: every declared wire method
     * appears in exactly one capability here, and no capability names a wire method no engine
     * declares. Adding a capability to an engine interface therefore fails the build until it is
     * grouped here.
     */
    static final Map<String, Set<String>> CAPABILITY_METHODS =
            Map.ofEntries(
                    Map.entry("githubAuth", Set.of("github/checkAuth")),
                    Map.entry("prDetail", Set.of("prs/getDetail")),
                    Map.entry("prDiff", Set.of("prs/getDiff")),
                    Map.entry("prList", Set.of("prs/list")),
                    Map.entry("repoDetect", Set.of("repo/detect")),
                    Map.entry("draftReview", Set.of("prs/getDraftReview")),
                    Map.entry(
                            "draftReviewMutations",
                            Set.of(
                                    "prs/saveDraftReview",
                                    "prs/submitReview",
                                    "prs/deleteDraftReview")),
                    Map.entry("prSearch", Set.of("prs/search")),
                    Map.entry("starredRepos", Set.of("repos/listStarred")),
                    Map.entry("existingReviews", Set.of("prs/getExistingReviews")),
                    Map.entry("checkStatus", Set.of("prs/getCheckStatus")),
                    Map.entry("prCommits", Set.of("prs/getCommits")),
                    Map.entry("linkedIssues", Set.of("prs/getLinkedIssues")),
                    Map.entry("repoProfile", Set.of("repo/getProfile")),
                    Map.entry("repoGuidelines", Set.of("reviews/readGuidelines")),
                    Map.entry(
                            "semanticReviews",
                            Set.of(
                                    "reviews/prepareDeepReview",
                                    "reviews/listDeepReviews",
                                    "reviews/cleanupDeepReview")),
                    Map.entry(
                            "worktrees",
                            Set.of(
                                    "reviews/findGitRoot",
                                    "reviews/createWorktree",
                                    "reviews/removeWorktree")),
                    Map.entry(
                            "reviewGeneration",
                            Set.of(
                                    "reviews/generate",
                                    "reviews/chat",
                                    "reviews/cancel",
                                    "reviews/recordOutcome")));

    InitializeResult initialize() {
        // TreeMap so the advertised order is stable across runs; the handshake payload is read by
        // hand often enough during debugging that nondeterministic ordering is pure noise.
        Map<String, Boolean> capabilities = new TreeMap<>();
        CAPABILITY_METHODS.keySet().forEach(name -> capabilities.put(name, Boolean.TRUE));
        return new InitializeResult("pr-pilot-sidecar", "0.1.0", 1, capabilities);
    }

    record InitializeResult(
            String serviceName,
            String serviceVersion,
            int protocolVersion,
            Map<String, Boolean> capabilities) {}
}
