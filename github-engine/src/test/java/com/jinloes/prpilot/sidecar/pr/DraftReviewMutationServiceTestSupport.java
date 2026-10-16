package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

class DraftReviewMutationServiceTestSupport {
    final ObjectMapper mapper = new ObjectMapper();

    static boolean isReviewList(String path) {
        return path.contains("/reviews?per_page=100&page=");
    }

    DraftReviewMutationService.SaveParams saveParams(
            List<DraftReviewMutationService.CommentInput> comments,
            List<DraftReviewMutationService.CommentInput> orphans) {
        return new DraftReviewMutationService.SaveParams(
                "https://github.com", "acme", "repo", 1, "summary", "APPROVE", comments, orphans);
    }
}
