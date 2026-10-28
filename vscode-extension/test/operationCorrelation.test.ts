import test from 'node:test';
import assert from 'node:assert/strict';
import {
    canPersistDraft,
    cancelThenCleanup,
    cancelForSelection,
    invalidateChat,
    invalidateChatAndCancel,
    invalidateGenerationAndCancel,
    staleGenerationReason,
    type OperationCorrelationState,
} from '../src/operationCorrelation';

function deferred(): { promise: Promise<void>; resolve: () => void } {
    let resolve!: () => void;
    const promise = new Promise<void>((done) => {
        resolve = done;
    });
    return { promise, resolve };
}

function state(): OperationCorrelationState {
    return {
        disposed: false,
        selectionRevision: 1,
        generationRevision: 3,
        chatRevision: 5,
    };
}

test('newest overlapping selection proceeds when cancellation resolves first', async () => {
    const current = state();
    const firstCancellation = deferred();
    const first = cancelForSelection(current, 1, () => firstCancellation.promise);

    current.selectionRevision = 2;
    const secondCancellation = deferred();
    const second = cancelForSelection(current, 2, () => secondCancellation.promise);

    secondCancellation.resolve();
    assert.equal(await second, true);

    firstCancellation.resolve();
    assert.equal(await first, false);
});

test('disposed selection cannot proceed after cancellation', async () => {
    const current = state();
    const cancellation = deferred();
    const pending = cancelForSelection(current, 1, () => cancellation.promise);

    current.disposed = true;
    cancellation.resolve();

    assert.equal(await pending, false);
});

test('explicit cancellation invalidates generation before provider resolves', async () => {
    const current = state();
    const cancellation = deferred();

    const pending = invalidateGenerationAndCancel(current, () => cancellation.promise);

    assert.equal(current.generationRevision, 4);
    cancellation.resolve();
    await pending;
});

test('clearing chat invalidates in-flight callbacks', () => {
    const current = state();

    invalidateChat(current);

    assert.equal(current.chatRevision, 6);
});

test('clearing chat invalidates callbacks before provider cancellation resolves', async () => {
    const current = state();
    const cancellation = deferred();

    const pending = invalidateChatAndCancel(current, () => cancellation.promise);

    assert.equal(current.chatRevision, 6);
    cancellation.resolve();
    await pending;
});

test('clearing chat does not cancel a provider owned by another operation', async () => {
    const current = state();
    let cancelled = false;

    await invalidateChatAndCancel(current, async () => { cancelled = true; }, false);

    assert.equal(current.chatRevision, 6);
    assert.equal(cancelled, false);
});

test('disposal cleanup waits for provider cancellation', async () => {
    const cancellation = deferred();
    let cleaned = false;
    const pending = cancelThenCleanup(
        () => cancellation.promise,
        () => { cleaned = true; },
    );

    assert.equal(cleaned, false);
    cancellation.resolve();
    await pending;
    assert.equal(cleaned, true);
});

test('outgoing PR drafts require an explicit review snapshot', () => {
    assert.equal(canPersistDraft(true, false), true);
    assert.equal(canPersistDraft(false, true), true);
    assert.equal(canPersistDraft(false, false), false);
});

test('staleGenerationReason is null while the generation still owns the view', () => {
    const snapshot = { prKey: 'o/r#1', selectionRevision: 1, generationRevision: 3 };
    assert.equal(staleGenerationReason(state(), 'o/r#1', snapshot), null);
});

test('staleGenerationReason explains each way a generation goes stale', () => {
    const snapshot = { prKey: 'o/r#1', selectionRevision: 1, generationRevision: 3 };
    assert.equal(
        staleGenerationReason({ ...state(), disposed: true }, 'o/r#1', snapshot),
        'the PR Pilot view was closed',
    );
    assert.equal(staleGenerationReason(state(), 'o/r#2', snapshot), 'a different pull request was selected');
    assert.equal(
        staleGenerationReason({ ...state(), selectionRevision: 2, generationRevision: 4 }, 'o/r#1', snapshot),
        'the pull request was reselected',
    );
    assert.equal(
        staleGenerationReason({ ...state(), generationRevision: 4 }, 'o/r#1', snapshot),
        'a newer review generation started',
    );
});

test('staleGenerationReason reports disposal before any other cause', () => {
    const snapshot = { prKey: 'o/r#1', selectionRevision: 1, generationRevision: 3 };
    assert.equal(
        staleGenerationReason({ ...state(), disposed: true, generationRevision: 9 }, 'o/r#2', snapshot),
        'the PR Pilot view was closed',
    );
});
