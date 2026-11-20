import { log } from './log';

const PENDING_DRAFT_STATE_KEY = 'pr-pilot.pendingDrafts.v1';
const MAX_PENDING_DRAFT_PRS = 500;

export interface PendingDraftMemento {
    get<T>(key: string): T | undefined;
    update(key: string, value: unknown): PromiseLike<void>;
}

interface PendingDraftEntry {
    key: string;
    updatedAt: number;
}

type PendingDraftState = Record<string, PendingDraftEntry>;

function inMemoryMemento(): PendingDraftMemento {
    const store = new Map<string, unknown>();
    return {
        get<T>(key: string): T | undefined {
            return store.get(key) as T | undefined;
        },
        update(key: string, value: unknown): PromiseLike<void> {
            store.set(key, value);
            return Promise.resolve();
        },
    };
}

export function normalizePendingDraftKey(owner: string, repo: string, number: number): string | null {
    const cleanOwner = owner.trim().toLowerCase();
    const cleanRepo = repo.trim().toLowerCase();
    if (!cleanOwner || !cleanRepo || !Number.isInteger(number) || number <= 0) return null;
    return `${cleanOwner}/${cleanRepo}#${number}`;
}

function readValidState(raw: unknown): PendingDraftState {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
    const out: PendingDraftState = {};
    for (const [key, entry] of Object.entries(raw as Record<string, unknown>)) {
        if (!entry || typeof entry !== 'object' || Array.isArray(entry)) continue;
        const record = entry as { key?: unknown; updatedAt?: unknown };
        if (record.key === key && typeof record.updatedAt === 'number' && Number.isFinite(record.updatedAt)) {
            out[key] = { key, updatedAt: record.updatedAt };
        }
    }
    return out;
}

/**
 * Token-free `globalState` index of pull requests with an unsubmitted GitHub review draft.
 * Mirrors IntelliJ's `PendingReviewIndex` so `Review draft` badges survive PR switches and reloads.
 */
export class PendingDraftIndex {
    private readonly memento: PendingDraftMemento;

    constructor(memento?: PendingDraftMemento) {
        this.memento = memento ?? inMemoryMemento();
    }

    has(owner: string, repo: string, number: number): boolean {
        const key = normalizePendingDraftKey(owner, repo, number);
        if (!key) return false;
        const state = readValidState(this.memento.get<unknown>(PENDING_DRAFT_STATE_KEY));
        return key in state;
    }

    async add(owner: string, repo: string, number: number, now = Date.now()): Promise<void> {
        const key = normalizePendingDraftKey(owner, repo, number);
        if (!key) return;
        const state = readValidState(this.memento.get<unknown>(PENDING_DRAFT_STATE_KEY));
        state[key] = { key, updatedAt: now };
        const ordered = Object.values(state).sort((left, right) => right.updatedAt - left.updatedAt);
        const bounded = Object.fromEntries(
            ordered.slice(0, MAX_PENDING_DRAFT_PRS).map((entry) => [entry.key, entry]),
        );
        try {
            await this.memento.update(PENDING_DRAFT_STATE_KEY, bounded);
        } catch (err) {
            log(`[warn] Failed to persist pending draft index: ${err instanceof Error ? err.message : String(err)}`);
        }
    }

    async remove(owner: string, repo: string, number: number): Promise<void> {
        const key = normalizePendingDraftKey(owner, repo, number);
        if (!key) return;
        const state = readValidState(this.memento.get<unknown>(PENDING_DRAFT_STATE_KEY));
        if (!(key in state)) return;
        delete state[key];
        try {
            await this.memento.update(PENDING_DRAFT_STATE_KEY, state);
        } catch (err) {
            log(`[warn] Failed to update pending draft index: ${err instanceof Error ? err.message : String(err)}`);
        }
    }
}
