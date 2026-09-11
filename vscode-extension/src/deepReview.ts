import type { DeepReviewPreparation } from './sidecar';

export interface PreparedDeepReview {
    preparation: DeepReviewPreparation;
    prKey: string;
    diff: string;
    settings: string;
    options: Record<string, unknown>;
    selectionRevision: number;
}

/** Pending host identity, never a provider/runtime held open while the user imports. */
export class DeepReviewFlow {
    private revision = 0;
    private pending: PreparedDeepReview | undefined;
    private consumed = new Set<string>();
    private operationId: string | undefined;

    start(operationId?: string): number {
        this.pending = undefined;
        this.operationId = operationId;
        return ++this.revision;
    }

    install(revision: number, prepared: PreparedDeepReview): boolean {
        if (revision !== this.revision) return false;
        this.pending = prepared;
        return true;
    }

    peek(): PreparedDeepReview | undefined { return this.pending; }

    isCurrent(revision: number): boolean { return revision === this.revision; }

    hasConsumed(operationId: string): boolean { return this.consumed.has(operationId); }

    isOperationCurrent(operationId: string): boolean { return this.operationId === operationId; }

    cancel(operationId: string): void {
        if (this.isOperationCurrent(operationId)) this.invalidate();
    }

    consume(retainedId: string, operationId: string, prKey: string, settings: string,
        selectionRevision: number, server?: string): PreparedDeepReview {
        const pending = this.pending;
        if (!pending || pending.preparation.retainedId !== retainedId || pending.prKey !== prKey
            || pending.settings !== settings || pending.selectionRevision !== selectionRevision
            || this.consumed.has(operationId)
            || (server !== undefined && !pending.preparation.servers.includes(server))) {
            throw new Error('Stale, duplicate or changed deep review; prepare again.');
        }
        this.consumed.add(operationId);
        this.operationId = operationId;
        this.pending = undefined;
        return pending;
    }

    retry(prepared: PreparedDeepReview): void { this.pending = prepared; }

    invalidate(): void { this.start(); }
}
