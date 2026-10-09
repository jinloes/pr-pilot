import type { LineComment, ReviewResult } from './models';
import type {
    SidecarDraftReviewMutationResult,
    SidecarDraftReviewResult,
    SidecarExistingReviewsResult,
    SidecarGitHubAuthResult,
    SidecarInitializeResult,
    SidecarPrDetailResult,
    SidecarIncrementalDiffResult,
    SidecarIncrementalFallbackReason,
    SidecarPrDiffResult,
    SidecarPrListResult,
    SidecarPrSearchResult,
    SidecarStarredReposResult,
    SidecarCommitContext,
    SidecarLineComment,
    SidecarPrDetailHead,
} from './sidecarTypes';

const MAX_LINKED_ISSUES = 3;
const MAX_GITHUB_ISSUE_NUMBER = 999_999_999;

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
    'prIncrementalDiff',
] as const;

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
        if (Array.isArray(comment.sources) && comment.sources.every((source) => typeof source === 'string')) {
            parsed.sources = [...comment.sources];
        }
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

const INCREMENTAL_FALLBACK_REASONS = new Set<string>([
    'no_prior_review',
    'up_to_date',
    'baseline_not_in_history',
    'baseline_unavailable',
    'empty_incremental_diff',
    'incremental_diff_too_large',
]);
const SHA = /^[0-9a-f]{40}$/;

/** Validates the token-free result shape returned by `prs/getIncrementalDiff`. */
export function parseIncrementalDiffResult(value: unknown): SidecarIncrementalDiffResult | null {
    if (!value || typeof value !== 'object') return null;
    const result = value as Record<string, unknown>;
    if (typeof result.status !== 'string' || typeof result.message !== 'string'
        || typeof result.limitBytes !== 'number') return null;
    const { message, limitBytes } = result;
    if (result.status !== 'ok') {
        if (!PR_DIFF_STATUSES.has(result.status as SidecarPrDiffResult['status'])
            || result.status === 'diff_too_large' || result.scope != null) return null;
        return {
            status: result.status as Exclude<SidecarPrDiffResult['status'], 'ok' | 'diff_too_large'>,
            message,
            limitBytes,
        };
    }
    if (result.scope === 'incremental') {
        if (typeof result.baselineSha !== 'string' || !SHA.test(result.baselineSha)
            || typeof result.headSha !== 'string' || !SHA.test(result.headSha)
            || typeof result.diff !== 'string' || !result.diff.trim()
            || typeof result.truncated !== 'boolean') return null;
        return { status: 'ok', message, scope: 'incremental', baselineSha: result.baselineSha,
            headSha: result.headSha, diff: result.diff, truncated: result.truncated, limitBytes };
    }
    if (result.scope === 'full') {
        if (typeof result.fallbackReason !== 'string' || !INCREMENTAL_FALLBACK_REASONS.has(result.fallbackReason)) {
            return null;
        }
        return { status: 'ok', message, scope: 'full',
            fallbackReason: result.fallbackReason as SidecarIncrementalFallbackReason, limitBytes };
    }
    return null;
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
