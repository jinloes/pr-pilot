package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class DraftRecoveryStoreTest {
    @Test
    void storesAndClearsTokenFreeSnapshot() {
        DraftRecoveryStore store = new DraftRecoveryStore();
        LineComment comment = new LineComment("a.java", 3, "issue", "fix");
        ReviewResult review = new ReviewResult("summary", "REQUEST_CHANGES", List.of(comment));

        store.save("acme/repo#1", review, List.of());

        DraftRecoveryStore.Snapshot restored = store.get("acme/repo#1");
        assertThat(restored).isNotNull();
        assertThat(restored.result().getSummary()).isEqualTo("summary");
        assertThat(restored.result().getLineComments())
                .extracting(LineComment::getBody)
                .containsExactly("fix");

        store.clear("acme/repo#1");
        assertThat(store.get("acme/repo#1")).isNull();
    }

    @Test
    void restoredSnapshotDoesNotShareMutableReviewLists() {
        DraftRecoveryStore store = new DraftRecoveryStore();
        ReviewResult review =
                new ReviewResult(
                        "summary",
                        "COMMENT",
                        List.of(new LineComment("a.java", 3, "note", "note")));
        store.save("acme/repo#1", review, List.of());

        store.get("acme/repo#1")
                .result()
                .getLineComments()
                .add(new LineComment("b.java", 4, "note", "other"));

        assertThat(store.get("acme/repo#1").result().getLineComments()).hasSize(1);
    }

    @Test
    void preservesASuggestedChangeAcrossSaveAndRestore() {
        DraftRecoveryStore store = new DraftRecoveryStore();
        LineComment comment = new LineComment("a.java", 3, "issue", "fix");
        comment.setSuggestedChange("  return a;");
        LineComment plain = new LineComment("a.java", 4, "note", "note");
        store.save(
                "acme/repo#1",
                new ReviewResult("summary", "COMMENT", List.of(comment, plain)),
                List.of());

        assertThat(store.get("acme/repo#1").result().getLineComments())
                .extracting(LineComment::getSuggestedChange)
                .containsExactly("  return a;", "");
    }
}
