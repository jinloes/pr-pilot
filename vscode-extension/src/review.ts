import type { ReviewResult, LineComment } from './github';

const VERDICTS = ['APPROVE', 'REQUEST_CHANGES', 'COMMENT'] as const;
const COMMENT_TYPES = ['issue', 'suggestion', 'note'] as const;

function isLineComment(value: unknown): value is LineComment {
    if (typeof value !== 'object' || value === null) return false;
    const c = value as Record<string, unknown>;
    return typeof c.file === 'string'
        && typeof c.line === 'number'
        && typeof c.body === 'string'
        && (COMMENT_TYPES as readonly string[]).includes(c.type as string);
}

/**
 * Extracts and validates a {@link ReviewResult} from raw provider output (which may include
 * markdown fences or leading/trailing prose). Unlike a bare `JSON.parse(...) as ReviewResult`,
 * this enforces the schema so malformed-but-valid JSON fails here with a clear error instead of
 * crashing later in consumers like `buildCommentArray`. Mirrors the runtime validation the Kotlin
 * host gets for free from kotlinx.serialization.
 */
export function parseReview(raw: string): ReviewResult {
    let json = raw.trim();
    if (json.startsWith('```')) {
        const newline = json.indexOf('\n');
        const closing = json.lastIndexOf('```');
        if (newline > 0 && closing > newline) json = json.substring(newline + 1, closing).trim();
    }
    const start = json.indexOf('{');
    const end = json.lastIndexOf('}');
    if (start >= 0 && end > start) json = json.substring(start, end + 1);

    const parsed: unknown = JSON.parse(json);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        throw new Error('review JSON is not an object');
    }
    const obj = parsed as Record<string, unknown>;
    if (typeof obj.summary !== 'string') {
        throw new Error('review JSON missing string "summary"');
    }
    if (!(VERDICTS as readonly string[]).includes(obj.verdict as string)) {
        throw new Error('review JSON has invalid "verdict"');
    }
    if (!Array.isArray(obj.lineComments) || !obj.lineComments.every(isLineComment)) {
        throw new Error('review JSON has invalid "lineComments"');
    }
    return {
        summary: obj.summary,
        verdict: obj.verdict as ReviewResult['verdict'],
        lineComments: obj.lineComments,
    };
}
