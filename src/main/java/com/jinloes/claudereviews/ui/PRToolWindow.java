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
import com.jinloes.claudereviews.services.PatternKnowledgeBase;
import com.jinloes.claudereviews.services.PendingReviewIndex;
import com.jinloes.claudereviews.settings.PluginSettings;
import com.jinloes.claudereviews.settings.PluginSettingsConfigurable;
import java.awt.*;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import javax.swing.Icon;
import javax.swing.border.EmptyBorder;
import org.apache.commons.text.StringEscapeUtils;

public class PRToolWindow {

    private static final String[] FILTERS = {
        "Open", "Assigned to me", "Review Requested", "Created by me", "Drafts"
    };

    private final Project project;
    private final JPanel mainPanel;

    // Left panel – PR list
    private final DefaultListModel<PullRequest> listModel = new DefaultListModel<>();
    private final JBList<PullRequest> prList = new JBList<>(listModel);
    private final JComboBox<String> filterCombo = new JComboBox<>(FILTERS);
    private final JButton refreshButton = new JButton("Refresh");

    // Browse-repo bar
    private final JComboBox<String> repoCombo = new JComboBox<>();
    private boolean loadingRepos = false;

    // Right panel – review
    private final JLabel prHeaderLabel = new JLabel(" ");
    private final JButton generateButton = new JButton("Generate Review");
    private final ReviewPanel reviewPanel = new ReviewPanel();
    private final GenerationProgressBar generationProgress = new GenerationProgressBar();
    private final JLabel statusLabel = new JBLabel(" ");

    // Summary panel (right-side sidebar, always visible when a review is loaded)
    private final JEditorPane summaryPane = new JEditorPane("text/html", "");
    private final JBScrollPane summaryScroll = new JBScrollPane(summaryPane);
    private JSplitPane diffSummarySplit;

    // Comment navigation
    private final JButton prevCommentButton =
            iconButton(AllIcons.Actions.PreviousOccurence, "Previous comment");
    private final JButton nextCommentButton =
            iconButton(AllIcons.Actions.NextOccurence, "Next comment");
    private final JLabel commentCountLabel = new JBLabel("");
    private final JButton openInBrowserButton =
            iconButton(AllIcons.Ide.External_link_arrow, "Open PR in browser");

    // Draft / submit controls
    private final JButton saveDraftButton = new JButton("Save Draft");
    private final JButton submitButton = new JButton("Submit \u25b6"); // ▶
    private String pendingReviewId = null;

    // Chat toggle
    private final JButton chatToggleButton = new JButton("Chat \u25be"); // ▾
    private JSplitPane reviewChatSplit;
    private boolean chatVisible = true;
    private double lastChatDividerRatio = 0.65;

    private ReviewResult lastResult;
    private PullRequest lastPR;
    private String lastDiff;

    private final GitHubService githubService = new GitHubService();
    private final ClaudeService claudeService = new ClaudeService();
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();
    private final PatternKnowledgeBase patternKb = new PatternKnowledgeBase();
    private final ChatPanel chatPanel = new ChatPanel(claudeService, reviewPanel::getSelectedText);

    public PRToolWindow(Project project) {
        this.project = project;

        mainPanel = buildUI();

        if (hasToken()) {
            loadStarredRepos(); // refreshPRs() is called once repos finish loading
        } else {
            setStatus("Sign in via Settings \u203a Tools \u203a Claude PR Reviews");
        }
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
        repoCombo.setToolTipText("Select a starred repo or type owner/repo, then press Enter");
        toolbarLeft.add(repoCombo);
        toolbarLeft.add(refreshButton);
        repoCombo.addActionListener(
                e -> {
                    if (!loadingRepos) refreshPRs();
                });
        toolbarLeft.add(new JSeparator(SwingConstants.VERTICAL));
        toolbarLeft.add(generateButton);

        submitButton.setToolTipText("Submit the pending draft review to GitHub");
        submitButton.setEnabled(false);

        chatToggleButton.setToolTipText("Show/hide chat panel");
        chatToggleButton.addActionListener(e -> toggleChat());

        JButton settingsButton = new JButton("\u2699 Settings");
        settingsButton.addActionListener(
                e ->
                        ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, PluginSettingsConfigurable.class));
        JPanel toolbarRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        toolbarRight.add(chatToggleButton);
        toolbarRight.add(submitButton);
        toolbarRight.add(settingsButton);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(toolbarLeft, BorderLayout.CENTER);
        toolbar.add(toolbarRight, BorderLayout.EAST);

        // ── Left: PR list ─────────────────────────────────────────────
        prList.setCellRenderer(new PRCellRenderer());
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
        northPanel.add(generationProgress, BorderLayout.SOUTH);

        JBScrollPane reviewScroll = new JBScrollPane(reviewPanel);
        reviewScroll.setBackground(ReviewPanel.BG);
        reviewScroll.getViewport().setBackground(ReviewPanel.BG);
        reviewScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel reviewControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        saveDraftButton.setEnabled(false);
        reviewControls.add(saveDraftButton);
        reviewControls.add(statusLabel);

        JPanel reviewPane = new JPanel(new BorderLayout());
        reviewPane.add(reviewScroll, BorderLayout.CENTER);
        reviewPane.add(reviewControls, BorderLayout.SOUTH);

        reviewChatSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, reviewPane, chatPanel);
        reviewChatSplit.setResizeWeight(0.65);
        reviewChatSplit.setDividerSize(5);

        // ── Summary sidebar (right of diff) ───────────────────────────
        summaryPane.setEditable(false);
        summaryPane.setBackground(ReviewPanel.BG_SUBTLE);
        summaryPane.setBorder(new EmptyBorder(8, 12, 8, 12));
        summaryPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        summaryScroll.setBackground(ReviewPanel.BG_SUBTLE);
        summaryScroll.getViewport().setBackground(ReviewPanel.BG_SUBTLE);
        summaryScroll.setBorder(null);

        JLabel summaryHeader = new JLabel("Summary");
        summaryHeader.setFont(summaryHeader.getFont().deriveFont(Font.BOLD, 11f));
        summaryHeader.setForeground(ReviewPanel.FG_MUTED);
        summaryHeader.setBorder(new EmptyBorder(6, 10, 4, 10));
        summaryHeader.setBackground(ReviewPanel.BG_SUBTLE);
        summaryHeader.setOpaque(true);

        JPanel summaryPanel = new JPanel(new BorderLayout());
        summaryPanel.setBackground(ReviewPanel.BG_SUBTLE);
        summaryPanel.add(summaryHeader, BorderLayout.NORTH);
        summaryPanel.add(summaryScroll, BorderLayout.CENTER);
        summaryPanel.setPreferredSize(new Dimension(280, 0));
        showSummaryPlaceholder();

        diffSummarySplit =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reviewChatSplit, summaryPanel);
        diffSummarySplit.setResizeWeight(1.0); // diff takes all extra space on resize
        diffSummarySplit.setDividerSize(5);

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
        refreshButton.addActionListener(e -> refreshPRs());
        filterCombo.addActionListener(e -> refreshPRs());
        generateButton.addActionListener(e -> generateReview());
        reviewPanel.setOnCommentRemoved(this::updateCommentNav);
        reviewPanel.setOnAskClaude(
                (ctx, q) -> {
                    ensureChatVisible();
                    chatPanel.askAbout(ctx, q);
                });
        reviewPanel.setOnVerifyComment(
                card -> {
                    PullRequest pr = lastPR;
                    if (pr == null) return;
                    LineComment c = card.getComment();
                    String ctx =
                            "**Verifying comment** on `%s` line %d:\n> %s"
                                    .formatted(c.getFile(), c.getLine(), c.getBody());
                    String question =
                            ("Search the %s/%s repository to verify whether the pattern described "
                                            + "in this comment already exists elsewhere in the codebase. "
                                            + "Should the comment stand or be dismissed? Give a concrete "
                                            + "example file and line if the pattern is established.")
                                    .formatted(pr.getOwner(), pr.getRepo());
                    ensureChatVisible();
                    chatPanel.askAbout(
                            ctx,
                            question,
                            response ->
                                    patternKb.append(
                                            pr.getOwner(), pr.getRepo(), c.getBody(), response));
                });
        saveDraftButton.addActionListener(e -> saveDraft());
        submitButton.addActionListener(e -> submitDraftReview());
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

        String filter = Objects.toString(filterCombo.getSelectedItem(), "Open");

        if ("Drafts".equals(filter)) {
            loadDraftPRs();
            return;
        }

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
                                    listModel.clear();
                                    prs.forEach(listModel::addElement);
                                    setStatus(
                                            prs.isEmpty()
                                                    ? "No PRs found."
                                                    : prs.size() + " PR(s) loaded.");
                                    refreshButton.setEnabled(true);
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    setStatus("Error: " + ex.getMessage());
                                    refreshButton.setEnabled(true);
                                });
                    }
                });
    }

    private void loadStarredRepos() {
        String token = getToken();
        runInBackground(
                () -> {
                    try {
                        List<String> starred = githubService.getStarredRepos(token);
                        runOnEdt(
                                () -> {
                                    loadingRepos = true;
                                    repoCombo.removeAllItems();
                                    starred.forEach(repoCombo::addItem);
                                    loadingRepos = false;
                                    refreshPRs();
                                });
                    } catch (Exception ex) {
                        // Non-fatal: leave combo empty, but still load PRs
                        runOnEdt(this::refreshPRs);
                    }
                });
    }

    private void onPRSelected(PullRequest pr) {
        if (pr == null) return;
        prHeaderLabel.setText(prHeaderHtml(pr));
        lastResult = null;
        lastPR = pr;
        pendingReviewId = null;
        showSummaryPlaceholder();
        reviewPanel.showPlaceholder("Checking GitHub for a pending draft review\u2026");
        generateButton.setEnabled(false);
        prevCommentButton.setEnabled(false);
        nextCommentButton.setEnabled(false);
        commentCountLabel.setText("");
        openInBrowserButton.setEnabled(true);
        saveDraftButton.setEnabled(false);
        submitButton.setEnabled(false);
        chatPanel.clearContext();
        reviewPanel.clearSelection();

        if (!hasToken()) {
            generateButton.setEnabled(true);
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

        generateButton.setEnabled(false);
        lastResult = null;
        showSummaryPlaceholder();
        reviewPanel.showPlaceholder("Fetching diff\u2026");
        setStatus("Fetching diff\u2026");

        String token = getToken();
        runInBackground(
                () -> {
                    try {
                        String diff =
                                githubService.getPRDiff(
                                        token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                        String knownPatterns = patternKb.load(pr.getOwner(), pr.getRepo());
                        runOnEdt(
                                () -> {
                                    generationProgress.start();
                                    setStatus("Generating review with Claude\u2026");
                                    reviewPanel.showStatusLog(
                                            "Generating review with Claude\u2026");
                                    claudeService.reviewPR(
                                            new PRReviewRequest(pr, diff, knownPatterns),
                                            statusMsg -> {
                                                setStatus(statusMsg);
                                                reviewPanel.updateStatus(statusMsg);
                                            },
                                            chars -> generationProgress.updateChars(chars),
                                            result -> {
                                                lastResult = result;
                                                lastPR = pr;
                                                generationProgress.stop();
                                                reviewPanel.showReview(result, diff);
                                                showSummary(result.getSummary());
                                                int count = reviewPanel.getCommentCount();
                                                boolean hasComments = count > 0;
                                                prevCommentButton.setEnabled(hasComments);
                                                nextCommentButton.setEnabled(hasComments);
                                                commentCountLabel.setText(
                                                        hasComments ? "0/" + count : "");
                                                chatPanel.setContext(pr, result);
                                                saveDraftButton.setEnabled(true);
                                                pendingReviewId =
                                                        null; // new review, not yet saved to GitHub
                                                submitButton.setEnabled(false);
                                                setStatus(
                                                        "Review complete. Click \u201cSave Draft\u201d to push to GitHub.");
                                                generateButton.setEnabled(true);
                                            },
                                            err -> {
                                                generationProgress.stop();
                                                reviewPanel.showError(err);
                                                setStatus("Error: " + err);
                                                generateButton.setEnabled(true);
                                            });
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    generationProgress.stop();
                                    reviewPanel.showError(ex.getMessage());
                                    setStatus("Failed to fetch diff: " + ex.getMessage());
                                    generateButton.setEnabled(true);
                                });
                    }
                });
    }

    // ---------------------------------------------------------------
    // Draft helpers
    // ---------------------------------------------------------------

    private void ensureChatVisible() {
        if (!chatVisible) toggleChat();
    }

    private void toggleChat() {
        if (chatVisible) {
            // Save current ratio before collapsing
            int total = reviewChatSplit.getHeight() - reviewChatSplit.getDividerSize();
            if (total > 0)
                lastChatDividerRatio = (double) reviewChatSplit.getDividerLocation() / total;
            reviewChatSplit.setDividerLocation(1.0d);
            chatToggleButton.setText("Chat \u25b8"); // ▸
        } else {
            reviewChatSplit.setDividerLocation(lastChatDividerRatio);
            chatToggleButton.setText("Chat \u25be"); // ▾
        }
        chatVisible = !chatVisible;
    }

    private void loadDraftFromGitHub(PullRequest pr) {
        setStatus("Checking GitHub for a pending draft review\u2026");
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
                                        generateButton.setEnabled(true);
                                        reviewPanel.showPlaceholder(
                                                "PR #%d is merged \u2014 no action needed."
                                                        .formatted(pr.getNumber()));
                                        setStatus(mergedStatus);
                                    });
                            return;
                        }

                        if (finalPending != null) {
                            String diff =
                                    githubService.getPRDiff(
                                            token, pr.getOwner(), pr.getRepo(), pr.getNumber());
                            runOnEdt(
                                    () -> {
                                        pendingReviewId = finalPending.id();
                                        lastResult = finalPending.result();
                                        lastPR = pr;
                                        reviewPanel.showReview(finalPending.result(), diff);
                                        showSummary(finalPending.result().getSummary());
                                        int count = reviewPanel.getCommentCount();
                                        prevCommentButton.setEnabled(count > 0);
                                        nextCommentButton.setEnabled(count > 0);
                                        commentCountLabel.setText(count > 0 ? "0/" + count : "");
                                        chatPanel.setContext(pr, finalPending.result());
                                        saveDraftButton.setEnabled(true);
                                        submitButton.setEnabled(true);
                                        generateButton.setEnabled(true);
                                        prHeaderLabel.setText(prHeaderHtml(pr, true));
                                        setStatus("Loaded pending draft review from GitHub.");
                                    });
                        } else {
                            runOnEdt(
                                    () -> {
                                        generateButton.setEnabled(true);
                                        reviewPanel.showPlaceholder(
                                                "No pending draft found. Click \u201cGenerate Review\u201d to analyse this PR.");
                                        setStatus("");
                                    });
                        }
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    generateButton.setEnabled(true);
                                    reviewPanel.showPlaceholder(
                                            "Click \u201cGenerate Review\u201d to analyse this PR with Claude.");
                                    setStatus("Could not load draft: " + ex.getMessage());
                                });
                    }
                });
    }

    private void loadDraftPRs() {
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
        if (!"Drafts".equals(Objects.toString(filterCombo.getSelectedItem(), ""))) return;
        int index = prList.locationToIndex(e.getPoint());
        if (index < 0) return;
        prList.setSelectedIndex(index);
        PullRequest pr = listModel.getElementAt(index);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("Delete Draft");
        deleteItem.addActionListener(
                ae -> {
                    pendingIndex.remove(pr.getOwner(), pr.getRepo(), pr.getNumber());
                    listModel.removeElement(pr);
                    if (lastPR != null
                            && lastPR.getOwner().equals(pr.getOwner())
                            && lastPR.getRepo().equals(pr.getRepo())
                            && lastPR.getNumber() == pr.getNumber()) {
                        reviewPanel.showPlaceholder("Draft deleted.");
                        lastPR = null;
                        lastResult = null;
                    }
                    setStatus("Draft removed from local index.");
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
                        String id =
                                githubService.saveDraftReview(
                                        token, pr.getOwner(), pr.getRepo(), pr.getNumber(), result);
                        pendingIndex.add(
                                pr.getOwner(), pr.getRepo(), pr.getNumber(), pr.getTitle());
                        runOnEdt(
                                () -> {
                                    pendingReviewId = id;
                                    saveDraftButton.setEnabled(true);
                                    submitButton.setEnabled(true);
                                    setStatus("Draft saved to GitHub.");
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    saveDraftButton.setEnabled(true);
                                    setStatus("Save failed: " + ex.getMessage());
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

        // Build a custom dialog with verdict buttons and an optional comment field
        JTextArea commentArea = new JTextArea(4, 40);
        commentArea.setLineWrap(true);
        commentArea.setWrapStyleWord(true);
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
        String commentBody = commentArea.getText().strip();
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
                                    saveDraftButton.setEnabled(false);
                                    prHeaderLabel.setText(prHeaderHtml(pr));
                                    setStatus("Review submitted to GitHub \u2714");
                                });
                    } catch (Exception ex) {
                        runOnEdt(
                                () -> {
                                    submitButton.setEnabled(pendingReviewId != null);
                                    setStatus("Submit failed: " + ex.getMessage());
                                });
                    }
                });
    }

    private static String prHeaderHtml(PullRequest pr) {
        return prHeaderHtml(pr, false);
    }

    private static String prHeaderHtml(PullRequest pr, boolean draft) {
        String draftBadge = draft ? " &nbsp;<font color=orange>[draft]</font>" : "";
        return "<html><b>#%d %s</b> &nbsp;<font color=gray>by %s &nbsp;\u00b7&nbsp; %s/%s%s</font></html>"
                .formatted(
                        pr.getNumber(),
                        esc(pr.getTitle()),
                        pr.getAuthor(),
                        pr.getOwner(),
                        pr.getRepo(),
                        draftBadge);
    }

    private void updateCommentNav() {
        int total = reviewPanel.getCommentCount();
        int idx = reviewPanel.getCurrentCommentIndex();
        commentCountLabel.setText(total > 0 ? (idx + 1) + "/" + total : "");
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

    private void showSummary(String markdown) {
        summaryPane.setText(ChatPanel.buildHtml(markdown));
        summaryPane.setCaretPosition(0);
    }

    private void showSummaryPlaceholder() {
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

    // ---------------------------------------------------------------
    // Cell renderer
    // ---------------------------------------------------------------

    private static class PRCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof PullRequest pr) {
                String subtitle =
                        pr.getAuthor().isEmpty()
                                ? "%s/%s".formatted(pr.getOwner(), pr.getRepo())
                                : "%s/%s &nbsp;·&nbsp; %s"
                                        .formatted(
                                                pr.getOwner(), pr.getRepo(), esc(pr.getAuthor()));
                setText(
                        "<html><b>#%d</b> %s<br><small style='color:gray'>%s</small></html>"
                                .formatted(pr.getNumber(), esc(pr.getTitle()), subtitle));
            }
            setBorder(new EmptyBorder(4, 6, 4, 6));
            return this;
        }
    }
}
