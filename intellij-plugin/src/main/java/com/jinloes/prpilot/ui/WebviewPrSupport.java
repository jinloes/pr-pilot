package com.jinloes.prpilot.ui;

import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewStatus;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.settings.RepositoryReviewInstructions;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.ErrorMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.RepositoryInstructionsSavedMsg;
import com.jinloes.prpilot.ui.WebviewBridgeMessages.WebviewPr;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/** Pure PR identity and bridge-reply helpers used by the webview panel. */
final class WebviewPrSupport {

    private WebviewPrSupport() {}

    static String worktreeKey(int number, String owner, String repo) {
        return owner.toLowerCase(java.util.Locale.ROOT)
                + "/"
                + repo.toLowerCase(java.util.Locale.ROOT)
                + "#"
                + number;
    }

    /**
     * Remembers (or forgets, when blank) review instructions for the PR's repository and returns
     * the bridge reply: the stored text on success, or an error the webview shows beside the field.
     */
    static Object saveRepositoryInstructionsReply(
            PluginSettings settings, int number, String owner, String repo, String instructions) {
        String key = bridgePrKey(number, owner, repo);
        if (RepositoryReviewInstructions.repositoryKey(owner, repo) == null) {
            return new ErrorMsg(
                    "repositoryInstructionsSaveError",
                    key,
                    "This repository name cannot be remembered.");
        }
        String stored = settings.rememberRepositoryReviewInstructions(owner, repo, instructions);
        if (stored == null) {
            return new ErrorMsg(
                    "repositoryInstructionsSaveError",
                    key,
                    "Repository instructions are limited to 10,000 characters and 200"
                            + " repositories.");
        }
        return new RepositoryInstructionsSavedMsg("repositoryInstructionsSaved", key, stored);
    }

    static String bridgePrKey(int number, String owner, String repo) {
        return owner + "/" + repo + "#" + number;
    }

    static String normalizeSearchScope(String value) {
        return switch (value) {
            case "authored", "assigned", "reviewRequested" -> value;
            default -> "currentRepo";
        };
    }

    static boolean isSamePr(PullRequest left, PullRequest right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getNumber() == right.getNumber()
                && StringUtils.equalsIgnoreCase(left.getOwner(), right.getOwner())
                && StringUtils.equalsIgnoreCase(left.getRepo(), right.getRepo());
    }

    static PullRequest hydratePullRequest(PullRequest summary, PrDetail detail) {
        if (detail == null) {
            return summary;
        }
        return new PullRequest(
                detail.title(),
                summary.getHtmlUrl(),
                summary.getOwner(),
                summary.getRepo(),
                summary.getNumber(),
                detail.body(),
                summary.getAuthor(),
                summary.getCreatedAt(),
                summary.isDraft(),
                summary.getReviewStatus());
    }

    static boolean matchesPrRequest(PullRequest pr, int number, String owner, String repo) {
        return pr != null
                && pr.getNumber() == number
                && StringUtils.equalsIgnoreCase(pr.getOwner(), owner)
                && StringUtils.equalsIgnoreCase(pr.getRepo(), repo);
    }

    static boolean isCurrentSelection(
            PullRequest currentPr,
            long currentRevision,
            String expectedKey,
            long expectedRevision) {
        return currentPr != null
                && currentRevision == expectedRevision
                && StringUtils.equals(
                        bridgePrKey(
                                currentPr.getNumber(), currentPr.getOwner(), currentPr.getRepo()),
                        expectedKey);
    }

    static Optional<List<PendingReviewIndex.Entry>> healthyDraftEntries(
            PendingReviewIndex.LoadResult result) {
        return result.healthy() ? Optional.of(result.entries()) : Optional.empty();
    }

    static WebviewPr toWebviewPr(PullRequest pr, boolean hasReviewDraft) {
        return new WebviewPr(
                pr.getNumber(),
                pr.getTitle(),
                pr.getOwner(),
                pr.getRepo(),
                pr.getAuthor(),
                pr.getCreatedAt(),
                pr.getHtmlUrl(),
                pr.isDraft(),
                hasReviewDraft,
                pr.getReviewStatus());
    }

    static PullRequest mergeActivatedPr(PullRequest existing, PullRequest incoming) {
        return incoming.getReviewStatus() == ReviewStatus.UNAVAILABLE
                        && existing.getReviewStatus() != ReviewStatus.UNAVAILABLE
                ? incoming.withReviewStatus(existing.getReviewStatus())
                : incoming;
    }
}
