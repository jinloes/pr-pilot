import test from 'node:test';
import assert from 'node:assert/strict';
import {
    MAX_REMEMBERED_REPOSITORIES,
    composeCustomInstructions,
    normalizeRepositoryInstructions,
    parseRepositoryInstructionsUpdate,
    repositoryKey,
    withRepositoryInstructions,
} from '../src/repositoryInstructions';

test('repositoryKey lowercases valid GitHub names and rejects anything else', () => {
    assert.equal(repositoryKey('Acme', 'My.Repo_1'), 'acme/my.repo_1');
    assert.equal(repositoryKey(' acme ', ' widget '), 'acme/widget');
    assert.equal(repositoryKey('a b', 'widget'), null);
    assert.equal(repositoryKey('acme', 'wid/get'), null);
    assert.equal(repositoryKey('', 'widget'), null);
    assert.equal(repositoryKey('-acme', 'widget'), null);
});

test('normalizeRepositoryInstructions keeps valid trimmed entries and the first of colliding keys', () => {
    assert.deepEqual(normalizeRepositoryInstructions(undefined), {});
    assert.deepEqual(normalizeRepositoryInstructions(['acme/widget']), {});
    assert.deepEqual(normalizeRepositoryInstructions({
        'Acme/Widget': '  First  ',
        'acme/widget': 'Second',
        'bad key': 'x',
        'acme/blank': '   ',
        'acme/number': 7,
        'acme/huge': 'x'.repeat(10_001),
    }), { 'acme/widget': 'First' });
});

test('normalizeRepositoryInstructions caps the number of remembered repositories', () => {
    const raw = Object.fromEntries(Array.from({ length: MAX_REMEMBERED_REPOSITORIES + 5 },
        (_, index) => [`owner/repo-${index}`, 'Rule']));
    assert.equal(Object.keys(normalizeRepositoryInstructions(raw)).length, MAX_REMEMBERED_REPOSITORIES);
});

test('withRepositoryInstructions sets, replaces, removes, and enforces limits without mutating input', () => {
    const current = { 'acme/widget': 'Old' };
    assert.deepEqual(withRepositoryInstructions(current, 'acme/widget', ' New '), { 'acme/widget': 'New' });
    assert.deepEqual(withRepositoryInstructions(current, 'acme/widget', ''), {});
    assert.deepEqual(current, { 'acme/widget': 'Old' });
    assert.equal(withRepositoryInstructions(current, 'acme/widget', 'x'.repeat(10_001)), null);
    const full = Object.fromEntries(Array.from({ length: MAX_REMEMBERED_REPOSITORIES },
        (_, index) => [`owner/repo-${index}`, 'Rule']));
    assert.equal(withRepositoryInstructions(full, 'acme/new', 'Rule'), null);
    assert.equal(withRepositoryInstructions(full, 'owner/repo-0', 'Updated')?.['owner/repo-0'], 'Updated');
});

test('composeCustomInstructions places remembered text ahead of the other instructions', () => {
    assert.equal(composeCustomInstructions('acme/widget', '', 'Per review'), 'Per review');
    assert.equal(composeCustomInstructions('acme/widget', ' Rule ', ''),
        'Instructions remembered for acme/widget:\nRule');
    assert.equal(composeCustomInstructions('acme/widget', 'Rule', ' Per review '),
        'Instructions remembered for acme/widget:\nRule\n\nPer review');
});

test('parseRepositoryInstructionsUpdate accepts valid maps, drops blanks, and rejects any invalid entry', () => {
    assert.deepEqual(parseRepositoryInstructionsUpdate({ 'Acme/Widget': ' Rule ', 'acme/old': '  ' }),
        { 'acme/widget': 'Rule' });
    assert.deepEqual(parseRepositoryInstructionsUpdate({}), {});
    assert.equal(parseRepositoryInstructionsUpdate(null), null);
    assert.equal(parseRepositoryInstructionsUpdate(['acme/widget']), null);
    assert.equal(parseRepositoryInstructionsUpdate({ 'acme/widget': 7 }), null);
    assert.equal(parseRepositoryInstructionsUpdate({ 'not a repo': 'Rule' }), null);
    assert.equal(parseRepositoryInstructionsUpdate({ 'acme/widget': 'x'.repeat(10_001) }), null);
    assert.equal(parseRepositoryInstructionsUpdate({ 'acme/widget': 'a', 'ACME/widget': 'b' }), null);
    const tooMany = Object.fromEntries(Array.from({ length: MAX_REMEMBERED_REPOSITORIES + 1 },
        (_, index) => [`owner/repo-${index}`, 'Rule']));
    assert.equal(parseRepositoryInstructionsUpdate(tooMany), null);
});
