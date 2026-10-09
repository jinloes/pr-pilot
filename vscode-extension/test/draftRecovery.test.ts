import * as assert from 'assert';
import test from 'node:test';
import { DraftRecoveryStore, type RecoveryMemento } from '../src/draftRecovery';
import type { ReviewResult } from '../src/models';

class MemoryMemento implements RecoveryMemento {
    readonly values = new Map<string, unknown>();

    get<T>(key: string): T | undefined {
        return this.values.get(key) as T | undefined;
    }

    async update(key: string, value: unknown): Promise<void> {
        this.values.set(key, value);
    }
}

const result: ReviewResult = {
    summary: 'summary',
    verdict: 'COMMENT',
    lineComments: [{ file: 'a.ts', line: 1, type: 'note', body: 'note' }],
};

test('persists and clears a token-free recovery snapshot', async () => {
    const store = new DraftRecoveryStore(new MemoryMemento());
    await store.save('acme/repo#1', result, []);

    assert.deepEqual(store.get('acme/repo#1')?.result, result);
    await store.clear('acme/repo#1');
    assert.equal(store.get('acme/repo#1'), null);
});

test('returns copies so callers cannot mutate persisted recovery data', async () => {
    const store = new DraftRecoveryStore(new MemoryMemento());
    await store.save('acme/repo#1', result, []);

    const restored = store.get('acme/repo#1');
    restored?.result.lineComments.push({ file: 'b.ts', line: 2, type: 'note', body: 'other' });

    assert.equal(store.get('acme/repo#1')?.result.lineComments.length, 1);
});

test('omits reviewer sources from recovery snapshots', async () => {
    const memento = new MemoryMemento();
    const store = new DraftRecoveryStore(memento);
    const attributed = { file: 'a.ts', line: 1, type: 'note' as const, body: 'note', sources: ['claude-opus', 'gpt-5.5'] };
    await store.save('acme/repo#1', { ...result, lineComments: [attributed] }, [attributed]);

    const stored = JSON.stringify(memento.values.get('pr-pilot.draftRecovery.v1'));
    assert.equal(stored.includes('sources'), false);
    const restored = store.get('acme/repo#1');
    assert.deepEqual(restored?.result.lineComments, [{ file: 'a.ts', line: 1, type: 'note', body: 'note' }]);
    assert.deepEqual(restored?.orphans, [{ file: 'a.ts', line: 1, type: 'note', body: 'note' }]);
    assert.deepEqual(attributed.sources, ['claude-opus', 'gpt-5.5']);
});

test('preserves a suggested change in recovery snapshots', async () => {
    const store = new DraftRecoveryStore(new MemoryMemento());
    const suggested = { file: 'a.ts', line: 1, type: 'issue' as const, body: 'fix', suggestedChange: '  return a;' };
    await store.save('acme/repo#1', { ...result, lineComments: [suggested] }, [suggested]);

    const restored = store.get('acme/repo#1');
    assert.deepEqual(restored?.result.lineComments, [suggested]);
    assert.deepEqual(restored?.orphans, [suggested]);
});
