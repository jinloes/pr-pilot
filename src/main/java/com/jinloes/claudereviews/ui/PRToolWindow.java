package com.jinloes.claudereviews.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.jinloes.claudereviews.model.LineComment;
import com.jinloes.claudereviews.model.PRReviewRequest;
import com.jinloes.claudereviews.model.PullRequest;
import com.jinloes.claudereviews.model.ReviewResult;
import com.jinloes.claudereviews.services.ClaudeService;
import com.jinloes.claudereviews.services.GitHubService;
import com.jinloes.claudereviews.services.PendingReviewIndex;
import com.jinloes.claudereviews.settings.PluginSettings;
import com.jinloes.claudereviews.settings.PluginSettingsConfigurable;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import javax.swing.Icon;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import org.apache.commons.io.FileUtils;
import org.apache.commons.text.StringEscapeUtils;

public class PRToolWindow {

    private enum State {
        NO_PR, // nothing selected
        LOADING, // checking GitHub for draft
        GENERATING, // review in progress
        NO_DRAFT, // PR selected, no pending GitHub draft
        DRAFT_PRESENT, // pending draft exists (loaded or just saved)
        REVIEW_UNSAVED, // review generated, not yet saved to GitHub
        SUBMITTED // review submitted successfully
    }

    private static final String[] FILTERS = {
        "All in repo", "Assigned to me", "Review Requested", "Created by me"
    };

    private final Project project;
    private final JPanel mainPanel;

    // Left panel – PR list
    private final DefaultListModel<PullRequest> listModel = new DefaultListModel<>();
    private final JBList<PullRequest> prList = new JBList<>(listModel);
    private final JComboBox<String> filterCombo = new JComboBox<>(FILTERS);
    private final JButton refreshButton = new JButton("Refresh");
    private final JTextField searchField = new JTextField(14);

    /** Full unfiltered list backing the search filter. */
    private final java.util.List<PullRequest> allPRs = new ArrayList<>();

    // Browse-repo bar
    private final JComboBox<String> repoCombo = new JComboBox<>();
    private final JButton draftsButton = new JButton("Saved Drafts");
    private volatile boolean loadingRepos = false;
    private boolean showingDrafts = false;

    // Right panel – review
    private final JLabel prHeaderLabel = new JLabel(" ");
    private final JButton generateButton = new JButton("Generate Review");
    private final ReviewPanel reviewPanel = new ReviewPanel();
    private final JLabel statusLabel = new JBLabel(" ");

    // Summary panel (right-side sidebar, always visible when a review is loaded)
    private final JEditorPane summaryPane = new JEditorPane("text/html", "");
    private final JBScrollPane summaryScroll = new JBScrollPane(summaryPane);
    private JSplitPane diffSummarySplit;

    // Comment navigation
    private final JButton prevCommentButton =
            iconButton(AllIcons.Actions.MoveUp, "Previous comment");
    private final JButton nextCommentButton = iconButton(AllIcons.Actions.MoveDown, "Next comment");
    private final JLabel commentCountLabel = new JBLabel("");
    private final JButton openInBrowserButton =
            iconButton(AllIcons.Ide.External_link_arrow, "Open PR in browser");

    // Draft / submit controls
    private final JButton saveDraftButton = new JButton("Save to GitHub");
    private final JButton submitButton = new JButton("Submit \u25b6"); // ▶
    private final JButton cancelButton = new JButton("Cancel");
    private String pendingReviewId = null;

    // Chat toggle
    private JSplitPane reviewChatSplit;
    private boolean chatVisible = true;
    private double lastChatDividerRatio = 0.65;

    /** Guard to prevent recursive list-selection events when restoring a prior selection. */
    private boolean settingSelection = false;

    private ReviewResult lastResult;
    private PullRequest lastPR;
    private volatile String prefetchedDiff = null;
    private volatile PullRequest prefetchedPR = null;

    private final GitHubService githubService = GitHubService.getInstance();
    private final ClaudeService claudeService = new ClaudeService();
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();
    private final ChatPanel chatPanel = new ChatPanel(claudeService, reviewPanel::getSelectedText);

    public PRToolWindow(Project project) {
        this.project = project;

        mainPanel = buildUI();

        // hasToken() shells out to `gh auth token` \u2014 run off the EDT to avoid freezing the UI.
        runInBackground(
                () -> {
                    boolean signedIn = hasToken();
                    runOnEdt(
                            () -> {
                                if (signedIn) {
                                    loadStarredRepos();
                                } else {
                                    setStatus(
                                            "Sign in via Settings \u203a Tools \u203a Claude PR"
                                                    + " Reviews");
                                }
                            });
                });
    }

    public JPanel getContent() {
        return mainPanel;
    }

    // ---------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout());

        // ── Top toolbar ──────────────────────────────────────────────
        JPanel toolbarLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbarLeft.add(new JBLabel("Filter:"));
        toolbarLeft.add(filterCombo);
        toolbarLeft.add(new JSeparator(SwingConstants.VERTICAL));
        repoCombo.setEditable(true);
        repoCombo.setPrototypeDisplayValue("owner/repository-name-xx");
        repoCombo.setToolTipText("Select a starred repo or type owner/repo to filter");
        toolbarLeft.add(repoCombo);
        toolbarLeft.add(refreshButton);
        toolbarLeft.add(new JSeparator(SwingConstants.VERTICAL));
        draftsButton.addActionListener(
                e -> {
                    if (showingDrafts) loadStarredRepos();
                    else loadDraftPRs();
                });
        toolbarLeft.add(draftsButton);
        searchField.putClientProperty("JTextField.placeholderText", "Search PRs…");
        searchField.setToolTipText("Filters already-loaded PRs — not a GitHub search");
        toolbarLeft.add(new JSeparator(SwingConstants.VERTICAL));
        toolbarLeft.add(new JBLabel("Search:"));
        toolbarLeft.add(searchField);
        repoCombo.addActionListener(
                e -> {
                    if (!loadingRepos) refreshPRs();
                });
        searchField
                .getDocument()
                .addDocumentListener(
                        new javax.swing.event.DocumentListener() {
                            @Override
                            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                                applySearch();
                            }

                            @Override
                            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                                applySearch();
                            }

                            @Override
                            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                                applySearch();
                            }
                        });

        JButton settingsButton = new JButton("\u2699 Settings");
        settingsButton.addActionListener(
                e ->
                        ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, PluginSettingsConfigurable.class));
        JPanel toolbarRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        toolbarRight.add(settingsButton);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(toolbarLeft, BorderLayout.CENTER);
        toolbar.add(toolbarRight, BorderLayout.EAST);

        // ── Left: PR list ─────────────────────────────────────────────
        prList.setCellRenderer(new PRCellRenderer());
        prList.getEmptyText().setText("No pull requests found.");
        prList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        prList.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting()) onPRSelected(prList.getSelectedValue());
                });
        prList.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                        maybeShowDraftContextMenu(e);
                    }

                    @Override
                    public void mouseReleased(java.awt.event.MouseEvent e) {
                        maybeShowDraftContextMenu(e);
                    }
                });
        JBScrollPane listScroll = new JBScrollPane(prList);
        listScroll.setPreferredSize(new Dimension(320, 0));

        // ── Right: review panel ───────────────────────────────────────
        prHeaderLabel.setBorder(new EmptyBorder(4, 6, 4, 6));
        prHeaderLabel.setFont(prHeaderLabel.getFont().deriveFont(Font.BOLD));

        // Comment navigation lives in the PR header bar (top-right)
        prevCommentButton.setEnabled(false);
        nextCommentButton.setEnabled(false);
        openInBrowserButton.setEnabled(false);
        commentCountLabel.setForeground(Color.GRAY);
        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        navPanel.add(prevCommentButton);
        navPanel.add(commentCountLabel);
        navPanel.add(nextCommentButton);
        navPanel.add(new JSeparator(SwingConstants.VERTICAL));
        navPanel.add(openInBrowserButton);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(prHeaderLabel, BorderLayout.CENTER);
        northPanel.add(navPanel, BorderLayout.EAST);

        JBScrollPane reviewScroll = new JBScrollPane(reviewPanel);
        reviewScroll.setBackground(ThemeColors.BG);
        reviewScroll.getViewport().setBackground(ThemeColors.BG);
        reviewScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Workflow strip: Generate Review | Save Draft | Submit ▶  [status fills remaining space]
        submitButton.setToolTipText("Submit the pending draft review to GitHub");
        submitButton.setEnabled(false);
        saveDraftButton.setEnabled(false);
        cancelButton.setVisible(false);
        cancelButton.setToolTipText("Cancel the in-progress review");
        JPanel controlButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controlButtons.add(generateButton);
        controlButtons.add(cancelButton);
        controlButtons.add(saveDraftButton);
        controlButtons.add(submitButton);
        JPanel reviewControls = new JPanel(new BorderLayout());
        reviewControls.add(controlButtons, BorderLayout.WEST);
        reviewControls.add(statusLabel, BorderLayout.CENTER);

        JPanel reviewPane = new JPanel(new BorderLayout());
        reviewPane.add(reviewScroll, BorderLayout.CENTER);
        reviewPane.add(reviewControls, BorderLayout.SOUTH);

        chatPanel.setMinimumSize(new Dimension(0, 28));
        reviewChatSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, reviewPane, chatPanel);
        reviewChatSplit.setResizeWeight(0.65);
        reviewChatSplit.setDividerSize(5);

        // ── Summary sidebar (right of diff) ───────────────────────────
        summaryPane.setEditable(false);
        summaryPane.setBackground(ThemeColors.BG_SUBTLE);
        summaryPane.setBorder(new EmptyBorder(8, 12, 8, 12));
        summaryPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        summaryScroll.setBackground(ThemeColors.BG_SUBTLE);
        summaryScroll.getViewport().setBackground(ThemeColors.BG_SUBTLE);
        summaryScroll.setBorder(null);
        summaryPane.addHyperlinkListener(
                e -> {
                    if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
                    String href = e.getDescription();
                    if (href != null && href.startsWith("comment:")) {
                        try {
                            int idx = Integer.parseInt(href.substring("comment:".length()));
                            if (lastResult != null && idx < lastResult.getLineComments().size()) {
                                reviewPanel.scrollToComment(lastResult.getLineComments().get(idx));
                                updateCommentNav();
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                });

        JLabel summaryHeader = new JLabel("Summary");
        summaryHeader.setFont(summaryHeader.getFont().deriveFont(Font.BOLD, 11f));
        summaryHeader.setForeground(ThemeColors.FG_MUTED);
        summaryHeader.setBorder(new EmptyBorder(6, 10, 4, 10));
        summaryHeader.setBackground(ThemeColors.BG_SUBTLE);
        summaryHeader.setOpaque(true);

        JPanel summaryPanel = new JPanel(new BorderLayout());
        summaryPanel.setBackground(ThemeColors.BG_SUBTLE);
        summaryPanel.add(summaryHeader, BorderLayout.NORTH);
        summaryPanel.add(summaryScroll, BorderLayout.CENTER);
        summaryPanel.setPreferredSize(new Dimension(280, 0));
        showSummaryPlaceholder();

        diffSummarySplit =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reviewChatSplit, summaryPanel);
        diffSummarySplit.setResizeWeight(1.0); // diff takes all extra space on resize
        diffSummarySplit.setDividerSize(5);
        // Collapse the summary sidebar by default — it expands when a review is generated
        SwingUtilities.invokeLater(() -> diffSummarySplit.setDividerLocation(1.0d));

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(northPanel, BorderLayout.NORTH);
        rightPanel.add(diffSummarySplit, BorderLayout.CENTER);

        // ── Split pane ────────────────────────────────────────────────
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        split.setDividerLocation(320);
        split.setResizeWeight(0.0);

        root.add(toolbar, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);

        // ── Wire listeners ────────────────────────────────────────────
        refreshButton.addActionListener(e -> loadStarredRepos());
        filterCombo.addActionListener(e -> refreshPRs());
        generateButton.addActionListener(e -> generateReview());
        chatPanel.setOnToggle(this::toggleChat);
        reviewPanel.setOnCommentRemoved(
                () -> {
                    updateCommentNav();
                    autoSaveDraft();
                });
        reviewPanel.setOnAskClaude(
                (ctx, q) -> {
                    ensureChatVisible();
                    chatPanel.askFocused(ctx, q, null);
                });
        saveDraftButton.addActionListener(e -> saveDraft());
        submitButton.addActionListener(e -> submitDraftReview());
        cancelButton.addActionListener(e -> cancelReview());
        openInBrowserButton.addActionListener(e -> openCurrentPRInBrowser());
        prevCommentButton.addActionListener(
                e -> {
                    reviewPanel.prevComment();
                    updateCommentNav();
                });
        nextCommentButton.addActionListener(
                e -> {
                    reviewPanel.nextComment();
                    updateCommentNav();
                });

        return root;
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    private void refreshPRs() {
        showingDrafts = false;
        draftsButton.setText("Saved Drafts");
        filterCombo.setEnabled(true);
        repoCombo.setEnabled(!loadingRepos);
        if (!hasToken()) {
            promptSettings();
            return;
        }

        // Determine whether a specific repo is selected; fall back to the first starred repo.
        Object sel = repoCombo.getEditor().getItem();
        String repoInput = sel == null ? "" : sel.toString().trim();
        if (repoInput.isEmpty() && repoCombo.getItemCount() > 0) {
            repoInput = repoCombo.getItemAt(0);
        }
        String[] repoParts = repoInput.split("/");
        boolean hasRepo =
                repoParts.length == 2 && !repoParts[0].isBlank() && !repoParts[1].isBlank();

        String filter = Objects.toString(filterCombo.getSelectedItem(), "All in repo");

        prList.getEmptyText().setText("Loading\u2026");
        setStatus("Loading PRs\u2026");
        refreshButton.setEnabled(false);
        String token = getToken();

        // Search API for all filters — draft:false eliminates draft PRs server-side.
        String repoClause = hasRepo ? " repo:" + repoInput : "";
        String query =
                switch (filter) {
                    case "Assigned to me" -> "is:pr is:open draft:false assignee:@me" + repoClause;
                    case "Review Requested" ->
                            "is:pr is:open draft:false review-requested:@me" + repoClause;
                    case "Created by me" -> "is:pr is:open draft:false author:@me" + repoClause;
                    default -> "is:pr is:open draft:false" + repoClause;
                };
        runInBackground(
                () -> {
                    try {
                        List<PullRequest> prs = githubService.searchPRs(token, query);
                        prs.sort(Comparator.comparing(PullRequest::getCreatedAt).reversed());
                        runOnEdt(
                                () -> {
                                    allPRs.clear();
                                    allPRs.addAll(prs);
                                    applySearch();
                                    prList.getEmptyText()
                                            .setText("No pull requests found for this filter.");
                                    setStatus(
                                            prs.isEmpty()
                                                    ? "No PRs found."
                                                    : prs.size() + " PR(s) loaded.");
                                    refreshButton.setEnabled(true);
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    prList.getEmptyText()
                                            .setText("No pull requests found for this filter.");
                                    setStatus("Error: " + friendlyError(ex));
                                    refreshButton.setEnabled(true);
                                });
                    }
                });
    }

    private void loadStarredRepos() {
        loadingRepos = true;
        repoCombo.removeAllItems();
        repoCombo.addItem("Loading starred repos…");
        repoCombo.setEnabled(false);
        String token = getToken();
        String basePath = project.getBasePath();
        runInBackground(
                () -> {
                    String detectedRepo = detectCurrentRepo(basePath);
                    List<String> starred;
                    try {
                        starred = githubService.getStarredRepos(token);
                    } catch (Exception ignored) {
                        // Non-fatal: fall back to detected repo only
                        starred = List.of();
                    }
                    final List<String> finalStarred = starred;
                    runOnEdt(
                            () -> {
                                repoCombo.removeAllItems();
                                // Prepend detected repo so the current project is always available
                                if (detectedRepo != null && !finalStarred.contains(detectedRepo)) {
                                    repoCombo.addItem(detectedRepo);
                                }
                                finalStarred.forEach(repoCombo::addItem);
                                if (detectedRepo != null) {
                                    repoCombo.setSelectedItem(detectedRepo);
                                }
                                repoCombo.setEnabled(true);
                                loadingRepos = false;
                                refreshPRs();
                            });
                });
    }

    private void onPRSelected(PullRequest pr) {
        if (pr == null) return;
        if (settingSelection) return;

        // Warn before discarding an unsaved (not yet pushed to GitHub) review.
        if (lastResult != null && pendingReviewId == null && lastPR != null && lastPR != pr) {
            int choice =
                    JOptionPane.showConfirmDialog(
                            mainPanel,
                            "You have an unsaved review for PR #"
                                    + lastPR.getNumber()
                                    + ". Discard it?",
                            "Unsaved Review",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                settingSelection = true;
                prList.setSelectedValue(lastPR, false);
                settingSelection = false;
                return;
            }
        }

        // Collapse stale chat history when switching PRs.
        if (chatVisible) toggleChat();

        prefetchedDiff = null;
        prefetchedPR = null;
        prHeaderLabel.setText(prHeaderHtml(pr));
        prHeaderLabel.setToolTipText(pr.getTitle());
        lastResult = null;
        lastPR = pr;
        pendingReviewId = null;
        showSummaryPlaceholder();
        reviewPanel.showPlaceholder("Checking GitHub for a pending draft review\u2026");
        applyState(State.LOADING);
        prevCommentButton.setEnabled(false);
        nextCommentButton.setEnabled(false);
        commentCountLabel.setText("");
        chatPanel.clearContext();
        reviewPanel.clearSelection();

        if (!hasToken()) {
            applyState(State.NO_DRAFT);
            reviewPanel.showPlaceholder(
                    "Click \u201cGenerate Review\u201d to analyse this PR with Claude.");
            return;
        }

        loadDraftFromGitHub(pr);
    }

    private void generateReview() {
        PullRequest pr = prList.getSelectedValue();
        if (pr == null) {
            setStatus("Select a PR first.");
            return;
        }
        if (!hasToken()) {
            promptSettings();
            return;
        }

        ReviewResult priorResult = lastResult; // capture for regenerate context
        String priorPendingId = pendingReviewId;
        lastResult = null;
        showSummaryPlaceholder();
        applyState(State.GENERATING);
        reviewPanel.showPlaceholder("Fetching diff\u2026");
        setStatus("Fetching diff\u2026");

        String token = getToken();
        runInBackground(
                () -> {
                    try {
                        String cachedDiff = prefetchedDiff;
                        PullRequest cachedPR = prefetchedPR;
                        String diff;
                        if (cachedPR == pr && cachedDiff != null) {
                            diff = cachedDiff;
                            prefetchedDiff = null;
                            prefetchedPR = null;
                        } else {
                            diff =
                                    githubService.getPRDiff(
                                            token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                        }
                        boolean truncated = diff.contains("[... diff truncated at 80 KB ...]");
                        final String truncationMsg = truncated ? buildTruncationStatus(diff) : null;
                        String projectConventions = readProjectConventions();
                        String priorReviewSummary =
                                priorResult != null ? formatPriorReview(priorResult) : null;
                        String existingReviews = "";
                        try {
                            existingReviews =
                                    githubService.getExistingReviewsSummary(
                                            token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                        } catch (Exception ignored) {
                            // Non-fatal: review generation proceeds without existing review context
                        }
                        final String finalDiff = diff;
                        final String finalExistingReviews = existingReviews;
                        runOnEdt(
                                () -> {
                                    if (truncationMsg != null) {
                                        setStatus(truncationMsg + " Generating\u2026");
                                    } else if (!finalExistingReviews.isBlank()) {
                                        long reviewCount =
                                                finalExistingReviews
                                                        .lines()
                                                        .filter(l -> l.startsWith("Review by "))
                                                        .count();
                                        setStatus(
                                                reviewCount
                                                        + " existing review(s) loaded as"
                                                        + " context\u2026");
                                    }
                                    reviewPanel.showStatusLog(
                                            "Generating review with Claude\u2026");
                                    claudeService.reviewPR(
                                            new PRReviewRequest(
                                                    pr,
                                                    finalDiff,
                                                    "",
                                                    projectConventions,
                                                    priorReviewSummary,
                                                    finalExistingReviews),
                                            statusMsg -> {
                                                if (truncationMsg == null) setStatus(statusMsg);
                                                reviewPanel.updateStatus(statusMsg);
                                            },
                                            result -> {
                                                lastResult = result;
                                                lastPR = pr;
                                                reviewPanel.showReview(result, finalDiff);
                                                showSummary(result);
                                                chatPanel.setProjectConventions(projectConventions);
                                                chatPanel.setContext(pr, result);
                                                pendingReviewId =
                                                        null; // new review, not yet saved to GitHub
                                                applyState(State.REVIEW_UNSAVED);
                                                updateCommentNav();
                                                updateGenerateButtonText();
                                                setStatus(
                                                        priorPendingId != null
                                                                ? "Review regenerated \u2014"
                                                                        + " click \u201cSave to"
                                                                        + " GitHub\u201d to replace"
                                                                        + " the existing draft."
                                                                : "Review complete. Click"
                                                                        + " \u201cSave to"
                                                                        + " GitHub\u201d to push"
                                                                        + " to GitHub.");
                                            },
                                            err -> {
                                                reviewPanel.showError(err);
                                                setStatus("Error: " + err);
                                                applyState(State.NO_DRAFT);
                                                updateGenerateButtonText();
                                            });
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    reviewPanel.showError(friendlyError(ex));
                                    setStatus("Failed to fetch diff: " + friendlyError(ex));
                                    applyState(State.NO_DRAFT);
                                    updateGenerateButtonText();
                                });
                    }
                });
    }

    // ---------------------------------------------------------------
    // Draft helpers
    // ---------------------------------------------------------------

    private void autoSaveDraft() {
        if (lastResult == null || lastPR == null || pendingReviewId == null) return;
        String token = getToken();
        PullRequest pr = lastPR;
        ReviewResult result = lastResult;
        runInBackground(
                () -> {
                    try {
                        githubService.saveDraftReview(
                                token, pr.getOwner(), pr.getRepo(), pr.getNumber(), result);
                    } catch (Exception ignored) {
                        // silent auto-save — user can manually save if needed
                    }
                });
    }

    private void ensureChatVisible() {
        if (!chatVisible) {
            toggleChat();
            chatPanel.setCollapsed(false);
        }
    }

    private void toggleChat() {
        if (chatVisible) {
            // Save current ratio before collapsing
            int total = reviewChatSplit.getHeight() - reviewChatSplit.getDividerSize();
            if (total > 0)
                lastChatDividerRatio = (double) reviewChatSplit.getDividerLocation() / total;
            int collapsed = reviewChatSplit.getHeight() - 28 - reviewChatSplit.getDividerSize();
            reviewChatSplit.setDividerLocation(Math.max(0, collapsed));
        } else {
            reviewChatSplit.setDividerLocation(lastChatDividerRatio);
        }
        chatVisible = !chatVisible;
        chatPanel.setCollapsed(!chatVisible);
    }

    private void loadDraftFromGitHub(PullRequest pr) {
        generateButton.setEnabled(false);
        String token = getToken();
        runInBackground(
                () -> {
                    try {
                        boolean merged =
                                githubService.isPRMerged(
                                        token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                        GitHubService.PendingReview pending =
                                githubService.loadDraftReview(
                                        token, pr.getOwner(), pr.getRepo(), pr.getNumber());

                        // Merged PR: always clean local index; also delete any GitHub draft
                        boolean deletedDraft = false;
                        if (merged) {
                            pendingIndex.remove(pr.getOwner(), pr.getRepo(), pr.getNumber());
                            if (pending != null) {
                                try {
                                    githubService.deleteDraftReview(
                                            token,
                                            pr.getOwner(),
                                            pr.getRepo(),
                                            pr.getNumber(),
                                            pending.id());
                                    deletedDraft = true;
                                } catch (Exception ignored) {
                                }
                                pending = null;
                            }
                        }

                        final GitHubService.PendingReview finalPending = pending;
                        final String mergedStatus =
                                merged
                                        ? ("PR is merged."
                                                + (deletedDraft ? " Stale draft removed." : ""))
                                        : null;

                        if (merged) {
                            runOnEdt(
                                    () -> {
                                        applyState(State.NO_DRAFT);
                                        reviewPanel.showPlaceholder(
                                                "PR #%d is merged \u2014 no action needed."
                                                        .formatted(pr.getNumber()));
                                        setStatus(mergedStatus);
                                    });
                            return;
                        }

                        // Always prefetch diff \u2014 used immediately if a draft is found, or
                        // stored
                        // so generateReview() can skip the network round-trip when the user clicks.
                        String diff = null;
                        try {
                            diff =
                                    githubService.getPRDiff(
                                            token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                        } catch (Exception ignored) {
                            // Non-fatal: generateReview() will re-fetch if needed.
                        }

                        final String finalDiff = diff;

                        if (finalPending != null) {
                            String conventions = readProjectConventions();
                            // Check if the PR has new commits since the draft was saved
                            String currentSha = "";
                            try {
                                currentSha =
                                        githubService.getPRHeadSha(
                                                token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                            } catch (Exception ignored) {
                            }
                            PendingReviewIndex.Entry localEntry =
                                    pendingIndex.list().stream()
                                            .filter(
                                                    e ->
                                                            e.owner().equals(pr.getOwner())
                                                                    && e.repo().equals(pr.getRepo())
                                                                    && e.number() == pr.getNumber())
                                            .findFirst()
                                            .orElse(null);
                            boolean staleCommits =
                                    localEntry != null
                                            && !localEntry.headSha().isBlank()
                                            && !currentSha.isBlank()
                                            && !currentSha.equals(localEntry.headSha());
                            final boolean finalStaleCommits = staleCommits;
                            runOnEdt(
                                    () -> {
                                        prefetchedDiff = finalDiff;
                                        prefetchedPR = pr;
                                        pendingReviewId = finalPending.id();
                                        lastResult = finalPending.result();
                                        lastPR = pr;
                                        reviewPanel.showReview(
                                                finalPending.result(),
                                                finalDiff != null ? finalDiff : "");
                                        showSummary(finalPending.result());
                                        chatPanel.setProjectConventions(conventions);
                                        chatPanel.setContext(pr, finalPending.result());
                                        applyState(State.DRAFT_PRESENT);
                                        updateCommentNav();
                                        prHeaderLabel.setText(prHeaderHtml(pr, true));
                                        setStatus(
                                                finalStaleCommits
                                                        ? "⚠ Draft loaded — new commits"
                                                                + " have been pushed since this"
                                                                + " review was saved."
                                                                + " Consider regenerating."
                                                        : "Loaded pending draft review from"
                                                                + " GitHub.");
                                    });
                        } else {
                            runOnEdt(
                                    () -> {
                                        prefetchedDiff = finalDiff;
                                        prefetchedPR = pr;
                                        applyState(State.NO_DRAFT);
                                        reviewPanel.showPlaceholder(
                                                "No pending draft found. Click \u201cGenerate Review\u201d to analyse this PR.");
                                        setStatus("");
                                    });
                        }
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    applyState(State.NO_DRAFT);
                                    reviewPanel.showPlaceholder(
                                            "Click \u201cGenerate Review\u201d to analyse this PR with Claude.");
                                    setStatus("Could not load draft: " + friendlyError(ex));
                                });
                    }
                });
    }

    private void loadDraftPRs() {
        showingDrafts = true;
        draftsButton.setText("← Back to PRs");
        filterCombo.setEnabled(false);
        repoCombo.setEnabled(false);
        setStatus("Loading saved drafts\u2026");
        refreshButton.setEnabled(false);
        List<PendingReviewIndex.Entry> entries = pendingIndex.list();
        if (entries.isEmpty()) {
            listModel.clear();
            setStatus("No saved drafts.");
            refreshButton.setEnabled(true);
            return;
        }
        String token = getToken();
        runInBackground(
                () -> {
                    int removed = 0;
                    for (PendingReviewIndex.Entry e : entries) {
                        try {
                            boolean merged =
                                    githubService.isPRMerged(
                                            token, e.owner(), e.repo(), e.number());
                            if (merged) {
                                try {
                                    GitHubService.PendingReview pending =
                                            githubService.loadDraftReview(
                                                    token, e.owner(), e.repo(), e.number());
                                    if (pending != null) {
                                        githubService.deleteDraftReview(
                                                token,
                                                e.owner(),
                                                e.repo(),
                                                e.number(),
                                                pending.id());
                                    }
                                } catch (Exception ignored) {
                                }
                                pendingIndex.remove(e.owner(), e.repo(), e.number());
                                removed++;
                            }
                        } catch (Exception ignored) {
                            // Cannot check merged status — keep the entry
                        }
                    }
                    final int removedCount = removed;
                    List<PullRequest> prs =
                            pendingIndex.list().stream()
                                    .map(
                                            e ->
                                                    new PullRequest(
                                                            e.title(),
                                                            "",
                                                            e.owner(),
                                                            e.repo(),
                                                            e.number(),
                                                            "",
                                                            "",
                                                            e.savedAt()))
                                    .toList();
                    runOnEdt(
                            () -> {
                                listModel.clear();
                                prs.forEach(listModel::addElement);
                                String status =
                                        prs.isEmpty()
                                                ? "No saved drafts."
                                                : prs.size()
                                                        + " saved draft(s)."
                                                        + (removedCount > 0
                                                                ? " "
                                                                        + removedCount
                                                                        + " merged PR draft(s)"
                                                                        + " removed."
                                                                : "");
                                setStatus(status);
                                refreshButton.setEnabled(true);
                            });
                });
    }

    private void maybeShowDraftContextMenu(java.awt.event.MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int index = prList.locationToIndex(e.getPoint());
        if (index < 0) return;
        prList.setSelectedIndex(index);
        PullRequest pr = listModel.getElementAt(index);

        boolean hasDraft =
                pendingIndex.list().stream()
                        .anyMatch(
                                en ->
                                        en.owner().equals(pr.getOwner())
                                                && en.repo().equals(pr.getRepo())
                                                && en.number() == pr.getNumber());
        if (!hasDraft) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("Delete Draft");
        deleteItem.addActionListener(
                ae -> {
                    int confirm =
                            JOptionPane.showConfirmDialog(
                                    mainPanel,
                                    "Delete the saved draft for PR #" + pr.getNumber() + "?",
                                    "Delete Draft",
                                    JOptionPane.YES_NO_OPTION);
                    if (confirm != JOptionPane.YES_OPTION) return;
                    pendingIndex.remove(pr.getOwner(), pr.getRepo(), pr.getNumber());
                    if (showingDrafts) {
                        listModel.removeElement(pr);
                    }
                    if (lastPR != null
                            && lastPR.getOwner().equals(pr.getOwner())
                            && lastPR.getRepo().equals(pr.getRepo())
                            && lastPR.getNumber() == pr.getNumber()) {
                        reviewPanel.showPlaceholder("Draft deleted.");
                        lastPR = null;
                        lastResult = null;
                        pendingReviewId = null;
                        applyState(State.NO_PR);
                    }
                    setStatus("Deleting draft from GitHub…");
                    String token = getToken();
                    runInBackground(
                            () -> {
                                try {
                                    String reviewId =
                                            githubService.getPendingReviewId(
                                                    token,
                                                    pr.getOwner(),
                                                    pr.getRepo(),
                                                    pr.getNumber());
                                    if (reviewId != null) {
                                        githubService.deleteDraftReview(
                                                token,
                                                pr.getOwner(),
                                                pr.getRepo(),
                                                pr.getNumber(),
                                                reviewId);
                                    }
                                    runOnEdt(() -> setStatus("Draft deleted."));
                                } catch (Exception ignored) {
                                    // Non-fatal: local index is already cleaned up
                                    runOnEdt(() -> setStatus("Draft removed from local index."));
                                }
                            });
                });
        menu.add(deleteItem);
        menu.show(prList, e.getX(), e.getY());
    }

    private void openCurrentPRInBrowser() {
        PullRequest pr = lastPR;
        if (pr == null) return;
        String url =
                !pr.getHtmlUrl().isBlank()
                        ? pr.getHtmlUrl()
                        : PluginSettings.getInstance().getGithubBaseUrl()
                                + "/"
                                + pr.getOwner()
                                + "/"
                                + pr.getRepo()
                                + "/pull/"
                                + pr.getNumber();
        BrowserUtil.browse(url);
    }

    private void saveDraft() {
        if (lastResult == null || lastPR == null) return;
        setStatus("Saving draft to GitHub\u2026");
        saveDraftButton.setEnabled(false);
        String token = getToken();
        PullRequest pr = lastPR;
        ReviewResult result = lastResult;
        runInBackground(
                () -> {
                    try {
                        GitHubService.SaveDraftResult saved =
                                githubService.saveDraftReview(
                                        token, pr.getOwner(), pr.getRepo(), pr.getNumber(), result);
                        String headSha = "";
                        try {
                            headSha =
                                    githubService.getPRHeadSha(
                                            token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                        } catch (Exception ignored) {
                        }
                        pendingIndex.add(
                                pr.getOwner(),
                                pr.getRepo(),
                                pr.getNumber(),
                                pr.getTitle(),
                                headSha);
                        runOnEdt(
                                () -> {
                                    pendingReviewId = saved.reviewId();
                                    applyState(State.DRAFT_PRESENT);
                                    setStatus(
                                            saved.commentsDropped()
                                                    ? "Draft saved — some inline comments"
                                                            + " had invalid line numbers and were"
                                                            + " not attached."
                                                    : "Draft saved to GitHub.");
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    applyState(State.REVIEW_UNSAVED);
                                    setStatus("Save failed: " + friendlyError(ex));
                                });
                    }
                });
    }

    private void submitDraftReview() {
        if (pendingReviewId == null || lastPR == null || lastResult == null) return;
        String[] events = {"APPROVE", "REQUEST_CHANGES", "COMMENT"};
        String[] labels = {"Approve", "Request Changes", "Comment"};
        int def =
                switch (lastResult.getVerdict()) {
                    case "APPROVE" -> 0;
                    case "REQUEST_CHANGES" -> 1;
                    default -> 2;
                };

        // Build a custom dialog with verdict buttons and an optional comment field.
        JTextArea commentArea = new JTextArea(4, 40);
        commentArea.setLineWrap(true);
        commentArea.setWrapStyleWord(true);
        commentArea.setForeground(Color.GRAY);
        commentArea.setText("Add an optional comment to the review…");
        commentArea.addFocusListener(
                new java.awt.event.FocusAdapter() {
                    @Override
                    public void focusGained(java.awt.event.FocusEvent e) {
                        if (commentArea.getForeground().equals(Color.GRAY)) {
                            commentArea.setText("");
                            commentArea.setForeground(UIManager.getColor("TextArea.foreground"));
                        }
                    }
                });
        JScrollPane commentScroll = new JScrollPane(commentArea);
        commentScroll.setBorder(BorderFactory.createTitledBorder("Comment (optional)"));

        JPanel dialogContent = new JPanel(new BorderLayout(0, 8));
        dialogContent.add(
                new JLabel(
                        "<html>Submit review for PR #%d — <i>%s</i>?</html>"
                                .formatted(lastPR.getNumber(), lastPR.getTitle())),
                BorderLayout.NORTH);
        dialogContent.add(commentScroll, BorderLayout.CENTER);

        int choice =
                JOptionPane.showOptionDialog(
                        mainPanel,
                        dialogContent,
                        "Submit Review to GitHub",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        labels,
                        labels[def]);
        if (choice < 0) return;

        String event = events[choice];
        String commentBody =
                commentArea.getForeground().equals(Color.GRAY) ? "" : commentArea.getText().strip();
        String reviewId = pendingReviewId;
        submitButton.setEnabled(false);
        saveDraftButton.setEnabled(false); // prevent re-save while submit is in flight
        setStatus("Submitting review\u2026");
        String token = getToken();
        PullRequest pr = lastPR;
        runInBackground(
                () -> {
                    try {
                        githubService.submitDraftReview(
                                token,
                                pr.getOwner(),
                                pr.getRepo(),
                                pr.getNumber(),
                                reviewId,
                                event,
                                commentBody);
                        pendingIndex.remove(pr.getOwner(), pr.getRepo(), pr.getNumber());
                        runOnEdt(
                                () -> {
                                    pendingReviewId = null;
                                    lastResult = null;
                                    applyState(State.SUBMITTED);
                                    reviewPanel.showPlaceholder(
                                            "Review submitted \u2714 Click \u201cGenerate"
                                                    + " Review\u201d to start a new one.");
                                    showSummaryPlaceholder();
                                    prHeaderLabel.setText(prHeaderHtml(pr));
                                    setStatus("Review submitted to GitHub \u2714");
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    applyState(State.DRAFT_PRESENT);
                                    JOptionPane.showMessageDialog(
                                            mainPanel,
                                            friendlyError(ex),
                                            "Submit Failed",
                                            JOptionPane.ERROR_MESSAGE);
                                    setStatus("Submit failed.");
                                });
                    }
                });
    }

    static String prHeaderHtml(PullRequest pr) {
        return prHeaderHtml(pr, false);
    }

    static String prHeaderHtml(PullRequest pr, boolean draft) {
        String title = pr.getTitle();
        String displayTitle = title.length() > 80 ? title.substring(0, 77) + "\u2026" : title;
        String draftBadge = draft ? " &nbsp;<font color='#e3b341'>[draft]</font>" : "";
        return "<html><b>#%d %s</b> &nbsp;<font color=gray>by %s &nbsp;\u00b7&nbsp; %s/%s%s</font></html>"
                .formatted(
                        pr.getNumber(),
                        esc(displayTitle),
                        pr.getAuthor(),
                        pr.getOwner(),
                        pr.getRepo(),
                        draftBadge);
    }

    private void applyState(State s) {
        boolean generating = s == State.GENERATING;
        generateButton.setEnabled(
                !generating
                        && (s == State.NO_DRAFT
                                || s == State.DRAFT_PRESENT
                                || s == State.REVIEW_UNSAVED
                                || s == State.SUBMITTED));
        cancelButton.setVisible(generating);
        saveDraftButton.setEnabled(
                !generating && (s == State.DRAFT_PRESENT || s == State.REVIEW_UNSAVED));
        submitButton.setEnabled(!generating && s == State.DRAFT_PRESENT);
        openInBrowserButton.setEnabled(s != State.NO_PR);
        // Lock filter/repo combos in drafts mode so the user can't accidentally switch views
        boolean drafts = showingDrafts;
        filterCombo.setEnabled(!drafts && !generating);
        repoCombo.setEnabled(!drafts && !generating && !loadingRepos);
    }

    private void cancelReview() {
        claudeService.cancelCurrentRequest();
        reviewPanel.showPlaceholder("Review cancelled.");
        applyState(State.NO_DRAFT);
        updateGenerateButtonText();
        setStatus("Review cancelled.");
    }

    private void updateGenerateButtonText() {
        generateButton.setText(lastResult != null ? "Regenerate ↺" : "Generate Review");
        generateButton.setToolTipText(
                lastResult != null
                        ? "Generate a new review — prior review included as context"
                        : null);
    }

    static String formatPriorReview(ReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Verdict: ").append(result.getVerdict()).append("\n\n");
        if (!result.getSummary().isBlank()) {
            sb.append("Summary:\n").append(result.getSummary()).append("\n\n");
        }
        if (!result.getLineComments().isEmpty()) {
            sb.append("Comments:\n");
            for (var c : result.getLineComments()) {
                sb.append("- [").append(c.getType().toUpperCase()).append("] ");
                if (!c.getFile().isBlank()) {
                    String file = c.getFile();
                    if (file.startsWith("a/") || file.startsWith("b/")) file = file.substring(2);
                    sb.append(file).append(":").append(c.getLine()).append(" — ");
                }
                sb.append(c.getBody()).append("\n");
            }
        }
        return sb.toString();
    }

    private void applySearch() {
        String query = searchField.getText().trim().toLowerCase();
        listModel.clear();
        if (query.isBlank()) {
            allPRs.forEach(listModel::addElement);
        } else {
            allPRs.stream()
                    .filter(
                            pr ->
                                    pr.getTitle().toLowerCase().contains(query)
                                            || pr.getAuthor().toLowerCase().contains(query)
                                            || (pr.getOwner() + "/" + pr.getRepo())
                                                    .toLowerCase()
                                                    .contains(query))
                    .forEach(listModel::addElement);
        }
    }

    private void updateCommentNav() {
        int total = reviewPanel.getCommentCount();
        int idx = reviewPanel.getCurrentCommentIndex();
        if (total == 0) {
            commentCountLabel.setText("");
        } else {
            // Always show position/total; if no navigation yet (idx < 0), show 1/N
            commentCountLabel.setText((Math.max(idx, 0) + 1) + "/" + total);
        }
        prevCommentButton.setEnabled(total > 0);
        nextCommentButton.setEnabled(total > 0);
        // Keep the comment list in the summary sidebar in sync with dismissed cards.
        if (lastResult != null) showSummary(lastResult);
    }

    static String friendlyError(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) return "Unexpected error — check IDE logs.";
        String low = msg.toLowerCase();
        if (low.contains("401") || low.contains("unauthorized"))
            return "GitHub authentication failed — run `gh auth login` in a terminal.";
        if (low.contains("connection refused")
                || low.contains("failed to connect")
                || low.contains("unknown host"))
            return "Could not reach GitHub — check your connection.";
        if (low.contains("404"))
            return "Not found on GitHub — the PR or repo may have been deleted.";
        String trimmed = msg.length() > 120 ? msg.substring(0, 117) + "…" : msg;
        return trimmed;
    }

    private boolean hasToken() {
        return PluginSettings.getInstance().isSignedIn();
    }

    /** Creates a borderless icon-only button styled like an IntelliJ toolbar action. */
    private static JButton iconButton(Icon icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private String getToken() {
        return PluginSettings.getInstance().getGithubToken();
    }

    private void promptSettings() {
        setStatus("Not signed in to GitHub.");
        ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, PluginSettingsConfigurable.class);
    }

    private void showSummary(ReviewResult result) {
        // Restore sidebar to ~280px if currently collapsed
        if (diffSummarySplit != null
                && diffSummarySplit.getDividerLocation() >= diffSummarySplit.getWidth() - 10) {
            diffSummarySplit.setDividerLocation(Math.max(0, diffSummarySplit.getWidth() - 280));
        }
        summaryPane.setText(
                ChatPanel.buildHtml(
                        result.getSummary(), buildCommentListHtml(result.getLineComments())));
        summaryPane.setCaretPosition(0);
    }

    static String buildCommentListHtml(List<LineComment> comments) {
        if (comments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<hr style='border:0;border-top:1px solid #30363d;margin:8px 0'>")
                .append("<p style='margin:4px 0;color:#8b949e;font-size:10pt'><b>Comments (")
                .append(comments.size())
                .append(")</b></p>");
        for (int i = 0; i < comments.size(); i++) {
            LineComment c = comments.get(i);
            String color = commentTypeColor(c.getType());
            String file = c.getFile();
            if (file.startsWith("a/") || file.startsWith("b/")) file = file.substring(2);
            String locPart =
                    file.isBlank()
                            ? ""
                            : " <font color='#8b949e'>" + esc(file) + ":" + c.getLine() + "</font>";
            String rawBody = c.getBody();
            String bodyText =
                    rawBody.isBlank()
                            ? "<i style='color:#8b949e'>(empty)</i>"
                            : esc(rawBody.length() > 80 ? rawBody.substring(0, 77) + "…" : rawBody);
            sb.append("<p style='margin:2px 0'>")
                    .append("<a href='comment:")
                    .append(i)
                    .append("' style='color:#e6edf3;text-decoration:none'>")
                    .append("<font color='")
                    .append(color)
                    .append("'>[")
                    .append(c.getType().toUpperCase())
                    .append("]</font>")
                    .append(locPart)
                    .append(" ")
                    .append(bodyText)
                    .append("</a></p>");
        }
        return sb.toString();
    }

    private static String commentTypeColor(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "issue" -> "#e3b341";
            case "suggestion" -> "#58a6ff";
            case "praise" -> "#3fb950";
            default -> "#8b949e";
        };
    }

    static String buildTruncationStatus(String diff) {
        List<DiffParser.DiffFile> files = DiffParser.parseDiff(diff);
        if (files.isEmpty()) return "⚠ Diff truncated at 80 KB";
        int n = files.size();
        String lastName = files.get(n - 1).name;
        return "⚠ Diff truncated at 80 KB — %d file(s) parsed; %s may be cut short"
                .formatted(n, lastName);
    }

    private void showSummaryPlaceholder() {
        if (diffSummarySplit != null) {
            SwingUtilities.invokeLater(() -> diffSummarySplit.setDividerLocation(1.0d));
        }
        summaryPane.setText(ChatPanel.buildHtml("*Generate a review to see the summary here.*"));
        summaryPane.setCaretPosition(0);
    }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
    }

    private static void runInBackground(Runnable r) {
        ApplicationManager.getApplication().executeOnPooledThread(r);
    }

    private static void runOnEdt(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    private static String esc(String s) {
        return StringEscapeUtils.escapeHtml4(s);
    }

    /**
     * Reads the first of CLAUDE.md / AGENTS.md / .claude/CLAUDE.md found under the open project
     * root, returning its content or an empty string if none exists.
     */
    String readProjectConventions() {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return "";
        }
        List<String> candidates =
                List.of("CLAUDE.md", "AGENTS.md", ".claude/CLAUDE.md", ".claude/AGENTS.md");
        for (String candidate : candidates) {
            File f = new File(basePath, candidate);
            if (f.isFile()) {
                try {
                    return FileUtils.readFileToString(f, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // non-fatal: fall through to the next candidate
                }
            }
        }
        return "";
    }

    // ---------------------------------------------------------------
    // Repo detection
    // ---------------------------------------------------------------

    /**
     * Walks up from {@code basePath} to find a {@code .git/config} file, then extracts the {@code
     * owner/repo} for the {@code origin} remote. Returns {@code null} if not found. No process is
     * spawned — reads the file directly. Walking up matches git's own behaviour for repos where the
     * IntelliJ project root is a subdirectory of the git root.
     */
    static String detectCurrentRepo(String basePath) {
        if (basePath == null) return null;
        File dir = new File(basePath);
        while (dir != null) {
            File gitConfig = new File(dir, ".git/config");
            if (gitConfig.isFile()) {
                try {
                    List<String> lines = FileUtils.readLines(gitConfig, StandardCharsets.UTF_8);
                    boolean inOriginSection = false;
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.equals("[remote \"origin\"]")) {
                            inOriginSection = true;
                        } else if (inOriginSection && trimmed.startsWith("[")) {
                            break;
                        } else if (inOriginSection && trimmed.startsWith("url")) {
                            int eq = trimmed.indexOf('=');
                            if (eq >= 0) return parseOwnerRepo(trimmed.substring(eq + 1).trim());
                        }
                    }
                } catch (IOException ignored) {
                }
                return null; // found .git/config but no origin remote — stop walking
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * Parses an owner/repo pair from a git remote URL. Handles scp-style SSH ({@code
     * git@github.com:owner/repo.git}), SSH URIs ({@code ssh://git@host:port/owner/repo.git}), and
     * HTTPS ({@code https://github.com/owner/repo.git}) formats.
     */
    static String parseOwnerRepo(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        String url = remoteUrl.strip();
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        // scp-style SSH: git@github.com:owner/repo — NOT ssh:// URIs (those have a port after ':')
        if (url.contains("@")
                && url.contains(":")
                && !url.startsWith("http")
                && !url.startsWith("ssh://")) {
            return ownerRepoFromPath(url.substring(url.lastIndexOf(':') + 1));
        }
        // HTTPS/HTTP/SSH-URI format: https://github.com/owner/repo or
        // ssh://git@host:port/owner/repo
        try {
            String path = new URI(url).getPath();
            if (path != null && path.startsWith("/")) path = path.substring(1);
            return ownerRepoFromPath(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String ownerRepoFromPath(String path) {
        if (path == null || path.isBlank()) return null;
        String[] parts = path.split("/");
        return parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()
                ? parts[0] + "/" + parts[1]
                : null;
    }

    // ---------------------------------------------------------------
    // Cell renderer
    // ---------------------------------------------------------------

    private class PRCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof PullRequest pr) {
                boolean hasDraft =
                        pendingIndex.list().stream()
                                .anyMatch(
                                        e ->
                                                e.owner().equals(pr.getOwner())
                                                        && e.repo().equals(pr.getRepo())
                                                        && e.number() == pr.getNumber());
                String draftBadge = hasDraft ? " 📝" : "";
                String subtitle =
                        pr.getAuthor().isEmpty()
                                ? "%s/%s".formatted(pr.getOwner(), pr.getRepo())
                                : "%s/%s &nbsp;·&nbsp; %s"
                                        .formatted(
                                                pr.getOwner(), pr.getRepo(), esc(pr.getAuthor()));
                setText(
                        "<html><b>#%d</b> %s%s<br><small style='color:gray'>%s</small></html>"
                                .formatted(
                                        pr.getNumber(), esc(pr.getTitle()), draftBadge, subtitle));
            }
            setBorder(new EmptyBorder(4, 6, 4, 6));
            return this;
        }
    }
}
