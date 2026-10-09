export type Severity = 'blocker' | 'major' | 'minor' | 'nit';
export type Category = 'correctness' | 'security' | 'performance' | 'tests' | 'maintainability' | 'style' | 'compatibility';
export type Confidence = 'low' | 'medium' | 'high';
export type ReviewStatus = 'UNREVIEWED' | 'REVIEWED' | 'UPDATED_SINCE_REVIEW' | 'UNAVAILABLE';

export interface PR {
    number: number;
    title: string;
    owner: string;
    repo: string;
    author: string;
    createdAt: string;
    htmlUrl: string;
    isDraft: boolean;
    hasReviewDraft: boolean;
    reviewStatus: ReviewStatus;
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
    /** Literal replacement for the anchored line, published as a GitHub suggestion block. */
    suggestedChange?: string;
    /** Display-only reviewer attribution; never persisted to GitHub drafts. */
    sources?: string[];
}

export interface ReviewResult {
    summary: string;
    verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT';
    lineComments: LineComment[];
}

export type PRSearchScope = 'currentRepo' | 'authored' | 'assigned' | 'reviewRequested';
