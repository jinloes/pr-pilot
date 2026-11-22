package com.jinloes.prpilot.ui;

import static com.jinloes.prpilot.ui.WebviewPrSupport.isSamePr;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.jinloes.prpilot.model.PullRequest;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-scoped coordinator managing PR Pilot's main-window {@link PrReviewVirtualFile} editor
 * tab, initial PR list loading, notification handoff, and dynamic tab titles.
 */
public final class PrPilotUiCoordinator implements Disposable {

    interface EditorHost {
        void openReviewEditor(boolean focusEditor);

        void refreshTabPresentation();

        void runOnEdt(Runnable action);
    }

    interface ReviewSurface {
        @Nullable
        PullRequest activePr();

        void activatePr(PullRequest pr, @Nullable String source);

        void reload();
    }

    interface ReviewRegistration {
        void markFocused();

        void close();
    }

    private record PendingActivation(PullRequest pr, @Nullable String source) {}

    private static final class ReviewEntry {
        private final ReviewSurface surface;
        private volatile boolean ready;
        private volatile PullRequest activePr;
        private volatile PendingActivation pendingActivation;

        private ReviewEntry(ReviewSurface surface) {
            this.surface = surface;
        }
    }

    private final EditorHost editorHost;
    private final CopyOnWriteArrayList<ReviewEntry> reviewEntries = new CopyOnWriteArrayList<>();
    private volatile ReviewEntry focusedEntry;
    private volatile PullRequest activePr;
    private volatile PullRequest tabTitlePr;
    private volatile PendingActivation pendingOpenActivation;
    private volatile boolean disposed;

    public PrPilotUiCoordinator(@NotNull Project project) {
        this(new IdeEditorHost(project));
    }

    PrPilotUiCoordinator(@NotNull EditorHost editorHost) {
        this.editorHost = editorHost;
    }

    public static PrPilotUiCoordinator getInstance(@NotNull Project project) {
        return project.getService(PrPilotUiCoordinator.class);
    }

    @Nullable
    PullRequest activePr() {
        return activePr;
    }

    @Nullable
    PullRequest tabTitlePr() {
        return tabTitlePr;
    }

    /** Registers a main-window editor tab's {@link WebviewPanel} and wires its PR lifecycle. */
    ReviewRegistration registerEditorPanel(@NotNull Project project, @NotNull WebviewPanel panel) {
        ReviewEntry entry =
                createReviewEntry(
                        new ReviewSurface() {
                            @Override
                            public @Nullable PullRequest activePr() {
                                return panel.activePR;
                            }

                            @Override
                            public void activatePr(PullRequest pr, @Nullable String source) {
                                panel.activatePr(pr, source);
                            }

                            @Override
                            public void reload() {
                                panel.reload();
                            }
                        });
        panel.setOnPRSelected(pr -> onReviewPrSelected(entry, pr));
        PRToolWindowFactory.wireWebviewLoading(project, panel, () -> onReviewSurfaceReady(entry));
        return handleFor(entry);
    }

    ReviewRegistration registerReviewSurfaceForTest(
            @NotNull ReviewSurface surface, boolean immediatelyReady) {
        ReviewEntry entry = createReviewEntry(surface);
        if (immediatelyReady) {
            onReviewSurfaceReady(entry);
        }
        return handleFor(entry);
    }

    Runnable markSurfaceReadyCallbackForTest(@NotNull ReviewRegistration registration) {
        if (registration instanceof EntryHandle handle) {
            return () -> onReviewSurfaceReady(handle.entry);
        }
        return () -> {};
    }

    void notifyPrSelectedForTest(
            @NotNull ReviewRegistration registration, @NotNull PullRequest pr) {
        if (registration instanceof EntryHandle handle) {
            onReviewPrSelected(handle.entry, pr);
        }
    }

    /** Opens or focuses PR Pilot's main-window editor tab without changing the selected PR. */
    public void openOrFocusEditor() {
        if (disposed) {
            return;
        }
        editorHost.runOnEdt(
                () -> {
                    if (!disposed) {
                        editorHost.openReviewEditor(true);
                    }
                });
    }

    /** Reloads every open PR Pilot editor surface in this project. */
    void reloadEditorSurfaces() {
        for (ReviewEntry entry : reviewEntries) {
            entry.surface.reload();
        }
    }

    /**
     * Opens or focuses PR Pilot's main-window editor tab and activates {@code pr} on it.
     *
     * <p>Re-activating the already-selected PR focuses the editor tab without re-sending {@code
     * activatePR}, preserving in-flight review generation and unsaved review edits.
     */
    public void requestSelectPr(@NotNull PullRequest pr, @Nullable String source) {
        if (disposed) {
            return;
        }
        editorHost.runOnEdt(() -> requestSelectPrOnEdt(pr, source));
    }

    private void requestSelectPrOnEdt(@NotNull PullRequest pr, @Nullable String source) {
        if (disposed) {
            return;
        }
        boolean hadOpenTab = !reviewEntries.isEmpty();
        if (!hadOpenTab && activePr == null) {
            tabTitlePr = pr;
        }
        pendingOpenActivation = new PendingActivation(pr, source);
        editorHost.openReviewEditor(true);

        PendingActivation unconsumed = pendingOpenActivation;
        pendingOpenActivation = null;
        if (unconsumed == null) {
            editorHost.refreshTabPresentation();
            return;
        }

        ReviewEntry target = primaryEntry();
        if (target == null) {
            return;
        }
        if (!target.ready) {
            target.pendingActivation = unconsumed;
            if (activePr == null) {
                tabTitlePr = pr;
                editorHost.refreshTabPresentation();
            }
            return;
        }

        PullRequest current = target.activePr != null ? target.activePr : target.surface.activePr();
        if (target.pendingActivation == null && isSamePr(current, pr)) {
            PullRequest merged = WebviewPrSupport.mergeActivatedPr(current, pr);
            target.activePr = merged;
            activePr = merged;
            tabTitlePr = merged;
            editorHost.refreshTabPresentation();
            return;
        }

        target.surface.activatePr(pr, source);
    }

    private ReviewEntry createReviewEntry(ReviewSurface surface) {
        ReviewEntry entry = new ReviewEntry(surface);
        PendingActivation queuedOpen = pendingOpenActivation;
        pendingOpenActivation = null;
        if (queuedOpen != null) {
            entry.pendingActivation = queuedOpen;
        } else if (activePr != null) {
            entry.pendingActivation = new PendingActivation(activePr, null);
        }
        reviewEntries.add(entry);
        focusedEntry = entry;
        return entry;
    }

    private ReviewRegistration handleFor(ReviewEntry entry) {
        return new EntryHandle(entry);
    }

    private final class EntryHandle implements ReviewRegistration {
        private final ReviewEntry entry;

        private EntryHandle(ReviewEntry entry) {
            this.entry = entry;
        }

        @Override
        public void markFocused() {
            if (disposed || !reviewEntries.contains(entry)) {
                return;
            }
            focusedEntry = entry;
            activePr = entry.activePr;
            tabTitlePr = entry.activePr;
            editorHost.refreshTabPresentation();
        }

        @Override
        public void close() {
            unregisterReviewEntry(entry);
        }
    }

    void onReviewSurfaceReady(ReviewEntry entry) {
        if (disposed || !reviewEntries.contains(entry)) {
            return;
        }
        entry.ready = true;
        PendingActivation pending = entry.pendingActivation;
        entry.pendingActivation = null;
        if (pending != null) {
            entry.surface.activatePr(pending.pr(), pending.source());
        }
    }

    void onReviewPrSelected(ReviewEntry entry, @NotNull PullRequest pr) {
        if (disposed || !reviewEntries.contains(entry)) {
            return;
        }
        entry.activePr = pr;
        if (focusedEntry == null || focusedEntry == entry || reviewEntries.size() == 1) {
            focusedEntry = entry;
            activePr = pr;
            tabTitlePr = pr;
            editorHost.refreshTabPresentation();
        }
    }

    private void unregisterReviewEntry(ReviewEntry entry) {
        reviewEntries.remove(entry);
        if (reviewEntries.isEmpty()) {
            focusedEntry = null;
            activePr = null;
            tabTitlePr = null;
            return;
        }
        if (focusedEntry == entry) {
            ReviewEntry fallback = reviewEntries.get(reviewEntries.size() - 1);
            focusedEntry = fallback;
            activePr = fallback.activePr;
            tabTitlePr = fallback.activePr;
            editorHost.refreshTabPresentation();
        }
    }

    private @Nullable ReviewEntry primaryEntry() {
        ReviewEntry focused = focusedEntry;
        if (focused != null && reviewEntries.contains(focused)) {
            return focused;
        }
        return reviewEntries.isEmpty() ? null : reviewEntries.get(0);
    }

    @Override
    public void dispose() {
        disposed = true;
        reviewEntries.clear();
        focusedEntry = null;
        activePr = null;
        tabTitlePr = null;
        pendingOpenActivation = null;
    }

    private static final class IdeEditorHost implements EditorHost {
        private final Project project;
        private PrReviewVirtualFile reviewFile;

        private IdeEditorHost(Project project) {
            this.project = project;
        }

        private PrReviewVirtualFile file() {
            if (reviewFile == null) {
                reviewFile = new PrReviewVirtualFile();
            }
            return reviewFile;
        }

        @Override
        public void openReviewEditor(boolean focusEditor) {
            if (project.isDisposed()) {
                return;
            }
            FileEditorManager.getInstance(project).openFile(file(), focusEditor);
        }

        @Override
        public void refreshTabPresentation() {
            if (project.isDisposed() || reviewFile == null) {
                return;
            }
            FileEditorManager.getInstance(project).updateFilePresentation(reviewFile);
        }

        @Override
        public void runOnEdt(Runnable action) {
            Application application = ApplicationManager.getApplication();
            if (application == null || application.isDispatchThread()) {
                action.run();
            } else {
                application.invokeLater(action);
            }
        }
    }
}
