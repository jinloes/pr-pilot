import test from 'node:test';
import assert from 'node:assert/strict';

import { normalizePendingDraftKey, PendingDraftIndex, type PendingDraftMemento } from '../src/pendingDraftIndex';

function memoryMemento(): PendingDraftMemento & { data: Map<string, unknown> } {
    const data = new Map<string, unknown>();
    return {
        data,
        get<T>(key: string): T | undefined {
            return data.get(key) as T | undefined;
        },
        update(key: string, value: unknown): PromiseLike<void> {
            data.set(key, value);
            return Promise.resolve();
        },
    };
}

test('normalizePendingDraftKey lowercases owner/repo and rejects invalid PR coordinates', () => {
    assert.equal(normalizePendingDraftKey('Acme', 'Widgets', 42), 'acme/widgets#42');
    assert.equal(normalizePendingDraftKey('', 'widgets', 42), null);
    assert.equal(normalizePendingDraftKey('acme', '  ', 42), null);
    assert.equal(normalizePendingDraftKey('acme', 'widgets', 0), null);
    assert.equal(normalizePendingDraftKey('acme', 'widgets', 1.5), null);
});

test('PendingDraftIndex persists keys across instances sharing a memento and removes cleanly', async () => {
    const memento = memoryMemento();
    const first = new PendingDraftIndex(memento);

    await first.add('Acme', 'Widgets', 11, 100);
    await first.add('acme', 'platform', 12, 200);
    assert.equal(first.has('acme', 'widgets', 11), true);
    assert.equal(first.has('ACME', 'PLATFORM', 12), true);

    const reloaded = new PendingDraftIndex(memento);
    assert.equal(reloaded.has('acme', 'widgets', 11), true);
    assert.equal(reloaded.has('acme', 'platform', 12), true);

    await reloaded.remove('acme', 'widgets', 11);
    assert.equal(reloaded.has('acme', 'widgets', 11), false);
    assert.equal(reloaded.has('acme', 'platform', 12), true);
});

test('PendingDraftIndex bounds stored entries to 500 most recent PR keys and tolerates corrupt state', async () => {
    const memento = memoryMemento();
    memento.data.set('pr-pilot.pendingDrafts.v1', 'not-an-object');
    const index = new PendingDraftIndex(memento);
    assert.equal(index.has('acme', 'widgets', 1), false);

    for (let i = 1; i <= 505; i++) {
        await index.add('acme', 'widgets', i, i);
    }

    assert.equal(index.has('acme', 'widgets', 1), false);
    assert.equal(index.has('acme', 'widgets', 5), false);
    assert.equal(index.has('acme', 'widgets', 6), true);
    assert.equal(index.has('acme', 'widgets', 505), true);
});
