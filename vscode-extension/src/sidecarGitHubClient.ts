import { SidecarTransport } from './sidecarTransport';
import {
    parseCommitContext,
    parseDraftReviewMutationResult,
    parseDraftReviewResult,
    parseExistingReviewsResult,
    parseGitHubAuthResult,
    parseIncrementalDiffResult,
    parsePrDetailResult,
    parsePrDiffResult,
    parsePrListResult,
    parsePrSearchResult,
    parseStarredReposResult,
} from './sidecarProtocol';
import type {
    SidecarCheckStatus,
    SidecarCommitContext,
    SidecarCommentInput,
    SidecarDraftReviewMutationResult,
    SidecarDraftReviewResult,
    SidecarExistingReviewsResult,
    SidecarGitHubAuthResult,
    SidecarIncrementalDiffResult,
    SidecarPrDetailResult,
    SidecarPrDiffResult,
    SidecarPrListResult,
    SidecarPrSearchResult,
    SidecarStarredReposResult,
} from './sidecarTypes';

export class SidecarGitHubClient extends SidecarTransport {
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

    /** Changes since the caller's last submitted review, or the reason to review the full PR. */
    async getIncrementalDiff(
        githubBaseUrl: string,
        owner: string,
        repo: string,
        number: number,
    ): Promise<SidecarIncrementalDiffResult> {
        return this.parseResult('incremental diff', parseIncrementalDiffResult,
            await this.request('prs/getIncrementalDiff', { githubBaseUrl, owner, repo, number }));
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

    protected async contextSummary(method: string, params: Record<string, unknown>): Promise<string> {
        try {
            const value = await this.request(method, params) as { summary?: unknown };
            return typeof value?.summary === 'string' ? value.summary : '';
        } catch {
            return '';
        }
    }

    /** Rendered language/build profile for a checkout, or empty on any failure. */
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
}
