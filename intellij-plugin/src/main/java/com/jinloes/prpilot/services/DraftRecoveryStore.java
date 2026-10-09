package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/** Token-free local recovery snapshots written before a pending GitHub review is mutated. */
@State(name = "PRPilotDraftRecovery", storages = @Storage("prPilotDraftRecovery.xml"))
public final class DraftRecoveryStore
        implements PersistentStateComponent<DraftRecoveryStore.StoreState> {
    private static final int MAX_SNAPSHOTS = 20;

    public static final class StoreState {
        public Map<String, SnapshotState> snapshots = new LinkedHashMap<>();
    }

    public static final class SnapshotState {
        public String prKey = "";
        public String summary = "";
        public String verdict = "COMMENT";
        public List<CommentState> lineComments = new ArrayList<>();
        public List<CommentState> orphans = new ArrayList<>();
        public long savedAt;
    }

    public static final class CommentState {
        public String file = "";
        public int line;
        public String type = "note";
        public String body = "";
        public String severity = "";
        public String category = "";
        public String confidence = "";
        public String rationale = "";
        public String suggestedChange = "";
    }

    public record Snapshot(
            String prKey, ReviewResult result, List<LineComment> orphans, long savedAt) {}

    private StoreState state = new StoreState();

    public static DraftRecoveryStore getInstance() {
        return ApplicationManager.getApplication().getService(DraftRecoveryStore.class);
    }

    @Override
    public synchronized @NotNull StoreState getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull StoreState value) {
        state = value;
        if (state.snapshots == null) state.snapshots = new LinkedHashMap<>();
    }

    public synchronized void save(
            String prKey, ReviewResult result, List<LineComment> orphanComments) {
        SnapshotState snapshot = new SnapshotState();
        snapshot.prKey = prKey;
        snapshot.summary = result.getSummary();
        snapshot.verdict = result.getVerdict();
        snapshot.lineComments = copyComments(result.getLineComments());
        snapshot.orphans = copyComments(orphanComments);
        snapshot.savedAt = System.currentTimeMillis();
        state.snapshots.put(prKey, snapshot);
        while (state.snapshots.size() > MAX_SNAPSHOTS) {
            String oldest =
                    state.snapshots.entrySet().stream()
                            .min(
                                    Map.Entry.comparingByValue(
                                            java.util.Comparator.comparingLong(
                                                    item -> item.savedAt)))
                            .map(Map.Entry::getKey)
                            .orElse(null);
            if (oldest == null) break;
            state.snapshots.remove(oldest);
        }
    }

    public synchronized Snapshot get(String prKey) {
        SnapshotState snapshot = state.snapshots.get(prKey);
        if (snapshot == null || !prKey.equals(snapshot.prKey)) return null;
        return new Snapshot(
                prKey,
                new ReviewResult(
                        snapshot.summary, snapshot.verdict, restoreComments(snapshot.lineComments)),
                restoreComments(snapshot.orphans),
                snapshot.savedAt);
    }

    public synchronized void clear(String prKey) {
        state.snapshots.remove(prKey);
    }

    private static List<CommentState> copyComments(List<LineComment> comments) {
        if (comments == null) return new ArrayList<>();
        List<CommentState> result = new ArrayList<>();
        for (LineComment comment : comments) {
            CommentState copy = new CommentState();
            copy.file = comment.getFile();
            copy.line = comment.getLine();
            copy.type = comment.getType();
            copy.body = comment.getBody();
            copy.severity = comment.getSeverity();
            copy.category = comment.getCategory();
            copy.confidence = comment.getConfidence();
            copy.rationale = comment.getRationale();
            copy.suggestedChange = comment.getSuggestedChange();
            result.add(copy);
        }
        return result;
    }

    private static List<LineComment> restoreComments(List<CommentState> comments) {
        List<LineComment> result = new ArrayList<>();
        if (comments == null) return result;
        for (CommentState stored : comments) {
            LineComment comment =
                    new LineComment(stored.file, stored.line, stored.type, stored.body);
            comment.setSeverity(stored.severity);
            comment.setCategory(stored.category);
            comment.setConfidence(stored.confidence);
            comment.setRationale(stored.rationale);
            comment.setSuggestedChange(stored.suggestedChange);
            result.add(comment);
        }
        return result;
    }
}
