import type { LineComment, ReviewResult } from './models';

const RECOVERY_STATE_KEY = 'pr-pilot.draftRecovery.v1';
const MAX_RECOVERY_SNAPSHOTS = 20;

export interface RecoveryMemento {
    get<T>(key: string): T | undefined;
    update(key: string, value: unknown): PromiseLike<void>;
}

export interface DraftRecoverySnapshot {
    prKey: string;
    result: ReviewResult;
    orphans: LineComment[];
    savedAt: number;
}

type RecoveryState = Record<string, DraftRecoverySnapshot>;

// Reviewer attribution belongs to one generation run, so recovered drafts never carry it.
function copyComment(comment: LineComment): LineComment {
    const { sources: _sources, ...rest } = comment;
    return rest;
}

function copyResult(result: ReviewResult): ReviewResult {
    return {
        summary: result.summary,
        verdict: result.verdict,
        lineComments: result.lineComments.map(copyComment),
    };
}

export class DraftRecoveryStore {
    constructor(private readonly memento: RecoveryMemento) {}

    get(prKey: string): DraftRecoverySnapshot | null {
        const snapshot = this.memento.get<RecoveryState>(RECOVERY_STATE_KEY)?.[prKey];
        if (!snapshot || snapshot.prKey !== prKey) return null;
        return {
            prKey,
            result: copyResult(snapshot.result),
            orphans: snapshot.orphans.map(copyComment),
            savedAt: snapshot.savedAt,
        };
    }

    async save(prKey: string, result: ReviewResult, orphans: LineComment[]): Promise<void> {
        const state = { ...(this.memento.get<RecoveryState>(RECOVERY_STATE_KEY) ?? {}) };
        state[prKey] = {
            prKey,
            result: copyResult(result),
            orphans: orphans.map(copyComment),
            savedAt: Date.now(),
        };
        const ordered = Object.values(state).sort((left, right) => right.savedAt - left.savedAt);
        const bounded = Object.fromEntries(
            ordered.slice(0, MAX_RECOVERY_SNAPSHOTS).map((snapshot) => [snapshot.prKey, snapshot]),
        );
        await this.memento.update(RECOVERY_STATE_KEY, bounded);
    }

    async clear(prKey: string): Promise<void> {
        const state = { ...(this.memento.get<RecoveryState>(RECOVERY_STATE_KEY) ?? {}) };
        if (!(prKey in state)) return;
        delete state[prKey];
        await this.memento.update(RECOVERY_STATE_KEY, state);
    }
}
