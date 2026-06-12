/**
 * Ambient type declarations for the KMP-compiled `core` module.
 *
 * The Kotlin classes in commonMain are compiled to Kotlin IR JS (CJS format) without
 * @JsExport annotations, so their runtime names are Kotlin-mangled and not directly
 * importable by TypeScript name. These declarations provide the logical shape for
 * the VS Code extension layer; update them as classes gain @JsExport annotations.
 *
 * To enable named imports and auto-generated .d.ts from Kotlin, add @JsExport to
 * the model/service classes in core/src/commonMain and call generateTypeScriptDefinitions()
 * inside the js(IR) block in core/build.gradle.
 */

declare module 'core' {
    // --- Model types ---

    interface PullRequest {
        readonly title: string;
        readonly htmlUrl: string;
        readonly owner: string;
        readonly repo: string;
        readonly number: number;
        readonly body: string;
        readonly author: string;
        readonly createdAt: string;
    }

    interface LineComment {
        getFile(): string;
        getLine(): number;
        /** One of "issue" | "suggestion" | "note" */
        getType(): string;
        getBody(): string;
    }

    interface ReviewResult {
        getSummary(): string;
        /** One of "APPROVE" | "REQUEST_CHANGES" | "COMMENT" */
        getVerdict(): string;
        getLineComments(): LineComment[];
    }

    type ChatRole = 'USER' | 'ASSISTANT';

    interface ChatMessage {
        readonly role: ChatRole;
        readonly content: string;
    }

    // --- Parser types ---

    interface DiffLine {
        readonly newLineNum: number;
        /** '+' | '-' | ' ' */
        readonly type: string;
        readonly content: string;
        readonly hunkStart: boolean;
    }

    interface DiffFile {
        name: string;
        readonly lines: DiffLine[];
    }

    interface DiffParserCompanion {
        parseDiff(rawDiff: string): DiffFile[];
        computeMaxColumns(files: DiffFile[]): number;
    }

    const DiffParser: DiffParserCompanion;

    // --- Service types ---

    interface GitHubServiceCompanion {
        encodeBody(review: ReviewResult): string;
        decodeReview(body: string, commentsArr: GhReviewComment[]): ReviewResult;
    }

    interface GhReviewComment {
        readonly path: string | null;
        readonly line: number | null;
        readonly originalLine: number | null;
        readonly body: string | null;
    }

    interface PendingReview {
        readonly id: string;
        readonly result: ReviewResult;
        readonly importedFromGitHub: boolean;
    }

    interface SaveDraftResult {
        readonly reviewId: string;
        readonly commentsDropped: boolean;
    }

    /**
     * GitHub REST API client. Blocking methods bridge Ktor suspend functions via runBlocking.
     * Construct with the GitHub base URL (e.g. "https://api.github.com").
     */
    class GitHubService {
        constructor(apiBase: string);
        searchPRs(token: string, query: string): PullRequest[];
        getStarredRepos(token: string): string[];
        getPRDiff(token: string, owner: string, repo: string, prNumber: number): string;
        getPendingReviewId(token: string, owner: string, repo: string, number: number): string | null;
        saveDraftReview(
            token: string,
            owner: string,
            repo: string,
            number: number,
            review: ReviewResult,
        ): SaveDraftResult;
        loadDraftReview(token: string, owner: string, repo: string, number: number): PendingReview | null;
        submitDraftReview(
            token: string,
            owner: string,
            repo: string,
            number: number,
            reviewId: string,
            event: string,
            body: string,
        ): void;
        deleteDraftReview(
            token: string,
            owner: string,
            repo: string,
            number: number,
            reviewId: string,
        ): void;
        isPRMerged(token: string, owner: string, repo: string, number: number): boolean;
        getPRHeadSha(token: string, owner: string, repo: string, number: number): string;
    }
}
