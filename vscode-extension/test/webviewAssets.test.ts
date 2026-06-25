import test from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';

import { resolveWebviewDistPath } from '../src/webviewAssets';

const extensionRoot = path.resolve('/workspace/pr-pilot/vscode-extension');

test('resolveWebviewDistPath prefers packaged webview-dist when bundled assets exist', () => {
    const resolved = resolveWebviewDistPath(extensionRoot, (candidate) =>
        candidate === path.join(extensionRoot, 'webview-dist', 'index.html'));

    assert.equal(resolved, path.join(extensionRoot, 'webview-dist'));
});

test('resolveWebviewDistPath falls back to sibling webview/dist during local development', () => {
    const resolved = resolveWebviewDistPath(extensionRoot, () => false);

    assert.equal(resolved, path.resolve(extensionRoot, '..', 'webview', 'dist'));
});

