import type { ChildProcessWithoutNullStreams } from 'child_process';

export type SidecarSpawn = (
    command: string,
    args: string[],
    options: { stdio: ['pipe', 'pipe', 'pipe'] },
) => ChildProcessWithoutNullStreams;

export interface SidecarInitializeResult {
    serviceName: string;
    serviceVersion: string;
    protocolVersion: number;
    capabilities: Record<string, boolean>;
}

export interface SidecarGitHubAuthResult {
    status: 'authenticated' | 'not_installed' | 'not_authenticated' | 'api_failed' | 'invalid_base_url';
    username: string | null;
    message: string;
}

export interface SidecarPrListResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    query: string | null;
    resultLimit: number;
    limited: boolean;
    reviewStatusAvailable: boolean;
    prs: Array<{
        number: number;
        title: string;
        owner: string;
        repo: string;
        author: string;
        createdAt: string;
        htmlUrl: string;
        isDraft: boolean;
        reviewStatus: 'UNREVIEWED' | 'REVIEWED' | 'UPDATED_SINCE_REVIEW' | 'UNAVAILABLE';
    }>;
}

export interface SidecarPrSearchResult {
    status: SidecarPrListResult['status'] | 'invalid_request';
    message: string;
    resultLimit: number;
    limited: boolean;
    prs: SidecarPrListResult['prs'];
}

export interface SidecarStarredReposResult {
    status: SidecarPrListResult['status'];
    message: string;
    resultLimit: number;
    limited: boolean;
    repositories: string[];
}

export interface SidecarExistingReviewsResult {
    status: SidecarPrDetailResult['status'];
    message: string;
    summary: string;
}

export interface SidecarPrDetailHead {
    sha: string;
    ref: string;
    repoFullName: string | null;
    cloneUrl: string | null;
}

/**
 * One comment in an outcome-logging request. Only the fields classification and segmentation need —
 * the engine's outcome log persists a fingerprint, never comment text.
 */
export interface OutcomeComment {
    file: string;
    line: number;
    type?: string;
    body: string;
    severity?: string;
    confidence?: string;
}

/** A file-anchored CI finding, machine-comparable against generated review comments. */
export interface SidecarCheckAnnotation {
    path: string;
    startLine: number;
    endLine: number;
    level: string;
    message: string;
}

/** Rendered CI state plus the structured annotations behind it. */
export interface SidecarCheckStatus {
    summary: string;
    annotations: SidecarCheckAnnotation[];
}

/** Rendered commits plus validated closing references extracted from their raw messages. */
export interface SidecarCommitContext {
    summary: string;
    closingIssueNumbers: number[];
}

/**
 * Outcome of a worktree creation request.
 *
 * `skipped` means there was nothing to check out (no branch); `failed` means git could not produce
 * one. Both are normal domain results rather than errors — the caller falls back to the open
 * workspace folder in either case.
 */
export interface SidecarWorktreeResult {
    status: 'created' | 'skipped' | 'failed';
    worktreeDir: string;
    message: string;
}

export interface SidecarPrDiffResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'not_found_or_inaccessible' | 'diff_too_large' | 'api_failed';
    message: string;
    diff: string | null;
    truncated: boolean;
    limitBytes: number;
}

export type SidecarIncrementalFallbackReason =
    | 'no_prior_review'
    | 'up_to_date'
    | 'baseline_not_in_history'
    | 'baseline_unavailable'
    | 'empty_incremental_diff'
    | 'incremental_diff_too_large';

/**
 * `ok` carries either an incremental baseline..head diff or a full-review fallback reason; any
 * other status carries no scope.
 */
export type SidecarIncrementalDiffResult =
    | {
        status: 'ok';
        message: string;
        scope: 'incremental';
        baselineSha: string;
        headSha: string;
        diff: string;
        truncated: boolean;
        limitBytes: number;
    }
    | {
        status: 'ok';
        message: string;
        scope: 'full';
        fallbackReason: SidecarIncrementalFallbackReason;
        limitBytes: number;
    }
    | {
        status: Exclude<SidecarPrDiffResult['status'], 'ok' | 'diff_too_large'>;
        message: string;
        limitBytes: number;
    };

export interface SidecarPrDetailResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    detail: {
        merged: boolean;
        title: string;
        body: string;
        head: SidecarPrDetailHead | null;
        baseRepoFullName: string | null;
        /** Full base commit SHA; null when GitHub omitted it or it was not a hex object id. */
        baseSha: string | null;
    } | null;
}

export interface SidecarLineComment {
    file: string;
    line: number;
    type: string;
    body: string;
    severity: string | null;
    category: string | null;
    confidence: string | null;
    rationale: string | null;
}

/** Request-shaped comment sent to `prs/saveDraftReview` — optional fields may be omitted. */
export interface SidecarCommentInput {
    file: string;
    line: number;
    type: string;
    body: string;
    severity?: string;
    category?: string;
    confidence?: string;
    rationale?: string;
    /** Accepted and ignored by the sidecar. */
    sources?: string[];
}

export interface SidecarDraftReviewResult {
    status: 'ok' | 'none' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    id: string | null;
    commitId: string | null;
    review: {
        summary: string;
        verdict: string;
        lineComments: SidecarLineComment[];
        importedFromGitHub: boolean;
    } | null;
}

export interface SidecarDraftReviewMutationResult {
    status: 'ok' | 'not_installed' | 'not_authenticated' | 'invalid_base_url' | 'invalid_request' | 'rate_limited' | 'network_error' | 'api_failed';
    message: string;
    reviewId: string | null;
    commentsDropped: boolean;
    recoveryRequired: boolean;
}

// ── Review generation / chat ───────────────────────────────────────────────────

export type ReviewProvider = 'claude' | 'copilot';

export interface SidecarPrInput {
    title: string;
    htmlUrl: string;
    owner: string;
    repo: string;
    number: number;
    body: string;
    author: string;
    createdAt: string;
    isDraft: boolean;
}

export interface SidecarGenerateReviewParams {
    deepReview?: { retainedId: string; server: string };
    operationId: string;
    provider: ReviewProvider;
    projectDir?: string;
    model: string;
    effort: string;
    inheritMcp: boolean;
    configDir?: string;
    selfCritique: boolean;
    reviewSupervisorEnabled: boolean;
    /** Optional Copilot model run as a parallel second reviewer; blank or absent disables it. */
    secondReviewerModel?: string;
    /** PR base commit; the engine reads trusted guidance and file history from it. */
    baseSha?: string;
    /** Last-reviewed commit when `diff` holds only later changes; the engine adds a scope section. */
    incrementalBaselineSha?: string;
    chunkedReview: boolean;
    pr: SidecarPrInput;
    diff: string;
    priorReview?: string;
    existingReviews?: string;
    repoGuidelines?: string;
    /** Configured guidance globs; the engine reads matches from `baseSha` before its defaults. */
    guidanceGlobs?: string[];
    /** Reviewer-configured local folder of extra review rules, read by the engine. */
    rulesDirectory?: string;
    focusAreas?: string;
    customInstructions?: string;
    /** Pre-rendered CI state from `prs/getCheckStatus`. */
    ciStatus?: string;
    /** Pre-rendered commit messages from `prs/getCommits`. */
    commits?: string;
    /** Pre-rendered linked-issue context from `prs/getLinkedIssues`. */
    linkedIssue?: string;
    /** Pre-rendered language/build profile from `repo/getProfile`. */
    repoProfile?: string;
    /**
     * Structured form of `ciStatus`. Machine-comparable, so the engine can drop review comments
     * that merely restate a CI finding instead of only asking the model not to produce them.
     */
    ciAnnotations?: Array<{ file: string; line: number; level: string; message: string }>;
}

export interface DeepReviewPreparation {
    retainedId: string;
    head: string;
    worktree: string;
    servers: string[];
}

export interface RetainedDeepReview {
    id: string;
    repository: string;
    worktree: string;
    head: string;
    createdAt: number;
}

export interface SidecarChatMessage {
    role: 'USER' | 'ASSISTANT';
    content: string;
}

export interface SidecarChatParams {
    operationId: string;
    provider: ReviewProvider;
    projectDir?: string;
    effort: string;
    inheritMcp: boolean;
    configDir?: string;
    prContext?: string;
    history?: SidecarChatMessage[];
    userMessage?: string;
    rawPrompt?: string;
}

/** Validates the shape returned by `reviews/generate`. */
