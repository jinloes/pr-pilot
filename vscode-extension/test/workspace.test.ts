import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';

import { resolveWorkspaceDir, TARGET_REPO_ENV } from '../src/workspace';

const workspaceFolder = path.resolve('/workspace/source');
const targetRepo = path.resolve('/workspace/target');

test('resolveWorkspaceDir uses PR_PILOT_TARGET_REPO when it is an existing absolute directory', () => {
    const resolved = resolveWorkspaceDir(
        [workspaceFolder],
        { [TARGET_REPO_ENV]: targetRepo },
        dir => dir === targetRepo,
    );

    assert.equal(resolved, targetRepo);
});

test('resolveWorkspaceDir trims PR_PILOT_TARGET_REPO before validating it', () => {
    const resolved = resolveWorkspaceDir(
        [workspaceFolder],
        { [TARGET_REPO_ENV]: `  ${targetRepo}  ` },
        dir => dir === targetRepo,
    );

    assert.equal(resolved, targetRepo);
});

test('resolveWorkspaceDir falls back to VS Code workspace when override is relative', () => {
    const resolved = resolveWorkspaceDir(
        [workspaceFolder],
        { [TARGET_REPO_ENV]: 'relative/repo' },
        () => true,
    );

    assert.equal(resolved, workspaceFolder);
});

test('resolveWorkspaceDir falls back to VS Code workspace when override does not exist', () => {
    const resolved = resolveWorkspaceDir(
        [workspaceFolder],
        { [TARGET_REPO_ENV]: targetRepo },
        () => false,
    );

    assert.equal(resolved, workspaceFolder);
});

test('resolveWorkspaceDir returns empty string when no override or VS Code workspace is available', () => {
    assert.equal(resolveWorkspaceDir([], {}, () => false), '');
});
