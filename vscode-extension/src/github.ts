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
}

export interface ReviewResult {
    summary: string;
    verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT';
    lineComments: LineComment[];
}

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
    head: { sha: string } | null;
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

// ── Helpers ────────────────────────────────────────────────────────────────────

export function apiBase(githubBaseUrl: string): string {
    const url = (githubBaseUrl || 'https://github.com').replace(/\/$/, '');
    if (url === 'https://github.com') return 'https://api.github.com';
    return `${url}/api/v3`;
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
    const base = (githubBaseUrl || '').replace(/\/$/, '');
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

export async function searchPRs(
    token: string,
    githubBaseUrl: string,
    state: string,
    assignedToMe: boolean,
    reviewRequested: boolean,
    currentRepo?: string,
): Promise<PR[]> {
    let q = 'is:pr';
    if (state === 'closed') q += ' is:closed';
    else if (state !== 'all') q += ' is:open';
    q += ' draft:false';
    if (currentRepo) {
        q += ` repo:${currentRepo}`;
    } else if (!assignedToMe && !reviewRequested) {
        q += ' author:@me';
    }
    if (assignedToMe) q += ' assignee:@me';
    if (reviewRequested) q += ' review-requested:@me';

    const url = `${apiBase(githubBaseUrl)}/search/issues?q=${encodeURIComponent(q)}&per_page=50&sort=updated`;
    const body = await ghRequest(token, url, {});
    const result: { items: SearchItem[] } = JSON.parse(body);
    return result.items.map(el => {
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

    const lines: string[] = [];
    for (const r of submitted) {
        const reviewer = r.user ? `@${r.user.login}` : 'unknown';
        const state = r.state ?? 'COMMENTED';
        const date = r.submitted_at?.substring(0, 10) ?? '';
        lines.push(`Review by ${reviewer} (${state}${date ? `, ${date}` : ''}):`);
        const reviewBody = r.body?.trim() ?? '';
        if (reviewBody) {
            lines.push(`  Overall: "${reviewBody.replace(/\n/g, ' ').substring(0, 300)}"`);
        }
        try {
            const commentsBody = await ghRequest(token, `${url}/${r.id}/comments`, {});
            const comments: GhReviewComment[] = JSON.parse(commentsBody);
            for (const c of comments) {
                const text = c.body?.trim()?.replace(/\n/g, ' ') ?? '';
                if (!text) continue;
                const line = c.line ?? c.original_line ?? 0;
                lines.push(`  - ${c.path ?? ''}${line > 0 ? `:${line}` : ''}: "${text.substring(0, 200)}"`);
            }
        } catch { /* non-fatal */ }
        lines.push('');
    }
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
    const comments = review.lineComments.map(c => ({ f: c.file, l: c.line, t: c.type, b: c.body }));
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
                const arr: Array<{ f: string; l: number; t: string; b: string }> = JSON.parse(json);
                return {
                    summary, verdict,
                    lineComments: arr.map(e => ({
                        file: e.f, line: e.l,
                        type: e.t as LineComment['type'],
                        body: e.b,
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

function buildCommentArray(review: ReviewResult): object[] {
    const seen = new Set<string>();
    const result: object[] = [];
    for (const c of review.lineComments) {
        let file = c.file;
        if (file.startsWith('a/') || file.startsWith('b/')) file = file.substring(2);
        if (!file || c.line <= 0 || !c.body) continue;
        const key = `${file} ${c.line} ${c.body}`;
        if (seen.has(key)) continue;
        seen.add(key);
        result.push({ path: file, line: c.line, side: 'RIGHT', body: c.body });
    }
    return result;
}

async function tryCommentsIndividually(
    token: string,
    url: string,
    basePayload: Record<string, unknown>,
    comments: object[],
): Promise<object[]> {
    const valid: object[] = [];
    for (const c of comments) {
        try {
            const probe = JSON.stringify({ ...basePayload, comments: [c] });
            const resp = await ghRequest(token, url, { method: 'POST', body: probe });
            const tempId = (JSON.parse(resp) as { id?: number })?.id?.toString();
            if (tempId) {
                try { await ghRequest(token, `${url}/${tempId}`, { method: 'DELETE' }); } catch { /* best-effort */ }
            }
            valid.push(c);
        } catch { /* skip invalid comment */ }
    }
    return valid;
}

export async function loadDraftReview(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
): Promise<{ id: string; result: ReviewResult } | null> {
    const id = await getPendingReviewId(token, githubBaseUrl, owner, repo, number);
    if (!id) return null;
    const reviewUrl = `${apiBase(githubBaseUrl)}/repos/${owner}/${repo}/pulls/${number}/reviews/${id}`;
    const [reviewBody, commentsBody] = await Promise.all([
        ghRequest(token, reviewUrl, {}),
        ghRequest(token, `${reviewUrl}/comments`, {}),
    ]);
    const review: GhReview = JSON.parse(reviewBody);
    const ghComments: GhReviewComment[] = JSON.parse(commentsBody);
    return { id, result: decodeReview(review.body ?? '', ghComments) };
}

export async function saveDraftReview(
    token: string,
    githubBaseUrl: string,
    owner: string,
    repo: string,
    number: number,
    review: ReviewResult,
): Promise<{ reviewId: string; commentsDropped: boolean }> {
    const base = apiBase(githubBaseUrl);
    const existing = await getPendingReviewId(token, githubBaseUrl, owner, repo, number);
    if (existing) {
        try { await ghRequest(token, `${base}/repos/${owner}/${repo}/pulls/${number}/reviews/${existing}`, { method: 'DELETE' }); }
        catch { /* non-fatal */ }
    }

    const detail = await getPRDetail(token, githubBaseUrl, owner, repo, number);
    const headSha = detail.head?.sha ?? '';
    const comments = buildCommentArray(review);
    const url = `${base}/repos/${owner}/${repo}/pulls/${number}/reviews`;
    const payload: Record<string, unknown> = { commit_id: headSha, body: encodeBody(review), comments };
    let commentsDropped = false;

    try {
        const resp = await ghRequest(token, url, { method: 'POST', body: JSON.stringify(payload) });
        return { reviewId: (JSON.parse(resp) as { id?: number })?.id?.toString() ?? '', commentsDropped };
    } catch (ex: unknown) {
        if (ex instanceof Error && ex.message.includes('422')) {
            const valid = await tryCommentsIndividually(token, url, payload, comments);
            commentsDropped = valid.length < comments.length;
            const resp = await ghRequest(token, url, { method: 'POST', body: JSON.stringify({ ...payload, comments: valid }) });
            return { reviewId: (JSON.parse(resp) as { id?: number })?.id?.toString() ?? '', commentsDropped };
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
    await ghRequest(token, url, { method: 'POST', body: JSON.stringify({ event, body }) });
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

function parseOwnerRepo(gitConfigText: string): string | null {
    const m = /url\s*=\s*(.+)/i.exec(gitConfigText);
    if (!m) return null;
    const raw = m[1].trim();
    // scp-style: git@github.com:owner/repo.git
    const scp = /^[^@]+@[^:]+:([^/]+\/[^/]+?)(?:\.git)?$/.exec(raw);
    if (scp) return scp[1];
    // https or ssh URL: https://github.com/owner/repo.git
    try {
        const u = new URL(raw);
        const parts = u.pathname.replace(/^\//, '').replace(/\.git$/, '').split('/');
        if (parts.length >= 2) return `${parts[0]}/${parts[1]}`;
    } catch { /* not a URL */ }
    return null;
}

export function detectCurrentRepo(workspaceFolder: string): string | null {
    try {
        const configPath = findGitConfig(workspaceFolder);
        if (!configPath) return null;
        return parseOwnerRepo(fs.readFileSync(configPath, 'utf8'));
    } catch {
        return null;
    }
}
