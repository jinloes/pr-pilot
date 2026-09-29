import { spawn, type ChildProcessWithoutNullStreams } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import type { ReviewResult, LineComment } from './models';

const REQUEST_TIMEOUT_MS = 60_000;
// Review/chat requests shell out to a provider CLI that can legitimately run for a long time
// (ClaudeService allows up to 30 minutes for review generation, resumed sessions up to 10
// minutes); a much longer timeout than the default RPC timeout is required here.
const REVIEW_REQUEST_TIMEOUT_MS = 35 * 60 * 1000;
/**
 * Worktree creation runs `git fetch` then `git worktree add`, which the engine bounds at 120s + 30s
 * on the fork path — already past the default 60s RPC timeout. A timeout here is not a local
 * failure either: this client treats one as fatal, killing the sidecar and failing every later
 * request. Since the caller already degrades a failed worktree to the user's own checkout, a
 * generous bound that lets a slow fetch finish is strictly better than one that ends the session.
 */
const WORKTREE_REQUEST_TIMEOUT_MS = 5 * 60 * 1000;
const HEADER_TERMINATOR = '\r\n\r\n';
const SIDECAR_PROTOCOL_VERSION = 1;
const MAX_LINKED_ISSUES = 3;
const MAX_GITHUB_ISSUE_NUMBER = 999_999_999;
const MAX_RUNTIME_RECOVERY_ATTEMPTS = 3;
/**
 * Every capability the sidecar advertises, all of which this client calls. Kept exhaustive on
 * purpose: each of these has a `SidecarClient` method, so a sidecar missing one produces a broken
 * feature, and for the four context reads (`checkStatus`, `prCommits`, `linkedIssues`,
 * `repoProfile`) it produces a *silently* broken one — those calls swallow failure by design, so an
 * unsupported sidecar would degrade every review to empty prompt sections with nothing surfaced to
 * the user. Failing the handshake instead turns that into an actionable message.
 *
 * `test/wireCatalog.test.ts` pins this list against the sidecar's declared capability groups.
 */
export const REQUIRED_CAPABILITIES = [
    'githubAuth',
    'prDetail',
    'prDiff',
    'prList',
    'repoDetect',
    'draftReview',
    'draftReviewMutations',
    'prSearch',
    'starredRepos',
    'existingReviews',
    'checkStatus',
    'prCommits',
    'linkedIssues',
    'repoProfile',
    'repoGuidelines',
    'worktrees',
    'semanticReviews',
    'reviewGeneration',
] as const;

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (err: Error) => void;
}

/** Callbacks invoked as `reviews/status`, `reviews/chunk`, and `reviews/chatChunk` notifications
 * arrive for a specific in-flight request, keyed by that request's JSON-RPC id. */
interface NotificationHandlers {
    onStatus?: (message: string) => void;
    onChunk?: (kind: 'text' | 'thinking', text: string) => void;
    onChatChunk?: (text: string) => void;
}

interface SidecarRpcResponse {
    jsonrpc?: string;
    id?: number;
    result?: unknown;
    error?: { code?: number; message?: string };
}

class StickySidecarFailure extends Error {}

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
    chunkedReview: boolean;
    pr: SidecarPrInput;
    diff: string;
    priorReview?: string;
    existingReviews?: string;
    repoGuidelines?: string;
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
export function parseReviewResult(value: unknown): ReviewResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.summary !== 'string' || typeof result.verdict !== 'string' || !Array.isArray(result.lineComments)) {
        return null;
    }
    const lineComments = result.lineComments.map((entry) => {
        if (!entry || typeof entry !== 'object') return null;
        const comment = entry as Record<string, unknown>;
        if (typeof comment.file !== 'string' || !Number.isInteger(comment.line)
            || typeof comment.type !== 'string' || typeof comment.body !== 'string') {
            return null;
        }
        const parsed: LineComment = {
            file: comment.file,
            line: comment.line as number,
            type: comment.type as LineComment['type'],
            body: comment.body,
            severity: typeof comment.severity === 'string' ? (comment.severity as LineComment['severity']) : undefined,
            category: typeof comment.category === 'string' ? (comment.category as LineComment['category']) : undefined,
            confidence: typeof comment.confidence === 'string' ? (comment.confidence as LineComment['confidence']) : undefined,
            rationale: typeof comment.rationale === 'string' ? comment.rationale : undefined,
        };
        return parsed;
    });
    if (lineComments.some((comment) => comment === null)) return null;
    return {
        summary: result.summary,
        verdict: result.verdict as ReviewResult['verdict'],
        lineComments: lineComments as LineComment[],
    };
}

/** Validates the shape returned by `reviews/chat`. */
export function parseChatResult(value: unknown): string | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    return typeof result.content === 'string' ? result.content : null;
}

const AUTH_STATUSES = new Set<SidecarGitHubAuthResult['status']>([
    'authenticated',
    'not_installed',
    'not_authenticated',
    'api_failed',
    'invalid_base_url',
]);

const PR_LIST_STATUSES = new Set<SidecarPrListResult['status']>([
    'ok',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'rate_limited',
    'network_error',
    'api_failed',
]);
const PR_SEARCH_STATUSES = new Set<SidecarPrSearchResult['status']>([
    ...PR_LIST_STATUSES,
    'invalid_request',
]);
const REVIEW_STATUSES = new Set<SidecarPrListResult['prs'][number]['reviewStatus']>([
    'UNREVIEWED',
    'REVIEWED',
    'UPDATED_SINCE_REVIEW',
    'UNAVAILABLE',
]);

const PR_DETAIL_STATUSES = new Set<SidecarPrDetailResult['status']>([
    'ok',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'invalid_request',
    'rate_limited',
    'network_error',
    'api_failed',
]);
const PR_DIFF_STATUSES = new Set<SidecarPrDiffResult['status']>([
    ...PR_DETAIL_STATUSES,
    'not_found_or_inaccessible',
    'diff_too_large',
]);

const DRAFT_REVIEW_STATUSES = new Set<SidecarDraftReviewResult['status']>([
    'ok',
    'none',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'invalid_request',
    'rate_limited',
    'network_error',
    'api_failed',
]);

const DRAFT_REVIEW_MUTATION_STATUSES = new Set<SidecarDraftReviewMutationResult['status']>([
    'ok',
    'not_installed',
    'not_authenticated',
    'invalid_base_url',
    'invalid_request',
    'rate_limited',
    'network_error',
    'api_failed',
]);

/** Validates the token-free result shape returned by `github/checkAuth`. */
export function parseGitHubAuthResult(value: unknown): SidecarGitHubAuthResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string' || !AUTH_STATUSES.has(result.status as SidecarGitHubAuthResult['status'])) {
        return null;
    }
    if (result.username !== null && typeof result.username !== 'string') return null;
    if (typeof result.message !== 'string') return null;
    return {
        status: result.status as SidecarGitHubAuthResult['status'],
        username: typeof result.username === 'string' ? result.username : null,
        message: result.message,
    };
}

export function parseInitializeResult(value: unknown): SidecarInitializeResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (result.serviceName !== 'pr-pilot-sidecar'
        || typeof result.serviceVersion !== 'string'
        || !Number.isInteger(result.protocolVersion)
        || !result.capabilities
        || typeof result.capabilities !== 'object'
        || Array.isArray(result.capabilities)) return null;
    const capabilities = result.capabilities as Record<string, unknown>;
    if (Object.values(capabilities).some((enabled) => typeof enabled !== 'boolean')) return null;
    return {
        serviceName: result.serviceName,
        serviceVersion: result.serviceVersion,
        protocolVersion: result.protocolVersion as number,
        capabilities: capabilities as Record<string, boolean>,
    };
}

/** Validates the token-free result shape returned by `prs/list`. */
export function parsePrListResult(value: unknown): SidecarPrListResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string' || !PR_LIST_STATUSES.has(result.status as SidecarPrListResult['status'])) {
        return null;
    }
    if (typeof result.message !== 'string'
        || (result.query !== null && typeof result.query !== 'string')
        || typeof result.resultLimit !== 'number'
        || typeof result.limited !== 'boolean'
        || typeof result.reviewStatusAvailable !== 'boolean'
        || !Array.isArray(result.prs)) {
        return null;
    }
    const prs = result.prs.map((value) => {
        if (!value || typeof value !== 'object') return null;
        const pr = value as Record<string, unknown>;
        if (!Number.isInteger(pr.number)
            || typeof pr.title !== 'string'
            || typeof pr.owner !== 'string'
            || typeof pr.repo !== 'string'
            || typeof pr.author !== 'string'
            || typeof pr.createdAt !== 'string'
            || typeof pr.htmlUrl !== 'string'
            || typeof pr.isDraft !== 'boolean'
            || typeof pr.reviewStatus !== 'string'
            || !REVIEW_STATUSES.has(pr.reviewStatus as SidecarPrListResult['prs'][number]['reviewStatus'])) {
            return null;
        }
        return {
            number: pr.number,
            title: pr.title,
            owner: pr.owner,
            repo: pr.repo,
            author: pr.author,
            createdAt: pr.createdAt,
            htmlUrl: pr.htmlUrl,
            isDraft: pr.isDraft,
            reviewStatus: pr.reviewStatus,
        };
    });
    if (prs.some((pr) => pr === null)) return null;
    return {
        status: result.status as SidecarPrListResult['status'],
        message: result.message,
        query: typeof result.query === 'string' ? result.query : null,
        resultLimit: result.resultLimit,
        limited: result.limited,
        reviewStatusAvailable: result.reviewStatusAvailable,
        prs: prs as SidecarPrListResult['prs'],
    };
}

export function parsePrSearchResult(value: unknown): SidecarPrSearchResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_SEARCH_STATUSES.has(result.status as SidecarPrSearchResult['status'])) return null;
    const parsed = parsePrListResult({
        ...result,
        status: result.status === 'invalid_request' ? 'api_failed' : result.status,
        query: null,
        reviewStatusAvailable: false,
    });
    return parsed === null ? null : {
        status: result.status as SidecarPrSearchResult['status'],
        message: parsed.message,
        resultLimit: parsed.resultLimit,
        limited: parsed.limited,
        prs: parsed.prs,
    };
}

export function parseStarredReposResult(value: unknown): SidecarStarredReposResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_LIST_STATUSES.has(result.status as SidecarStarredReposResult['status'])
        || typeof result.message !== 'string'
        || typeof result.resultLimit !== 'number'
        || typeof result.limited !== 'boolean'
        || !Array.isArray(result.repositories)
        || result.repositories.some((repository) => typeof repository !== 'string')) return null;
    return {
        status: result.status as SidecarStarredReposResult['status'],
        message: result.message,
        resultLimit: result.resultLimit,
        limited: result.limited,
        repositories: result.repositories as string[],
    };
}

export function parseExistingReviewsResult(value: unknown): SidecarExistingReviewsResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_DETAIL_STATUSES.has(result.status as SidecarExistingReviewsResult['status'])
        || typeof result.message !== 'string'
        || typeof result.summary !== 'string') return null;
    return {
        status: result.status as SidecarExistingReviewsResult['status'],
        message: result.message,
        summary: result.summary,
    };
}

export function parseCommitContext(value: unknown): SidecarCommitContext | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.summary !== 'string'
        || !Array.isArray(result.closingIssueNumbers)
        || result.closingIssueNumbers.length > MAX_LINKED_ISSUES
        || result.closingIssueNumbers.some((number) =>
            typeof number !== 'number'
            || !Number.isInteger(number)
            || number <= 0
            || number > MAX_GITHUB_ISSUE_NUMBER)
        || new Set(result.closingIssueNumbers).size !== result.closingIssueNumbers.length) {
        return null;
    }
    return {
        summary: result.summary,
        closingIssueNumbers: [...result.closingIssueNumbers] as number[],
    };
}

/** Validates the token-free result shape returned by `prs/getDetail`. */
export function parsePrDetailResult(value: unknown): SidecarPrDetailResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !PR_DETAIL_STATUSES.has(result.status as SidecarPrDetailResult['status'])
        || typeof result.message !== 'string'
        || (result.detail !== null && typeof result.detail !== 'object')) {
        return null;
    }
    if (result.detail === null) {
        return result.status === 'ok' ? null : {
            status: result.status as SidecarPrDetailResult['status'],
            message: result.message,
            detail: null,
        };
    }
    const detail = result.detail as Record<string, unknown>;
    if (typeof detail.merged !== 'boolean'
        || typeof detail.title !== 'string'
        || typeof detail.body !== 'string'
        || (detail.baseRepoFullName !== null && typeof detail.baseRepoFullName !== 'string')
        || (detail.baseSha !== undefined && detail.baseSha !== null && typeof detail.baseSha !== 'string')) {
        return null;
    }
    let head: SidecarPrDetailHead | null = null;
    if (detail.head !== null) {
        if (!detail.head || typeof detail.head !== 'object') return null;
        const rawHead = detail.head as Record<string, unknown>;
        if (typeof rawHead.sha !== 'string'
            || typeof rawHead.ref !== 'string'
            || (rawHead.repoFullName !== null && typeof rawHead.repoFullName !== 'string')
            || (rawHead.cloneUrl !== null && typeof rawHead.cloneUrl !== 'string')) {
            return null;
        }
        head = {
            sha: rawHead.sha,
            ref: rawHead.ref,
            repoFullName: typeof rawHead.repoFullName === 'string' ? rawHead.repoFullName : null,
            cloneUrl: typeof rawHead.cloneUrl === 'string' ? rawHead.cloneUrl : null,
        };
    }
    return {
        status: result.status as SidecarPrDetailResult['status'],
        message: result.message,
        detail: {
            merged: detail.merged,
            title: detail.title,
            body: detail.body,
            head,
            baseRepoFullName: typeof detail.baseRepoFullName === 'string' ? detail.baseRepoFullName : null,
            baseSha: typeof detail.baseSha === 'string' ? detail.baseSha : null,
        },
    };
}

export function parsePrDiffResult(value: unknown): SidecarPrDiffResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string' || !PR_DIFF_STATUSES.has(result.status as SidecarPrDiffResult['status'])
        || typeof result.message !== 'string' || (result.diff !== null && typeof result.diff !== 'string')
        || typeof result.truncated !== 'boolean' || typeof result.limitBytes !== 'number') return null;
    if ((result.status === 'ok') !== (typeof result.diff === 'string')) return null;
    return { status: result.status as SidecarPrDiffResult['status'], message: result.message,
        diff: typeof result.diff === 'string' ? result.diff : null, truncated: result.truncated, limitBytes: result.limitBytes };
}

/** Validates the token-free result shape returned by `prs/getDraftReview`. */
export function parseDraftReviewResult(value: unknown): SidecarDraftReviewResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !DRAFT_REVIEW_STATUSES.has(result.status as SidecarDraftReviewResult['status'])
        || typeof result.message !== 'string'
        || (result.id !== null && typeof result.id !== 'string')
        || (result.commitId !== null && typeof result.commitId !== 'string')
        || (result.review !== null && typeof result.review !== 'object')) {
        return null;
    }
    if (result.review === null) {
        return result.status === 'ok' ? null : {
            status: result.status as SidecarDraftReviewResult['status'],
            message: result.message,
            id: typeof result.id === 'string' ? result.id : null,
            commitId: typeof result.commitId === 'string' ? result.commitId : null,
            review: null,
        };
    }
    const review = result.review as Record<string, unknown>;
    if (typeof review.summary !== 'string'
        || typeof review.verdict !== 'string'
        || typeof review.importedFromGitHub !== 'boolean'
        || !Array.isArray(review.lineComments)) {
        return null;
    }
    const lineComments = review.lineComments.map((entry) => {
        if (!entry || typeof entry !== 'object') return null;
        const comment = entry as Record<string, unknown>;
        if (typeof comment.file !== 'string'
            || !Number.isInteger(comment.line)
            || typeof comment.type !== 'string'
            || typeof comment.body !== 'string'
            || (comment.severity !== null && typeof comment.severity !== 'string')
            || (comment.category !== null && typeof comment.category !== 'string')
            || (comment.confidence !== null && typeof comment.confidence !== 'string')
            || (comment.rationale !== null && typeof comment.rationale !== 'string')) {
            return null;
        }
        return {
            file: comment.file,
            line: comment.line,
            type: comment.type,
            body: comment.body,
            severity: typeof comment.severity === 'string' ? comment.severity : null,
            category: typeof comment.category === 'string' ? comment.category : null,
            confidence: typeof comment.confidence === 'string' ? comment.confidence : null,
            rationale: typeof comment.rationale === 'string' ? comment.rationale : null,
        };
    });
    if (lineComments.some((comment) => comment === null)) return null;
    return {
        status: result.status as SidecarDraftReviewResult['status'],
        message: result.message,
        id: typeof result.id === 'string' ? result.id : null,
        commitId: typeof result.commitId === 'string' ? result.commitId : null,
        review: {
            summary: review.summary,
            verdict: review.verdict,
            lineComments: lineComments as SidecarLineComment[],
            importedFromGitHub: review.importedFromGitHub,
        },
    };
}

/** Validates the token-free result shape returned by `prs/saveDraftReview`, `prs/submitReview`,
 * and `prs/deleteDraftReview` — all three share the same result shape. */
export function parseDraftReviewMutationResult(value: unknown): SidecarDraftReviewMutationResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string'
        || !DRAFT_REVIEW_MUTATION_STATUSES.has(result.status as SidecarDraftReviewMutationResult['status'])
        || typeof result.message !== 'string'
        || (result.reviewId !== null && typeof result.reviewId !== 'string')
        || typeof result.commentsDropped !== 'boolean'
        || typeof result.recoveryRequired !== 'boolean') {
        return null;
    }
    return {
        status: result.status as SidecarDraftReviewMutationResult['status'],
        message: result.message,
        reviewId: typeof result.reviewId === 'string' ? result.reviewId : null,
        commentsDropped: result.commentsDropped,
        recoveryRequired: result.recoveryRequired,
    };
}

/** Encodes a JSON-RPC payload with the same bounded Content-Length framing the sidecar's
 * StdioFrameCodec (Java) reads/writes. Kept as a pure function so framing can be unit tested
 * without spawning a real process. */
export function encodeFrame(payload: string): Buffer {
    const body = Buffer.from(payload, 'utf8');
    if (body.length > 8 * 1024 * 1024) throw new Error('JSON-RPC payload exceeds the maximum size');
    return Buffer.concat([Buffer.from(`Content-Length: ${body.length}${HEADER_TERMINATOR}`, 'ascii'), body]);
}

/** Extracts every complete Content-Length-framed message currently available in `buffer`,
 * invoking `onFrame` with each decoded UTF-8 body in order. Returns the remaining unconsumed
 * bytes (a partial frame, if any) so callers can keep appending as more data arrives. */
export function extractFrames(buffer: Uint8Array, onFrame: (body: string) => void): Buffer {
    let remaining: Buffer = Buffer.from(buffer);
    for (;;) {
        const headerEnd = remaining.indexOf(HEADER_TERMINATOR);
        if (headerEnd < 0) return remaining;
        const header = remaining.subarray(0, headerEnd).toString('ascii');
        const match = /Content-Length:\s*(\d+)/i.exec(header);
        if (!match) {
            throw new Error('Sidecar sent an invalid Content-Length header');
        }
        const length = parseInt(match[1], 10);
        if (length > 8 * 1024 * 1024) throw new Error('Sidecar response exceeds the maximum size');
        const bodyStart = headerEnd + HEADER_TERMINATOR.length;
        if (remaining.length < bodyStart + length) return remaining;
        onFrame(remaining.subarray(bodyStart, bodyStart + length).toString('utf8'));
        remaining = remaining.subarray(bodyStart + length);
    }
}

/** Required transport to the shared Java GitHub engine used by the VS Code host. */
export class SidecarClient {
    private child: ChildProcessWithoutNullStreams | null = null;
    private startupFailure: Error | null = null;
    private runtimeFailure: Error | null = null;
    private runtimeRecoveryAttempts = 0;
    private recoveryPrompted = false;
    private readyPromise: Promise<void> | null = null;
    private disposed = false;
    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private readonly notificationHandlers = new Map<number, NotificationHandlers>();
    private buffer: Buffer = Buffer.alloc(0);
    private stderr = '';

    constructor(
        private readonly jarPath: string | null,
        private readonly javaBinary = 'java',
        private readonly spawnSidecar: SidecarSpawn = spawn,
        private readonly requestTimeoutMs = REQUEST_TIMEOUT_MS,
        private readonly onRecoveryExhausted?: (failure: Error) => void,
    ) {}

    async initialize(): Promise<void> {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        if (this.startupFailure) throw this.startupFailure;
        if (this.readyPromise) return this.readyPromise;
        if (this.runtimeFailure) {
            if (this.runtimeRecoveryAttempts >= MAX_RUNTIME_RECOVERY_ATTEMPTS) {
                const failure = new Error(
                    `${this.runtimeFailure.message} Automatic recovery failed after ${MAX_RUNTIME_RECOVERY_ATTEMPTS} attempts. Use Retry to start the sidecar again.`,
                );
                if (!this.recoveryPrompted) {
                    this.recoveryPrompted = true;
                    this.onRecoveryExhausted?.(failure);
                }
                throw failure;
            }
            this.runtimeRecoveryAttempts++;
            this.runtimeFailure = null;
            this.readyPromise = null;
            this.buffer = Buffer.alloc(0);
            this.stderr = '';
        }

        try {
            this.ensureStarted();
        } catch (err) {
            throw err instanceof Error ? err : new Error(String(err));
        }
        const initializingChild = this.child;
        const ready = this.requestRaw('initialize', {}).then((value) => {
            const result = parseInitializeResult(value);
            if (!result) {
                throw new StickySidecarFailure(
                    'PR Pilot Java sidecar returned an invalid initialization response. Reinstall the extension.',
                );
            }
            if (result.protocolVersion !== SIDECAR_PROTOCOL_VERSION) {
                throw new StickySidecarFailure(
                    `PR Pilot Java sidecar protocol mismatch (expected ${SIDECAR_PROTOCOL_VERSION}, got ${result.protocolVersion}). Reinstall the extension.`,
                );
            }
            const missing = REQUIRED_CAPABILITIES.filter((capability) => result.capabilities[capability] !== true);
            if (missing.length > 0) {
                throw new StickySidecarFailure(
                    `PR Pilot Java sidecar is missing required capabilities: ${missing.join(', ')}. Reinstall the extension.`,
                );
            }
            if (this.child !== initializingChild) {
                throw this.runtimeFailure ?? new Error('PR Pilot Java sidecar stopped during initialization.');
            }
            this.runtimeFailure = null;
            this.runtimeRecoveryAttempts = 0;
            this.recoveryPrompted = false;
        }).catch((err: unknown) => {
            const failure = err instanceof Error ? err : new Error(String(err));
            if (this.startupFailure) throw this.startupFailure;
            if (failure instanceof StickySidecarFailure) {
                this.markStartupFailure(failure);
                throw failure;
            }
            if (!this.runtimeFailure) this.markRuntimeFailure(failure, initializingChild);
            return this.initialize();
        });
        this.readyPromise = ready;
        return ready;
    }

    private ensureStarted(): void {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        if (this.startupFailure) throw this.startupFailure;
        if (this.child) return;
        if (!this.jarPath) {
            const failure = new StickySidecarFailure(
                'PR Pilot Java sidecar is missing. Reinstall the extension or run ./gradlew :sidecar:bootJar for local development.',
            );
            this.markStartupFailure(failure);
            throw failure;
        }
        if (!fs.existsSync(this.jarPath)) {
            const failure = new StickySidecarFailure(
                `PR Pilot Java sidecar was not found at ${this.jarPath}. Reinstall the extension.`,
            );
            this.markStartupFailure(failure);
            throw failure;
        }
        try {
            const child = this.spawnSidecar(
                this.javaBinary,
                ['-jar', this.jarPath],
                { stdio: ['pipe', 'pipe', 'pipe'] },
            );
            child.on('error', (err: NodeJS.ErrnoException) => {
                if (this.disposed || this.child !== child) return;
                const failure = err.code === 'ENOENT'
                    ? new StickySidecarFailure('Java was not found. Install Java 17 or newer and ensure java is on PATH.')
                    : new Error(`PR Pilot Java sidecar failed to start: ${err.message}`);
                if (failure instanceof StickySidecarFailure) this.markStartupFailure(failure, child);
                else this.markRuntimeFailure(failure, child);
            });
            child.on('exit', (code, signal) => {
                if (this.disposed || this.child !== child) return;
                const failure = this.processExitError(code, signal);
                if (failure instanceof StickySidecarFailure) this.markStartupFailure(failure, child);
                else this.markRuntimeFailure(failure, child);
            });
            child.stdout.on('data', (chunk: Buffer) => {
                if (this.child === child) this.onData(chunk, child);
            });
            child.stderr.on('data', (chunk: Buffer) => {
                if (this.child === child) {
                    this.stderr = (this.stderr + chunk.toString('utf8')).slice(-4_096);
                }
            });
            this.child = child;
        } catch (err) {
            const failure = (err as NodeJS.ErrnoException | undefined)?.code === 'ENOENT'
                ? new StickySidecarFailure(
                    'Java was not found. Install Java 17 or newer and ensure java is on PATH.',
                )
                : err instanceof Error
                    ? new Error(`PR Pilot Java sidecar failed to start: ${err.message}`)
                    : new Error('PR Pilot Java sidecar failed to start.');
            if (failure instanceof StickySidecarFailure) this.markStartupFailure(failure);
            else this.markRuntimeFailure(failure);
            throw failure;
        }
    }

    private onData(chunk: Buffer, child: ChildProcessWithoutNullStreams): void {
        try {
            this.buffer = extractFrames(
                Buffer.concat([this.buffer, chunk]),
                (body) => this.handleMessage(body),
            );
        } catch (err) {
            const failure = err instanceof Error ? err : new Error(String(err));
            this.markRuntimeFailure(failure, child);
        }
    }

    private handleMessage(body: string): void {
        let message: SidecarRpcResponse & { method?: string; params?: unknown };
        try {
            message = JSON.parse(body) as SidecarRpcResponse & { method?: string; params?: unknown };
        } catch {
            const failure = new Error('PR Pilot Java sidecar sent malformed JSON.');
            this.markRuntimeFailure(failure);
            return;
        }
        if (typeof message.id !== 'number') {
            // A JSON-RPC notification (no id) — route reviews/status, reviews/chunk, and
            // reviews/chatChunk to the handlers registered for their correlated request.
            if (typeof message.method === 'string') this.dispatchNotification(message.method, message.params);
            return;
        }
        const pending = this.pending.get(message.id);
        if (!pending) return;
        if (message.jsonrpc !== '2.0' || (message.error === undefined) === (message.result === undefined)) {
            this.markRuntimeFailure(
                new Error('PR Pilot Java sidecar returned a malformed JSON-RPC response.'),
            );
            return;
        }
        this.pending.delete(message.id);
        this.notificationHandlers.delete(message.id);
        if (message.error) {
            pending.reject(new Error(message.error.message ?? 'Sidecar error'));
        } else {
            pending.resolve(message.result);
        }
    }

    private dispatchNotification(method: string, params: unknown): void {
        const p = params as Record<string, unknown> | undefined;
        const requestId = typeof p?.requestId === 'number' ? p.requestId : undefined;
        if (requestId === undefined) return;
        const handlers = this.notificationHandlers.get(requestId);
        if (!handlers) return;
        if (method === 'reviews/status' && typeof p?.message === 'string') {
            handlers.onStatus?.(p.message);
        } else if (method === 'reviews/chunk' && typeof p?.kind === 'string' && typeof p?.text === 'string') {
            handlers.onChunk?.(p.kind === 'thinking' ? 'thinking' : 'text', p.text);
        } else if (method === 'reviews/chatChunk' && typeof p?.text === 'string') {
            handlers.onChatChunk?.(p.text);
        }
    }

    private markRuntimeFailure(
        failure: Error,
        expectedChild?: ChildProcessWithoutNullStreams | null,
    ): void {
        if (this.disposed) return;
        if (expectedChild && this.child !== expectedChild) return;
        this.runtimeFailure = failure;
        this.readyPromise = null;
        this.buffer = Buffer.alloc(0);
        this.failAllPending(failure);
        this.stopChild();
    }

    private markStartupFailure(
        failure: Error,
        expectedChild?: ChildProcessWithoutNullStreams | null,
    ): void {
        if (this.disposed) return;
        if (expectedChild && this.child !== expectedChild) return;
        this.startupFailure = failure;
        this.runtimeFailure = null;
        this.readyPromise = null;
        this.buffer = Buffer.alloc(0);
        this.failAllPending(failure);
        this.stopChild();
    }

    private failAllPending(err: Error): void {
        for (const pending of this.pending.values()) pending.reject(err);
        this.pending.clear();
        this.notificationHandlers.clear();
    }

    private failPending(id: number, err: Error): boolean {
        const pending = this.pending.get(id);
        if (!pending) return false;
        this.pending.delete(id);
        this.notificationHandlers.delete(id);
        pending.reject(err);
        return true;
    }

    /** Restarts the sidecar after a user-visible transport failure and revalidates its capabilities. */
    async restart(): Promise<void> {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        this.failAllPending(new Error('PR Pilot Java sidecar is restarting.'));
        this.stopChild();
        this.startupFailure = null;
        this.runtimeFailure = null;
        this.runtimeRecoveryAttempts = 0;
        this.recoveryPrompted = false;
        this.readyPromise = null;
        this.buffer = Buffer.alloc(0);
        this.stderr = '';
        await this.initialize();
    }

    private async request(method: string, params: unknown): Promise<unknown> {
        await this.initialize();
        return this.requestRaw(method, params);
    }

    private requestRaw(
        method: string,
        params: unknown,
        options?: { timeoutMs?: number; notificationHandlers?: NotificationHandlers },
    ): Promise<unknown> {
        const child = this.child;
        if (!child) {
            return Promise.reject(
                this.startupFailure
                ?? this.runtimeFailure
                ?? new Error('PR Pilot Java sidecar is not running.'),
            );
        }
        const id = this.nextId++;
        const timeoutMs = options?.timeoutMs ?? this.requestTimeoutMs;
        if (options?.notificationHandlers) this.notificationHandlers.set(id, options.notificationHandlers);
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                const failure = new Error(
                    `PR Pilot Java sidecar request "${method}" timed out. Try the request again.`,
                );
                this.failPending(id, failure);
            }, timeoutMs);
            this.pending.set(id, {
                resolve: (value) => { clearTimeout(timer); this.notificationHandlers.delete(id); resolve(value); },
                reject: (err) => { clearTimeout(timer); this.notificationHandlers.delete(id); reject(err); },
            });
            child.stdin.write(encodeFrame(JSON.stringify({ jsonrpc: '2.0', id, method, params })), (err) => {
                if (err) {
                    const failure = err instanceof Error ? err : new Error(String(err));
                    this.markRuntimeFailure(failure, child);
                }
            });
        });
    }

    /**
     * Generates a PR review via the shared {@code review-engine} Claude/Copilot services, routed
     * through the sidecar over JSON-RPC instead of spawning the provider CLI in the extension
     * process. `onStatus`/`onChunk` are driven by `reviews/status`/`reviews/chunk` notifications
     * correlated to this request's id.
     */
    async generateReview(
        params: SidecarGenerateReviewParams,
        onStatus: (message: string) => void,
        onChunk: (kind: 'text' | 'thinking', text: string) => void,
    ): Promise<ReviewResult> {
        await this.initialize();
        const value = await this.requestRaw('reviews/generate', params, {
            timeoutMs: REVIEW_REQUEST_TIMEOUT_MS,
            notificationHandlers: { onStatus, onChunk },
        });
        return this.parseResult('review generation', parseReviewResult, value);
    }

    /**
     * Answers a chat question via the shared {@code review-engine} services, routed through the
     * sidecar. `onChunk` is driven by `reviews/chatChunk` notifications correlated to this
     * request's id.
     */
    async chatReview(params: SidecarChatParams, onChunk: (text: string) => void): Promise<string> {
        await this.initialize();
        const value = await this.requestRaw('reviews/chat', params, {
            timeoutMs: REVIEW_REQUEST_TIMEOUT_MS,
            notificationHandlers: { onChatChunk: onChunk },
        });
        const result = parseChatResult(value);
        if (result === null) throw this.invalidResponse('chat response');
        return result;
    }

    /** Cancels only the matching review/chat operation on the sidecar. */
    async cancelReview(operationId: string): Promise<boolean> {
        if (!this.child) return false;
        const value = await this.request('reviews/cancel', { operationId });
        if (!value || typeof value !== 'object' || typeof (value as { cancelled?: unknown }).cancelled !== 'boolean') {
            throw this.invalidResponse('cancellation response');
        }
        return (value as { cancelled: boolean }).cancelled;
    }

    /**
     * Records what the reviewer did with each generated comment. Instrumentation: failures are
     * swallowed because the submission this follows has already succeeded, and a metrics write
     * must never surface as a user-visible error.
     */
    async recordReviewOutcome(
        provider: string,
        model: string,
        reviewSupervisorEnabled: boolean,
        generated: OutcomeComment[],
        submitted: OutcomeComment[],
    ): Promise<void> {
        try {
            await this.request('reviews/recordOutcome', {
                provider,
                model,
                reviewSupervisorEnabled,
                generated,
                submitted,
            });
        } catch (err) {
            console.warn('[pr-pilot] Review outcome logging failed:', err instanceof Error ? err.message : String(err));
        }
    }

    /** Returns null only when the shared detector reports that no repository was found. */
    async detectRepo(path: string): Promise<string | null> {
        const result = (await this.request('repo/detect', { path })) as
            | { status?: string; repository?: { owner?: string; repo?: string } | null }
            | undefined;
        if (typeof result?.status !== 'string') throw this.invalidResponse('repository detection');
        if (result.status !== 'found') return null;
        const owner = result.repository?.owner;
        const repo = result.repository?.repo;
        if (!owner || !repo) throw this.invalidResponse('repository detection');
        return `${owner}/${repo}`;
    }

    /** Verifies GitHub CLI credentials without returning the token to the extension. */
    async checkGitHubAuth(githubBaseUrl: string): Promise<SidecarGitHubAuthResult> {
        return this.parseResult(
            'GitHub authentication',
            parseGitHubAuthResult,
            await this.request('github/checkAuth', { githubBaseUrl }),
        );
    }

    /** Lists pull requests without returning a GitHub token to the extension. */
    async listPullRequests(
        githubBaseUrl: string,
        state: string,
        searchScope: string,
        currentRepo?: string,
    ): Promise<SidecarPrListResult> {
        return this.parseResult('PR list', parsePrListResult, await this.request('prs/list', {
            githubBaseUrl,
            state,
            searchScope,
            ...(currentRepo ? { currentRepo } : {}),
        }));
    }

    async searchPullRequests(
        githubBaseUrl: string,
        query: string,
        limit: number,
    ): Promise<SidecarPrSearchResult> {
        return this.parseResult('PR search', parsePrSearchResult,
            await this.request('prs/search', { githubBaseUrl, query, limit }));
    }

    async listStarredRepositories(githubBaseUrl: string): Promise<SidecarStarredReposResult> {
        return this.parseResult('starred repositories', parseStarredReposResult,
            await this.request('repos/listStarred', { githubBaseUrl }));
    }

    /** Retrieves PR metadata without returning a GitHub token to the extension. */
    async getPullRequestDetail(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarPrDetailResult> {
        return this.parseResult('PR detail', parsePrDetailResult,
            await this.request('prs/getDetail', { githubBaseUrl, owner, repo, number }));
    }

    async getPullRequestDiff(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        mode: 'review' | 'validation' = 'review',
    ): Promise<SidecarPrDiffResult> {
        return this.parseResult('PR diff', parsePrDiffResult,
            await this.request('prs/getDiff', { githubBaseUrl, owner, repo, number, mode }));
    }

    async getExistingReviews(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarExistingReviewsResult> {
        return this.parseResult('existing reviews', parseExistingReviewsResult,
            await this.request('prs/getExistingReviews', { githubBaseUrl, owner, repo, number }));
    }

    /** Loads a pending review; `none` is a valid domain outcome. */
    async getDraftReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarDraftReviewResult> {
        return this.parseResult('draft review', parseDraftReviewResult,
            await this.request('prs/getDraftReview', { githubBaseUrl, owner, repo, number }));
    }

    /*
     * The four prompt-context reads below are deliberately best-effort and untyped beyond `summary`:
     * a review without them is exactly as good as before they existed, so a CI outage or missing
     * token must degrade the prompt rather than fail the review. Mirrors the same decision on the
     * IntelliJ side (IntellijGitHubService's "do NOT call requireOk" block).
     */

    /** Rendered CI state plus the structured annotations behind it, or empty on any failure. */
    async getCheckStatus(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        headSha: string,
    ): Promise<SidecarCheckStatus> {
        try {
            const value = await this.request('prs/getCheckStatus', { githubBaseUrl, owner, repo, headSha }) as
                { summary?: unknown; annotations?: unknown };
            return {
                summary: typeof value?.summary === 'string' ? value.summary : '',
                annotations: Array.isArray(value?.annotations)
                    ? value.annotations.flatMap((raw) => {
                        const a = raw as Record<string, unknown>;
                        return typeof a?.path === 'string' && typeof a?.message === 'string'
                            ? [{
                                path: a.path,
                                startLine: typeof a.startLine === 'number' ? a.startLine : 0,
                                endLine: typeof a.endLine === 'number' ? a.endLine : 0,
                                level: typeof a.level === 'string' ? a.level : 'warning',
                                message: a.message,
                            }]
                            : [];
                    })
                    : [],
            };
        } catch {
            return { summary: '', annotations: [] };
        }
    }

    /** Rendered commit messages and closing references, or empty values on any failure. */
    async getCommits(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarCommitContext> {
        try {
            const value = await this.request('prs/getCommits', { githubBaseUrl, owner, repo, number });
            return parseCommitContext(value) ?? { summary: '', closingIssueNumbers: [] };
        } catch {
            return { summary: '', closingIssueNumbers: [] };
        }
    }

    /** Rendered linked-issue context, or empty on any failure. */
    async getLinkedIssues(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        prBody: string,
        commitIssueNumbers: readonly number[],
    ): Promise<string> {
        return this.contextSummary(
            'prs/getLinkedIssues',
            { githubBaseUrl, owner, repo, prBody, commitIssueNumbers },
        );
    }

    /** Rendered language/build profile for a checkout, or empty on any failure. */
    async getRepoProfile(projectDir: string): Promise<string> {
        return this.contextSummary('repo/getProfile', { projectDir });
    }

    /**
     * Reads the repository's review-guidance docs (AGENTS.md, CONTRIBUTING.md, configured globs).
     *
     * Resolution lives in the engine rather than here: glob translation, the bounded directory
     * walk, ordering, and the size cap all change what reaches the prompt, and the previous
     * hand-mirrored TypeScript copy had already drifted from the JVM one. Passing an empty `globs`
     * selects the engine's default file list, so this client carries no copy of it.
     *
     * Best-effort like the context reads above — guidance is additive, so a failure degrades the
     * prompt rather than failing the review.
     */
    async readRepoGuidelines(projectDir: string, globs: string[]): Promise<string> {
        try {
            const value = await this.request('reviews/readGuidelines', { projectDir, globs }) as
                { guidelines?: unknown };
            return typeof value?.guidelines === 'string' ? value.guidelines : '';
        } catch {
            return '';
        }
    }

    private async contextSummary(method: string, params: Record<string, unknown>): Promise<string> {
        try {
            const value = await this.request(method, params) as { summary?: unknown };
            return typeof value?.summary === 'string' ? value.summary : '';
        } catch {
            return '';
        }
    }

    /*
     * Worktree lifecycle. The engine owns the whole policy — destination naming, the fork-versus-
     * origin fetch decision, and the head-SHA pinning that keeps the agent reading the code the
     * diff was rendered from. This host keeps only the caching: which directory belongs to the
     * active PR, and when to tear it down. The previous hand-mirrored TypeScript implementation is
     * retired (AGENTS.md guardrail #5).
     */

    /**
     * Returns the git repository root containing `startDir`, or `''` when it is not in one.
     *
     * A blank result is a normal answer, not an error: the only caller uses it to choose between a
     * PR worktree and the user's plain checkout.
     */
    async findGitRoot(startDir: string): Promise<string> {
        try {
            const value = await this.request('reviews/findGitRoot', { startDir }) as { gitRoot?: unknown };
            return typeof value?.gitRoot === 'string' ? value.gitRoot : '';
        } catch {
            return '';
        }
    }

    /**
     * Creates a detached worktree pinned to the PR's head commit.
     *
     * Failure is reported as a `failed` status rather than thrown, matching the engine: callers
     * fall back to the open workspace folder, so a missing worktree degrades review accuracy
     * instead of failing the review. Pass a blank `forkCloneUrl` to fetch from `origin`.
     */
    async createWorktree(
        gitRoot: string,
        prNumber: number,
        branch: string,
        headSha: string,
        forkCloneUrl: string,
    ): Promise<SidecarWorktreeResult> {
        try {
            await this.initialize();
            const value = await this.requestRaw(
                'reviews/createWorktree',
                { gitRoot, prNumber, branch, headSha, forkCloneUrl },
                { timeoutMs: WORKTREE_REQUEST_TIMEOUT_MS },
            ) as { status?: unknown; worktreeDir?: unknown; message?: unknown };
            const status = value?.status === 'created' || value?.status === 'skipped' ? value.status : 'failed';
            const worktreeDir = typeof value?.worktreeDir === 'string' ? value.worktreeDir : '';
            return {
                // A 'created' status with no directory is a contract violation, not a usable
                // worktree; treat it as failure so the caller falls back rather than passing '' as
                // a working directory.
                status: status === 'created' && !worktreeDir ? 'failed' : status,
                worktreeDir,
                message: typeof value?.message === 'string' ? value.message : '',
            };
        } catch (err) {
            return { status: 'failed', worktreeDir: '', message: err instanceof Error ? err.message : String(err) };
        }
    }

    async prepareDeepReview(params: {
        operationId: string; gitRoot: string; prNumber: number; branch: string; headSha: string;
        forkCloneUrl: string; prIdentity: string; diffDigest: string;
    }): Promise<DeepReviewPreparation> {
        await this.initialize();
        const value = await this.requestRaw('reviews/prepareDeepReview', params,
            { timeoutMs: WORKTREE_REQUEST_TIMEOUT_MS }) as Partial<DeepReviewPreparation> | null;
        if (!value || typeof value.retainedId !== 'string' || !/^[0-9a-f-]{36}$/.test(value.retainedId)
            || value.head !== params.headSha || typeof value.worktree !== 'string' || !path.isAbsolute(value.worktree)
            || !Array.isArray(value.servers) || !value.servers.length || value.servers.length > 100
            || !value.servers.every(s => typeof s === 'string' && s.length > 0 && s.length < 256)
            || new Set(value.servers).size !== value.servers.length) {
            throw new Error('Invalid deep preparation response; retained worktrees remain available in cleanup.');
        }
        return value as DeepReviewPreparation;
    }

    async listDeepReviews(): Promise<RetainedDeepReview[]> {
        const value = await this.request('reviews/listDeepReviews', {});
        if (!Array.isArray(value) || value.length > 1000 || !value.every((r: Partial<RetainedDeepReview>) =>
            r && typeof r.id === 'string' && /^[0-9a-f-]{36}$/.test(r.id)
            && typeof r.repository === 'string' && path.isAbsolute(r.repository)
            && typeof r.worktree === 'string' && path.isAbsolute(r.worktree)
            && typeof r.head === 'string' && /^[0-9a-f]{40}(?:[0-9a-f]{24})?$/.test(r.head)
            && typeof r.createdAt === 'number' && Number.isSafeInteger(r.createdAt) && r.createdAt > 0)) {
            throw new Error('Invalid retained review response');
        }
        return value as RetainedDeepReview[];
    }

    async cleanupDeepReview(retainedId: string, projectClosed: boolean): Promise<boolean> {
        const value = await this.request('reviews/cleanupDeepReview', { retainedId, projectClosed }) as { removed?: unknown };
        if (!value || typeof value.removed !== 'boolean') throw new Error('Invalid retained cleanup response');
        return value.removed;
    }

    /** Removes a worktree created by {@link createWorktree}. Cleanup failure is logged, never thrown. */
    async removeWorktree(gitRoot: string, worktreeDir: string): Promise<boolean> {
        try {
            const value = await this.request('reviews/removeWorktree', { gitRoot, worktreeDir }) as { removed?: unknown };
            if (value?.removed === true) return true;
            console.warn(`[pr-pilot] Failed to remove worktree at ${worktreeDir}: engine reported cleanup failure`);
            return false;
        } catch (err) {
            console.warn(`[pr-pilot] Failed to remove worktree at ${worktreeDir}:`,
                err instanceof Error ? err.message : String(err));
            return false;
        }
    }

    /** Saves a pending review without returning a GitHub token to the extension. */
    async saveDraftReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        summary: string,
        verdict: string,
        lineComments: SidecarCommentInput[],
        orphans: SidecarCommentInput[],
    ): Promise<SidecarDraftReviewMutationResult> {
        return this.parseResult('save draft review', parseDraftReviewMutationResult,
            await this.request('prs/saveDraftReview', {
                githubBaseUrl, owner, repo, number, summary, verdict, lineComments, orphans,
            }));
    }

    /** Submits a pending review without returning a GitHub token to the extension. */
    async submitReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        reviewId: string,
        event: string,
        body: string,
    ): Promise<SidecarDraftReviewMutationResult> {
        return this.parseResult('submit review', parseDraftReviewMutationResult,
            await this.request('prs/submitReview', {
                githubBaseUrl, owner, repo, number, reviewId, event, body,
            }));
    }

    /** Deletes a pending review without returning a GitHub token to the extension. */
    async deleteDraftReview(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
        reviewId: string,
    ): Promise<SidecarDraftReviewMutationResult> {
        return this.parseResult('delete draft review', parseDraftReviewMutationResult,
            await this.request('prs/deleteDraftReview', {
                githubBaseUrl, owner, repo, number, reviewId,
            }));
    }

    private parseResult<T>(description: string, parser: (value: unknown) => T | null, value: unknown): T {
        const result = parser(value);
        if (!result) throw this.invalidResponse(description);
        return result;
    }

    private invalidResponse(description: string): Error {
        return new Error(`PR Pilot Java sidecar returned an invalid ${description} response.`);
    }

    private processExitError(code: number | null, signal: NodeJS.Signals | null): Error {
        const diagnostics = this.stderr.trim();
        if (/UnsupportedClassVersionError|class file version/i.test(diagnostics)) {
            return new StickySidecarFailure(
                'PR Pilot requires Java 17 or newer. Update Java and reload VS Code.',
            );
        }
        if (/Unable to access jarfile|Invalid or corrupt jarfile/i.test(diagnostics)) {
            return new StickySidecarFailure(
                'PR Pilot Java sidecar could not be opened. Reinstall the extension.',
            );
        }
        const reason = signal ? `signal ${signal}` : `exit code ${code ?? 'unknown'}`;
        const diagnosticLines = diagnostics.split(/\r?\n/);
        const detail = diagnostics ? ` ${diagnosticLines[diagnosticLines.length - 1]}` : '';
        return new Error(`PR Pilot Java sidecar exited unexpectedly (${reason}).${detail}`);
    }

    private stopChild(): void {
        const child = this.child;
        this.child = null;
        if (child) {
            child.stdin.end();
            child.kill();
        }
    }

    dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.stopChild();
        this.failAllPending(new Error('PR Pilot Java sidecar has been disposed.'));
    }
}

/**
 * Resolves the sidecar jar to spawn: the packaged copy staged alongside the extension by
 * `scripts/stage-sidecar.mjs` (release/`.vsix` builds), falling back to the sibling Gradle
 * module's `build/libs` bootJar output for local development. Returns `null` when no jar can be
 * found so the client can surface an actionable installation error on first use.
 */
export function resolveSidecarJarPath(
    extensionRoot: string,
    existsSync: (candidate: string) => boolean = fs.existsSync,
    readdirSync: (dir: string) => string[] = (dir) => fs.readdirSync(dir),
): string | null {
    const packagedJar = path.join(extensionRoot, 'sidecar', 'pr-pilot-sidecar.jar');
    if (existsSync(packagedJar)) return packagedJar;

    const devLibsDir = path.resolve(extensionRoot, '..', 'sidecar', 'build', 'libs');
    if (!existsSync(devLibsDir)) return null;
    try {
        const candidate = readdirSync(devLibsDir).find((name) => name === 'pr-pilot-sidecar.jar');
        return candidate ? path.join(devLibsDir, candidate) : null;
    } catch {
        return null;
    }
}
