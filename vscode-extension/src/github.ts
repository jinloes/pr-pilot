import * as https from 'https';
import * as http from 'http';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { execFile } from 'child_process';
import { promisify } from 'util';

const execFileAsync = promisify(execFile);

export const MAX_DIFF_BYTES = 80_000;

const VERDICT_TAG = '<!-- claude-verdict: ';
const SUMMARY_TAG = '<!-- claude-summary: ';
const COMMENTS_TAG = '<!-- claude-comments: ';
const TAG_END = ' -->';
const TYPE_PREFIX = /^\[([A-Z]+)]\s*/;
const DETACHED_COMMENTS_HEADER = '**Comments not attached inline (invalid diff positions):**';

export type Severity = 'blocker' | 'major' | 'minor' | 'nit';
export type Category = 'correctness' | 'security' | 'performance' | 'tests' | 'maintainability' | 'style';
export type Confidence = 'low' | 'medium' | 'high';

// ── Types ──────────────────────────────────────────────────────────────────────

export interface PR {
    number: number;
    title: string;
    owner: string;
    repo: string;
    author: string;
    createdAt: string;
    htmlUrl: string;
    hasDraft: boolean;
}

export interface LineComment {
    file: string;
    line: number;
    type: 'issue' | 'suggestion' | 'note';
    body: string;
    severity?: Severity;
    category?: Category;
    confidence?: Confidence;
    rationale?: string;
}

export interface ReviewResult {
    summary: string;
    verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT';
    lineComments: LineComment[];
}

export type PRSearchScope = 'currentRepo' | 'authored' | 'assigned' | 'reviewRequested';

export interface GhReviewComment {
    path: string | null;
    line: number | null;
    original_line: number | null;
    body: string | null;
}

interface GhReview {
    id: number;
    state: string | null;
    body: string | null;
}

interface GhSubmittedReview {
    id: number;
    user: { login: string } | null;
    state: string | null;
    body: string | null;
    submitted_at: string | null;
}

interface PrDetail {
    merged: boolean;
    title: string | null;
    body: string | null;
    head: {
        sha: string;
        ref: string;
        repo: { full_name: string; clone_url: string } | null;
    } | null;
    base: { repo: { full_name: string } | null } | null;
}

interface SearchItem {
    title: string;
    html_url: string;
    number: number;
    body: string | null;
    user: { login: string } | null;
    created_at: string | null;
    repository_url: string | null;
}

interface StarredRepo {
    full_name?: string;
}

// ── Helpers ────────────────────────────────────────────────────────────────────

export function apiBase(githubBaseUrl: string): string {
    const url = normalizeGithubBaseUrl(githubBaseUrl);
    if (url === 'https://github.com') return 'https://api.github.com';
    return `${url}/api/v3`;
}

export function normalizeGithubBaseUrl(githubBaseUrl: string): string {
    const url = (githubBaseUrl || 'https://github.com').trim().replace(/\/$/, '');
    if (!url.startsWith('https://')) {
        throw new Error('GitHub base URL must start with https://');
    }
    return url;
}

function findGhBinary(): string {
    const candidates = [
        '/opt/homebrew/bin/gh',
        '/usr/local/bin/gh',
        '/usr/bin/gh',
        '/home/linuxbrew/.linuxbrew/bin/gh',
    ];
    for (const p of candidates) {
        try {
            if (fs.statSync(p).isFile()) return p;
        } catch { /* not found */ }
    }
    return 'gh';
}

export async function resolveToken(githubBaseUrl: string): Promise<string> {
    const args = ['auth', 'token'];
    const base = normalizeGithubBaseUrl(githubBaseUrl);
    if (base && base !== 'https://github.com') {
        try {
            const hostname = new URL(base).hostname;
            if (hostname) args.push('--hostname', hostname);
        } catch { /* ignore invalid URL */ }
    }
    const { stdout } = await execFileAsync(findGhBinary(), args, {
        timeout: 15_000,
        env: { ...process.env, HOME: process.env.HOME || os.homedir() },
    });
    const token = stdout.trim();
    if (!token) throw new Error("gh auth token returned empty — run 'gh auth login' in a terminal first.");
    return token;
}

function ghRequest(token: string, url: string, options: {
    method?: string;
    accept?: string;
    body?: string;
}): Promise<string> {
    return new Promise((resolve, reject) => {
        const { method = 'GET', accept = 'application/vnd.github.v3+json', body } = options;
        const parsedUrl = new URL(url);
        const reqOptions = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + (parsedUrl.search || ''),
            port: parsedUrl.port || (parsedUrl.protocol === 'http:' ? 80 : 443),
            method,
            headers: {
                'Authorization': `Bearer ${token}`,
                'Accept': accept,
                'X-GitHub-Api-Version': '2022-11-28',
                'User-Agent': 'claude-reviews-vscode/0.1.0',
                ...(body ? {
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(body),
                } : {}),
            },
        };

        const lib = parsedUrl.protocol === 'http:' ? http : https;
        const req = lib.request(reqOptions, (res) => {
            const chunks: Buffer[] = [];
            res.on('data', (chunk: Buffer) => chunks.push(chunk));
            res.on('end', () => {
                const responseBody = Buffer.concat(chunks).toString('utf8');
                const status = res.statusCode ?? 0;
                if (status < 200 || status >= 300) {
                    reject(new Error(`GitHub API ${method} ${status}: ${responseBody.substring(0, 300)}`));
                } else {
                    resolve(responseBody);
                }
            });
        });
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

// ── PR search ──────────────────────────────────────────────────────────────────

/** Maximum PRs shown in the list. Search over-fetches by one to detect truncation accurately. */
export const PR_SEARCH_LIMIT = 50;

export async function searchPRs(
    token: string,
    githubBaseUrl: string,
    state: string,
    searchScope: PRSearchScope,
    currentRepo?: string,
): Promise<PR[]> {
    const q = buildPRSearchQuery(state, searchScope, currentRepo);
    // Over-fetch by one so the caller can tell "exactly the limit" from "more exist".
    return searchPRsByQuery(token, githubBaseUrl, q, PR_SEARCH_LIMIT + 1);
}

export async function searchPRsByQuery(
    token: string,
    githubBaseUrl: string,
    q: string,
    perPage = PR_SEARCH_LIMIT,
): Promise<PR[]> {
    const url = `${apiBase(githubBaseUrl)}/search/issues?q=${encodeURIComponent(q)}&per_page=${perPage}&sort=updated`;
    const body = await ghRequest(token, url, {});
    const result: { items?: SearchItem[] } = JSON.parse(body);
    return (result.items ?? []).map(el => {
        const parts = (el.repository_url ?? '').split('/');
        const owner = parts.length >= 2 ? parts[parts.length - 2] : '';
        const repo = parts.length >= 1 ? parts[parts.length - 1] : '';
        return {
            number: el.number,
            title: el.title,
            owner,
            repo,
            author: el.user?.login ?? '',
            createdAt: el.created_at ?? '',
            htmlUrl: el.html_url,
            hasDraft: false,
        };
    });
}

export function buildPRSearchQuery(
    state: string,
    searchScope: PRSearchScope,
    currentRepo?: string,
): string {
    let q = 'is:pr';
    if (state === 'closed') q += ' is:closed';
    else if (state !== 'all') q += ' is:open';
    q += ' draft:false';

    switch (searchScope) {
        case 'currentRepo':
            q += currentRepo ? ` repo:${currentRepo}` : ' author:@me';
            break;
        case 'assigned':
            q += ' assignee:@me';
            break;
        case 'reviewRequested':
            q += ' review-requested:@me';
            break;
        case 'authored':
            q += ' author:@me';
            break;
    }

    return q;
}

export async function getStarredRepos(
    token: string,
    githubBaseUrl: string,
): Promise<string[]> {
    const repos: string[] = [];
    for (let page = 1; repos.length < 200; page++) {
        const url = `${apiBase(githubBaseUrl)}/user/starred?per_page=100&sort=updated&page=${page}`;
        const body = await ghRequest(token, url, {});
        const items = JSON.parse(body) as StarredRepo[];
        if (items.length === 0) break;
        for (const item of items) {
            if (item.full_name) repos.push(item.full_name);
        }
        if (items.length < 100) break;
    }
    return repos;
}

// ── PR details ─────────────────────────────────────────────────────────────────

export async function getPRDiff(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    prNumber: number,
): Promise<string> {
    const url = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${prNumber}`;
    const diff = await ghRequest(token, url, { accept: 'application/vnd.github.v3.diff' });
    return diff.length > MAX_DIFF_BYTES
        ? diff.substring(0, MAX_DIFF_BYTES) + '\n\n[... diff truncated at 80 KB ...]'
        : diff;
}

export async function getPRDiffFull(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    prNumber: number,
): Promise<string> {
    const url = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${prNumber}`;
    return ghRequest(token, url, { accept: 'application/vnd.github.v3.diff' });
}

export async function getPRDetail(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    prNumber: number,
): Promise<PrDetail> {
    const url = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${prNumber}`;
    return JSON.parse(await ghRequest(token, url, {})) as PrDetail;
}

/** Fetches the head branch name and fork status for a PR. Mirrors GitHubService.getPRHeadInfo. */
export async function getPRHeadInfo(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    prNumber: number,
): Promise<{ ref: string; isFork: boolean; forkCloneUrl: string }> {
    const detail = await getPRDetail(token, githubBaseUrl, owner, repo, prNumber);
    const ref = detail.head?.ref ?? '';
    const headFullName = detail.head?.repo?.full_name ?? `${owner}/${repo}`;
    const baseFullName = detail.base?.repo?.full_name ?? `${owner}/${repo}`;
    const isFork = headFullName !== '' && headFullName !== baseFullName;
    return { ref, isFork, forkCloneUrl: isFork ? (detail.head?.repo?.clone_url ?? '') : '' };
}

export async function getExistingReviewsSummary(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
): Promise<string> {
    const url = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${number}/reviews`;
    const reviews: GhSubmittedReview[] = JSON.parse(await ghRequest(token, url, {}));
    const submitted = reviews.filter(r => r.state !== 'PENDING');
    if (submitted.length === 0) return '';

    // Fetch every review's inline comments in parallel (order-preserving) to avoid an
    // N+1 sequential waterfall on PRs with many prior reviews. Concurrency is bounded to
    // stay well under GitHub's secondary rate limits on bursts of simultaneous requests.
    const CONCURRENCY = 5;
    const commentsPerReview: GhReviewComment[][] = [];
    for (let i = 0; i < submitted.length; i += CONCURRENCY) {
        const chunk = await Promise.all(
            submitted.slice(i, i + CONCURRENCY).map(r =>
                ghRequest(token, `${url}/${r.id}/comments`, {})
                    .then(body => JSON.parse(body) as GhReviewComment[])
                    .catch(() => [] as GhReviewComment[]),
            ),
        );
        commentsPerReview.push(...chunk);
    }

    const lines: string[] = [];
    submitted.forEach((r, i) => {
        const reviewer = r.user ? `@${r.user.login}` : 'unknown';
        const state = r.state ?? 'COMMENTED';
        const date = r.submitted_at?.substring(0, 10) ?? '';
        lines.push(`Review by ${reviewer} (${state}${date ? `, ${date}` : ''}):`);
        const reviewBody = r.body?.trim() ?? '';
        if (reviewBody) {
            lines.push(`  Overall: "${reviewBody.replace(/\n/g, ' ').substring(0, 300)}"`);
        }
        for (const c of commentsPerReview[i]) {
            const text = c.body?.trim()?.replace(/\n/g, ' ') ?? '';
            if (!text) continue;
            const line = c.line ?? c.original_line ?? 0;
            lines.push(`  - ${c.path ?? ''}${line > 0 ? `:${line}` : ''}: "${text.substring(0, 200)}"`);
        }
        lines.push('');
    });
    return lines.join('\n').trim();
}

// ── Draft review ───────────────────────────────────────────────────────────────

async function getPendingReviewId(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
): Promise<string | null> {
    const url = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${number}/reviews`;
    const reviews: GhReview[] = JSON.parse(await ghRequest(token, url, {}));
    const pending = reviews.find(r => r.state === 'PENDING');
    return pending ? String(pending.id) : null;
}

function escapeComment(s: string): string {
    return s.replace(/-->/g, '-- >');
}

function encodeBody(review: ReviewResult): string {
    const comments = review.lineComments.map(c => ({
        f: c.file, l: c.line, t: c.type, b: c.body,
        s: c.severity, c: c.category, cf: c.confidence, r: c.rationale,
    }));
    let body = `${SUMMARY_TAG}${escapeComment(review.summary)}${TAG_END}`;
    body += `\n${VERDICT_TAG}${escapeComment(review.verdict)}${TAG_END}`;
    body += `\n${COMMENTS_TAG}${JSON.stringify(comments).replace(/-->/g, '-- >')}${TAG_END}`;
    const general = review.lineComments.filter(c => !c.file || c.line <= 0);
    if (general.length > 0) {
        body += '\n\n**General Notes:**';
        for (const c of general) body += `\n- ${c.body}`;
    }
    return body;
}

function decodeReview(body: string, apiComments: GhReviewComment[]): ReviewResult {
    let verdict: ReviewResult['verdict'] = 'COMMENT';
    const vi = body.indexOf(VERDICT_TAG);
    if (vi >= 0) {
        const ei = body.indexOf(TAG_END, vi + VERDICT_TAG.length);
        if (ei >= 0) verdict = body.substring(vi + VERDICT_TAG.length, ei).trim() as ReviewResult['verdict'];
    }

    let summary = '';
    const si = body.indexOf(SUMMARY_TAG);
    if (si >= 0) {
        const se = body.indexOf(TAG_END, si + SUMMARY_TAG.length);
        if (se >= 0) summary = body.substring(si + SUMMARY_TAG.length, se).trim();
    }

    const embeddedIdx = body.indexOf(COMMENTS_TAG);
    if (embeddedIdx >= 0) {
        const endIdx = body.indexOf(TAG_END, embeddedIdx + COMMENTS_TAG.length);
        if (endIdx >= 0) {
            const json = body.substring(embeddedIdx + COMMENTS_TAG.length, endIdx).trim();
            try {
                const arr: Array<{
                    f: string; l: number; t: string; b: string;
                    s?: string; c?: string; cf?: string; r?: string;
                }> = JSON.parse(json);
                return {
                    summary, verdict,
                    lineComments: arr.map(e => ({
                        file: e.f, line: e.l,
                        type: e.t as LineComment['type'],
                        body: e.b,
                        severity: e.s as Severity | undefined,
                        category: e.c as Category | undefined,
                        confidence: e.cf as Confidence | undefined,
                        rationale: e.r,
                    })),
                };
            } catch { /* fall through to API comments */ }
        }
    }

    return {
        summary, verdict,
        lineComments: apiComments.map(c => {
            let text = c.body ?? '';
            let type: LineComment['type'] = 'note';
            const m = TYPE_PREFIX.exec(text);
            if (m) { type = m[1].toLowerCase() as LineComment['type']; text = text.substring(m[0].length); }
            return { file: c.path ?? '', line: c.line ?? c.original_line ?? 0, type, body: text };
        }),
    };
}

function buildCommentArray(review: ReviewResult, orphans: LineComment[] = []): object[] {
    const seen = new Set<string>();
    const orphanKeys = new Set(orphans.map(o => `${o.file}|${o.line}|${o.type}|${o.body}`));
    const result: object[] = [];
    for (const c of review.lineComments) {
        let file = c.file;
        if (file.startsWith('a/') || file.startsWith('b/')) file = file.substring(2);
        if (!file || c.line <= 0 || !c.body) continue;
        // Pre-known orphans go in the body section, not as inline comments.
        if (orphanKeys.has(`${c.file}|${c.line}|${c.type}|${c.body}`)) continue;
        const key = `${file}\u0000${c.line}\u0000${c.body}`;
        if (seen.has(key)) continue;
        seen.add(key);
        result.push({ path: file, line: c.line, side: 'RIGHT', body: c.body });
    }
    return result;
}

function buildOrphanSection(orphans: LineComment[]): string {
    let s = `${DETACHED_COMMENTS_HEADER}\n`;
    for (const c of orphans) {
        const path = c.file ?? '';
        const line = c.line ?? 0;
        const body = c.body ?? '';
        s += formatDetachedCommentLine(path, line, body);
    }
    return s.trimEnd();
}

function buildDroppedSection(dropped: Array<Record<string, unknown>>): string {
    let s = `${DETACHED_COMMENTS_HEADER}\n`;
    for (const c of dropped) {
        const path = typeof c['path'] === 'string' ? c['path'] : '';
        const line = typeof c['line'] === 'number' ? c['line'] : 0;
        const body = typeof c['body'] === 'string' ? c['body'] : '';
        s += formatDetachedCommentLine(path, line, body);
    }
    return s.trimEnd();
}

function formatDetachedCommentLine(path: string, line: number, body: string): string {
    return `- \`${path}${line > 0 ? `:${line}` : ''}\`: ${body}\n`;
}

// Removed: tryCommentsIndividually — the probe-review approach created orphaned pending reviews
// that showed up as duplicate comments in the PR diff view. See saveDraftReview fallback instead.

export async function loadDraftReview(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
): Promise<{ id: string; result: ReviewResult; importedFromGitHub: boolean } | null> {
    const id = await getPendingReviewId(token, githubBaseUrl, owner, repo, number);
    if (!id) return null;
    const reviewUrl = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${number}/reviews/${id}`;
    const [reviewBody, commentsBody] = await Promise.all([
        ghRequest(token, reviewUrl, {}),
        ghRequest(token, `${reviewUrl}/comments`, {}),
    ]);
    const review: GhReview = JSON.parse(reviewBody);
    const ghComments: GhReviewComment[] = JSON.parse(commentsBody);
    const body = review.body ?? '';
    return { id, result: decodeReview(body, ghComments), importedFromGitHub: !hasUsableEmbeddedComments(body) };
}

function hasUsableEmbeddedComments(body: string): boolean {
    const embeddedIdx = body.indexOf(COMMENTS_TAG);
    if (embeddedIdx < 0) return false;
    const endIdx = body.indexOf(TAG_END, embeddedIdx + COMMENTS_TAG.length);
    if (endIdx < 0) return false;
    const json = body.substring(embeddedIdx + COMMENTS_TAG.length, endIdx).trim();
    try {
        return Array.isArray(JSON.parse(json));
    } catch {
        return false;
    }
}

export async function saveDraftReview(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
    review: ReviewResult,
    orphans: LineComment[] = [],
): Promise<{ reviewId: string; commentsDropped: boolean }> {
    const base = apiBase(githubBaseUrl);
    const existing = await getPendingReviewId(token, githubBaseUrl, owner, repo, number);
    if (existing) {
        try { await ghRequest(token, `${base}/repos/${owner}/${repo}/pulls/${number}/reviews/${existing}`, { method: 'DELETE' }); }
        catch { /* non-fatal */ }
    }

    const detail = await getPRDetail(token, githubBaseUrl, owner, repo, number);
    const headSha = detail.head?.sha ?? '';
    const comments = buildCommentArray(review, orphans);
    const url = `${base}/repos/${owner}/${repo}/pulls/${number}/reviews`;
    const bodyWithOrphans = orphans.length > 0
        ? `${encodeBody(review)}\n\n${buildOrphanSection(orphans)}`
        : encodeBody(review);
    const payload: Record<string, unknown> = { commit_id: headSha, body: bodyWithOrphans, comments };
    let commentsDropped = false;

    try {
        const resp = await ghRequest(token, url, { method: 'POST', body: JSON.stringify(payload) });
        return { reviewId: (JSON.parse(resp) as { id?: number })?.id?.toString() ?? '', commentsDropped };
    } catch (ex: unknown) {
        if (ex instanceof Error && ex.message.includes('422')) {
            // One or more inline comments reference an invalid path or line. Create the review
            // body-only first (guaranteed to succeed), then add each comment individually so
            // only the bad ones are dropped. This avoids creating orphaned probe reviews.
            const bodyResp = await ghRequest(token, url, { method: 'POST', body: JSON.stringify({ ...payload, comments: [] }) });
            const reviewId = (JSON.parse(bodyResp) as { id?: number })?.id?.toString() ?? '';
            const commentsUrl = `${url}/${reviewId}/comments`;
            const droppedComments: Array<Record<string, unknown>> = [];
            for (const c of comments) {
                try {
                    await ghRequest(token, commentsUrl, { method: 'POST', body: JSON.stringify(c) });
                } catch { droppedComments.push(c as Record<string, unknown>); }
            }
            if (droppedComments.length > 0) {
                const section = buildDroppedSection(droppedComments);
                // Preserve any pre-known orphan section we already added so its entries
                // aren't lost when we PUT the updated body.
                const updatedBody = `${bodyWithOrphans}\n\n${section}`;
                try {
                    await ghRequest(token, `${url}/${reviewId}`, { method: 'PUT', body: JSON.stringify({ body: updatedBody }) });
                } catch { commentsDropped = true; }
            }
            return { reviewId, commentsDropped };
        }
        throw ex;
    }
}

export async function submitReview(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
    reviewId: string,
    event: string,
    body: string,
): Promise<void> {
    const url = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${number}/reviews/${reviewId}/events`;
    await ghRequest(token, url, { method: 'POST', body: JSON.stringify({ event, body: effectiveBody(event, body) }) });
}

// GitHub rejects REQUEST_CHANGES/COMMENT submissions with an empty body
// (422: "You need to leave a comment indicating the requested changes."),
// so a placeholder is required when the caller does not supply one.
export function effectiveBody(event: string, body: string): string {
    if (body && body.trim().length > 0) return body;
    if (event === 'APPROVE') return 'Looks good to me!';
    if (event === 'REQUEST_CHANGES') return 'Requesting changes.';
    if (event === 'COMMENT') return 'Leaving comments.';
    return body;
}

export async function deleteDraftReview(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
    reviewId: string,
): Promise<void> {
    const url = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${number}/reviews/${reviewId}`;
    await ghRequest(token, url, { method: 'DELETE' });
}

// ── Repo auto-detection ────────────────────────────────────────────────────────

function findGitConfig(dir: string): string | null {
    let current = path.resolve(dir);
    while (true) {
        const candidate = path.join(current, '.git', 'config');
        if (fs.existsSync(candidate)) return candidate;
        const parent = path.dirname(current);
        if (parent === current) return null;
        current = parent;
    }
}

function parseOwnerRepo(remoteUrl: string): string | null {
    const raw = remoteUrl.trim();
    if (!raw) return null;
    // scp-style: git@github.com:owner/repo.git
    const scp = /^[^@]+@[^:]+:([^/]+\/[^/]+?)(?:\.git)?$/.exec(raw);
    if (scp) return scp[1];
    // https or ssh URL: https://github.com/owner/repo.git
    try {
        const u = new URL(raw);
        const parts = u.pathname.replace(/^\//, '').replace(/\.git$/, '').split('/');
        if (parts.length >= 2 && parts[0] && parts[1]) return `${parts[0]}/${parts[1]}`;
    } catch { /* not a URL */ }
    return null;
}

/**
 * Extracts the `url` of the `[remote "origin"]` section from git config text. Scoping to origin
 * (rather than the first `url=` in the file) matches IntelliJ's `RepoDetector` and avoids picking
 * an unrelated remote (e.g. `upstream`) in multi-remote/fork setups. Returns null if origin has no
 * url.
 */
function extractOriginUrl(gitConfigText: string): string | null {
    let inOrigin = false;
    for (const line of gitConfigText.split('\n')) {
        const trimmed = line.trim();
        if (trimmed === '[remote "origin"]') {
            inOrigin = true;
        } else if (inOrigin && trimmed.startsWith('[')) {
            break;
        } else if (inOrigin && /^url\s*=/.test(trimmed)) {
            return trimmed.substring(trimmed.indexOf('=') + 1).trim();
        }
    }
    return null;
}

export function detectCurrentRepo(workspaceFolder: string): string | null {
    try {
        const configPath = findGitConfig(workspaceFolder);
        if (!configPath) return null;
        const originUrl = extractOriginUrl(fs.readFileSync(configPath, 'utf8'));
        return originUrl ? parseOwnerRepo(originUrl) : null;
    } catch {
        return null;
    }
}
