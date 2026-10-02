package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WebviewPrSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    class HealthyDraftEntries {

        @Test
        void keepsHealthyEmptyStateDistinctFromUnavailableState() {
            var healthy =
                    WebviewPrSupport.healthyDraftEntries(
                            new PendingReviewIndex.LoadResult(List.of(), null));
            var unavailable =
                    WebviewPrSupport.healthyDraftEntries(
                            new PendingReviewIndex.LoadResult(List.of(), "corrupt"));

            assertThat(healthy).isPresent();
            assertThat(healthy.orElseThrow()).isEmpty();
            assertThat(unavailable).isEmpty();
        }
    }

    @Nested
    class WorktreeKey {

        @Test
        void normalizesOwnerAndRepoCase() {
            assertThat(WebviewPrSupport.worktreeKey(42, "JinLoes", "PR-Pilot"))
                    .isEqualTo("jinloes/pr-pilot#42");
        }

        @Test
        void keyIncludesPrNumber() {
            assertThat(WebviewPrSupport.worktreeKey(1, "a", "b"))
                    .isNotEqualTo(WebviewPrSupport.worktreeKey(2, "a", "b"));
        }
    }

    @Nested
    class IsSamePr {

        @Test
        void matchesByNumberAndRepoIgnoringCase() {
            PullRequest left = new PullRequest("t", "", "OwNeR", "RePo", 7, "", "a", "");
            PullRequest right = new PullRequest("t2", "", "owner", "repo", 7, "", "b", "");

            assertThat(WebviewPrSupport.isSamePr(left, right)).isTrue();
        }

        @Test
        void rejectsDifferentNumberOrRepo() {
            PullRequest base = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");
            PullRequest differentNumber = new PullRequest("t", "", "owner", "repo", 8, "", "a", "");
            PullRequest differentRepo = new PullRequest("t", "", "owner", "other", 7, "", "a", "");

            assertThat(WebviewPrSupport.isSamePr(base, differentNumber)).isFalse();
            assertThat(WebviewPrSupport.isSamePr(base, differentRepo)).isFalse();
        }

        @Test
        void returnsFalseWhenEitherIsNull() {
            PullRequest pr = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");

            assertThat(WebviewPrSupport.isSamePr(pr, null)).isFalse();
            assertThat(WebviewPrSupport.isSamePr(null, pr)).isFalse();
        }
    }

    @Nested
    class IsCurrentSelection {

        @Test
        void acceptsHydratedInstanceAtCapturedRevision() {
            PullRequest hydrated =
                    new PullRequest("detail", "", "acme", "platform", 7, "body", "a", "");

            assertThat(WebviewPrSupport.isCurrentSelection(hydrated, 4, "acme/platform#7", 4))
                    .isTrue();
        }

        @Test
        void rejectsSamePrReselectedAtNewerRevision() {
            PullRequest reselected =
                    new PullRequest("detail", "", "acme", "platform", 7, "body", "a", "");

            assertThat(WebviewPrSupport.isCurrentSelection(reselected, 5, "acme/platform#7", 4))
                    .isFalse();
        }
    }

    @Nested
    class MatchesPrRequest {

        @Test
        void matchesByIdentityFieldsIgnoringCase() {
            PullRequest pr = new PullRequest("t", "", "OwNeR", "RePo", 7, "", "a", "");

            assertThat(WebviewPrSupport.matchesPrRequest(pr, 7, "owner", "repo")).isTrue();
        }

        @Test
        void rejectsNullAndFieldMismatches() {
            PullRequest pr = new PullRequest("t", "", "owner", "repo", 7, "", "a", "");

            assertThat(WebviewPrSupport.matchesPrRequest(null, 7, "owner", "repo")).isFalse();
            assertThat(WebviewPrSupport.matchesPrRequest(pr, 8, "owner", "repo")).isFalse();
            assertThat(WebviewPrSupport.matchesPrRequest(pr, 7, "other", "repo")).isFalse();
            assertThat(WebviewPrSupport.matchesPrRequest(pr, 7, "owner", "other")).isFalse();
        }
    }

    @Nested
    class HydratePullRequest {

        @Test
        void replacesDetailFieldsAndPreservesListMetadata() {
            PullRequest summary =
                    new PullRequest(
                            "List title",
                            "https://github.com/acme/platform/pull/7",
                            "acme",
                            "platform",
                            7,
                            "",
                            "octocat",
                            "2026-07-30T12:00:00Z",
                            true,
                            ReviewStatus.REVIEWED);
            PrDetail detail =
                    new PrDetail(
                            false,
                            "Detailed title",
                            "Closes #42",
                            new PrDetail.Head("sha", "branch", "acme/platform", "clone"),
                            "acme/platform",
                            null);

            PullRequest hydrated = WebviewPrSupport.hydratePullRequest(summary, detail);

            assertThat(hydrated.getTitle()).isEqualTo("Detailed title");
            assertThat(hydrated.getBody()).isEqualTo("Closes #42");
            assertThat(hydrated.getHtmlUrl()).isEqualTo(summary.getHtmlUrl());
            assertThat(hydrated.getOwner()).isEqualTo("acme");
            assertThat(hydrated.getRepo()).isEqualTo("platform");
            assertThat(hydrated.getNumber()).isEqualTo(7);
            assertThat(hydrated.getAuthor()).isEqualTo("octocat");
            assertThat(hydrated.getCreatedAt()).isEqualTo("2026-07-30T12:00:00Z");
            assertThat(hydrated.isDraft()).isTrue();
            assertThat(hydrated.getReviewStatus()).isEqualTo(ReviewStatus.REVIEWED);
        }

        @Test
        void returnsSummaryWhenDetailReadFailed() {
            PullRequest summary =
                    new PullRequest("Title", "", "acme", "platform", 7, "", "octocat", "");

            assertThat(WebviewPrSupport.hydratePullRequest(summary, null)).isSameAs(summary);
        }
    }

    @Nested
    class MergeActivatedPr {

        @Test
        void preservesKnownReviewStatusForNotificationSummary() {
            PullRequest existing =
                    new PullRequest(
                            "List title",
                            "",
                            "acme",
                            "platform",
                            7,
                            "",
                            "octocat",
                            "",
                            false,
                            ReviewStatus.REVIEWED);
            PullRequest incoming =
                    new PullRequest(
                            "Notification title",
                            "",
                            "acme",
                            "platform",
                            7,
                            "",
                            "octocat",
                            "",
                            false,
                            ReviewStatus.UNAVAILABLE);

            PullRequest merged = WebviewPrSupport.mergeActivatedPr(existing, incoming);

            assertThat(merged.getTitle()).isEqualTo("Notification title");
            assertThat(merged.getReviewStatus()).isEqualTo(ReviewStatus.REVIEWED);
        }

        @Test
        void acceptsNewKnownReviewStatus() {
            PullRequest existing =
                    new PullRequest("Old", "", "acme", "platform", 7, "", "octocat", "");
            PullRequest incoming = existing.withReviewStatus(ReviewStatus.UPDATED_SINCE_REVIEW);

            assertThat(WebviewPrSupport.mergeActivatedPr(existing, incoming)).isSameAs(incoming);
        }
    }

    @Nested
    class SaveRepositoryInstructionsReply {
        @Test
        void remembersTrimmedTextAndRepliesWithTheStoredValue() {
            PluginSettings settings = new PluginSettings();
            Object reply =
                    WebviewPrSupport.saveRepositoryInstructionsReply(
                            settings, 7, "Acme", "Widget", "  API PRs precede service PRs.  ");

            assertThat(MAPPER.valueToTree(reply).toString())
                    .isEqualTo(
                            MAPPER.createObjectNode()
                                    .put("type", "repositoryInstructionsSaved")
                                    .put("prKey", "Acme/Widget#7")
                                    .put("instructions", "API PRs precede service PRs.")
                                    .toString());
            assertThat(settings.getRepositoryReviewInstructions("acme", "widget"))
                    .isEqualTo("API PRs precede service PRs.");
        }

        @Test
        void forgetsOnBlankText() {
            PluginSettings settings = new PluginSettings();
            settings.rememberRepositoryReviewInstructions("acme", "widget", "Old");
            Object reply =
                    WebviewPrSupport.saveRepositoryInstructionsReply(
                            settings, 7, "acme", "widget", "   ");

            assertThat(MAPPER.valueToTree(reply).path("instructions").asText("missing")).isEmpty();
            assertThat(settings.getRepositoryReviewInstructions()).isEmpty();
        }

        @Test
        void reportsInvalidNamesAndOversizedTextWithoutSaving() {
            PluginSettings settings = new PluginSettings();
            Object invalid =
                    WebviewPrSupport.saveRepositoryInstructionsReply(
                            settings, 1, "a b", "w", "Rule");
            Object oversized =
                    WebviewPrSupport.saveRepositoryInstructionsReply(
                            settings, 1, "acme", "widget", "x".repeat(10_001));

            assertThat(MAPPER.valueToTree(invalid).path("type").asText())
                    .isEqualTo("repositoryInstructionsSaveError");
            assertThat(MAPPER.valueToTree(oversized).path("message").asText())
                    .contains("10,000 characters");
            assertThat(MAPPER.valueToTree(oversized).path("prKey").asText())
                    .isEqualTo("acme/widget#1");
            assertThat(settings.getRepositoryReviewInstructions()).isEmpty();
        }

        @Test
        void validatorRequiresPrIdentityAndBoundedInstructions() {
            ObjectNode valid =
                    MAPPER.createObjectNode()
                            .put("protocolVersion", 1)
                            .put("type", "saveRepositoryInstructions")
                            .put("number", 7)
                            .put("owner", "acme")
                            .put("repo", "widget")
                            .put("instructions", "Rule");
            assertThat(BridgeMessageValidator.isValid(valid)).isTrue();
            assertThat(BridgeMessageValidator.isValid(valid.deepCopy().put("instructions", "")))
                    .isTrue();
            ObjectNode missing = valid.deepCopy();
            missing.remove("instructions");
            assertThat(BridgeMessageValidator.isValid(missing)).isFalse();
            assertThat(
                            BridgeMessageValidator.isValid(
                                    valid.deepCopy().put("instructions", "x".repeat(10_001))))
                    .isFalse();
            ObjectNode noPr = valid.deepCopy();
            noPr.remove("number");
            assertThat(BridgeMessageValidator.isValid(noPr)).isFalse();
        }
    }
}
