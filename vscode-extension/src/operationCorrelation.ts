export interface OperationCorrelationState {
    disposed: boolean;
    selectionRevision: number;
    generationRevision: number;
    chatRevision: number;
}

export async function cancelForSelection(
    state: OperationCorrelationState,
    selectionRevision: number,
    cancel: () => Promise<void>,
): Promise<boolean> {
    await cancel();
    return !state.disposed && state.selectionRevision === selectionRevision;
}

export async function invalidateGenerationAndCancel(
    state: OperationCorrelationState,
    cancel: () => Promise<void>,
): Promise<void> {
    state.generationRevision++;
    await cancel();
}

export function invalidateChat(state: OperationCorrelationState): void {
    state.chatRevision++;
}

export async function invalidateChatAndCancel(
    state: OperationCorrelationState,
    cancel: () => Promise<void>,
    ownsProvider = true,
): Promise<void> {
    invalidateChat(state);
    if (ownsProvider) await cancel();
}

export async function cancelThenCleanup(
    cancel: () => Promise<void>,
    cleanup: () => void,
): Promise<void> {
    try {
        await cancel();
    } finally {
        cleanup();
    }
}

export function canPersistDraft(activePr: boolean, hasExplicitResult: boolean): boolean {
    return activePr || hasExplicitResult;
}

/** Snapshot taken when a review generation starts, used to explain why its output was discarded. */
export interface GenerationSnapshot {
    prKey: string;
    selectionRevision: number;
    generationRevision: number;
}

/**
 * Why a finished generation no longer owns the view, or `null` while it still does. The webview
 * has already moved on in every stale case, so the host only logs the reason.
 */
export function staleGenerationReason(
    state: OperationCorrelationState,
    activePrKey: string,
    snapshot: GenerationSnapshot,
): string | null {
    if (state.disposed) return 'the PR Pilot view was closed';
    if (activePrKey !== snapshot.prKey) return 'a different pull request was selected';
    if (state.selectionRevision !== snapshot.selectionRevision) return 'the pull request was reselected';
    if (state.generationRevision !== snapshot.generationRevision) return 'a newer review generation started';
    return null;
}
