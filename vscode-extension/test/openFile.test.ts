import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

import { isValidBridgeRequest, BRIDGE_PROTOCOL_VERSION } from '../src/bridgeValidation';
import {
  buildReadOnlyWorktreeQuery,
  buildReadOnlyWorktreeUriKey,
  handleOpenFileInEditor,
  resolveOpenFileTarget,
} from '../src/openFile';

test('bridgeValidation accepts valid openFile requests and rejects path escapes or invalid lines', () => {
  const base = {
    protocolVersion: BRIDGE_PROTOCOL_VERSION,
    type: 'openFile',
    number: 7,
    owner: 'acme',
    repo: 'widgets',
    file: 'src/index.ts',
  };
  assert.equal(isValidBridgeRequest(base), true);
  assert.equal(isValidBridgeRequest({ ...base, line: 12 }), true);

  for (const badFile of ['', '../secret.ts', '/etc/passwd', 'src//index.ts', 'src\\index.ts', 'C:/win.ini', 'src/./index.ts']) {
    assert.equal(isValidBridgeRequest({ ...base, file: badFile }), false, `expected rejection for ${badFile}`);
  }
  assert.equal(isValidBridgeRequest({ ...base, line: 0 }), false);
  assert.equal(isValidBridgeRequest({ ...base, line: -3 }), false);
  assert.equal(isValidBridgeRequest({ ...base, line: '12' }), false);
});

test('resolveOpenFileTarget prefers workspace checkout and falls back to PR worktree as read-only', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-openfile-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));

  const workspaceDir = path.join(root, 'workspace');
  const worktreeDir = path.join(root, 'worktree');
  const outsideDir = path.join(root, 'outside');
  fs.mkdirSync(path.join(workspaceDir, 'src'), { recursive: true });
  fs.mkdirSync(path.join(worktreeDir, 'src'), { recursive: true });
  fs.mkdirSync(outsideDir, { recursive: true });

  const wsFile = path.join(workspaceDir, 'src', 'existing.ts');
  const wtExistingFile = path.join(worktreeDir, 'src', 'existing.ts');
  const wtAddedFile = path.join(worktreeDir, 'src', 'added-in-pr.ts');
  const outsideFile = path.join(outsideDir, 'secret.txt');
  fs.writeFileSync(wsFile, 'export const v = 1;\n');
  fs.writeFileSync(wtExistingFile, 'export const v = 2;\n');
  fs.writeFileSync(wtAddedFile, 'export const added = true;\n');
  fs.writeFileSync(outsideFile, 'secret\n');

  let worktreeCalls = 0;
  const fromWorkspace = await resolveOpenFileTarget({
    workspaceGitRoot: workspaceDir,
    getWorktreeDir: async () => {
      worktreeCalls++;
      return worktreeDir;
    },
    relativeFile: 'src/existing.ts',
    line: 5,
  });
  assert.ok(fromWorkspace);
  assert.equal(fromWorkspace.filePath, fs.realpathSync(wsFile));
  assert.equal(fromWorkspace.zeroBasedLine, 4);
  assert.equal(fromWorkspace.temporaryWorktree, false);
  assert.equal(worktreeCalls, 0);

  const fromWorktree = await resolveOpenFileTarget({
    workspaceGitRoot: workspaceDir,
    getWorktreeDir: async () => {
      worktreeCalls++;
      return worktreeDir;
    },
    relativeFile: 'src/added-in-pr.ts',
  });
  assert.ok(fromWorktree);
  assert.equal(fromWorktree.filePath, fs.realpathSync(wtAddedFile));
  assert.equal(fromWorktree.zeroBasedLine, 0);
  assert.equal(fromWorktree.temporaryWorktree, true);
  assert.equal(worktreeCalls, 1);

  try {
    fs.symlinkSync(outsideFile, path.join(workspaceDir, 'src', 'escape.ts'));
    const escaped = await resolveOpenFileTarget({
      workspaceGitRoot: workspaceDir,
      getWorktreeDir: async () => null,
      relativeFile: 'src/escape.ts',
    });
    assert.equal(escaped, null);
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'EPERM') throw err;
  }

  const dotDotFile = path.join(workspaceDir, '..env');
  fs.writeFileSync(dotDotFile, 'PORT=3000\n');
  const validDotDotPrefix = await resolveOpenFileTarget({
    workspaceGitRoot: workspaceDir,
    getWorktreeDir: async () => null,
    relativeFile: '..env',
    line: 50_000_000,
  });
  assert.ok(validDotDotPrefix);
  assert.equal(validDotDotPrefix.filePath, fs.realpathSync(dotDotFile));
  assert.equal(validDotDotPrefix.zeroBasedLine, 9_999_999);
});

test('buildReadOnlyWorktreeUriKey scopes by owner/repo/PR and handleOpenFileInEditor catches showTextDocument failures', async () => {
  assert.equal(
    buildReadOnlyWorktreeUriKey('Acme', 'Widgets', 42, 'src/index.ts'),
    '/acme/widgets/PR-42/src/index.ts',
  );
  assert.notEqual(
    buildReadOnlyWorktreeUriKey('acme', 'widgets', 42, 'src/index.ts'),
    buildReadOnlyWorktreeUriKey('other', 'fork', 42, 'src/index.ts'),
  );
  assert.notEqual(
    buildReadOnlyWorktreeQuery('/tmp/wt-1/src/index.ts'),
    buildReadOnlyWorktreeQuery('/tmp/wt-2/src/index.ts'),
  );

  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-open-err-'));
  try {
    fs.mkdirSync(path.join(root, 'src'), { recursive: true });
    fs.writeFileSync(path.join(root, 'src', 'app.ts'), 'export {};\n');
    const messages: string[] = [];
    const fakeVscode = {
      ViewColumn: { Beside: 2 },
      Range: class { constructor(public a: number, public b: number, public c: number, public d: number) {} },
      Uri: { file: (fsPath: string) => ({ scheme: 'file', fsPath }) },
      window: {
        showTextDocument: async () => { throw new Error('binary file cannot be opened as text'); },
        showInformationMessage: async (m: string) => { messages.push(m); return undefined; },
      },
    } as unknown as typeof import('vscode');

    await handleOpenFileInEditor(
      {
        disposed: false,
        selectionRevision: 1,
        activePR: { number: 7, owner: 'acme', repo: 'widgets', title: 'T', body: '' },
      } as unknown as import('../src/extensionTypes').ViewState,
      { number: 7, owner: 'acme', repo: 'widgets', file: 'src/app.ts', line: 1 },
      {
        vscode: fakeVscode,
        workingDir: () => root,
        findGitRoot: async () => root,
        detectRepo: async () => 'acme/widgets',
        resolveWorkingDir: async () => root,
      },
    );
    assert.deepEqual(messages, ['Could not open src/app.ts in the editor.']);

    messages.length = 0;
    const state = {
      disposed: false,
      selectionRevision: 2,
      activePR: { number: 7, owner: 'acme', repo: 'widgets', title: 'T', body: '' },
    } as unknown as import('../src/extensionTypes').ViewState;
    await handleOpenFileInEditor(
      state,
      { number: 7, owner: 'acme', repo: 'widgets', file: 'src/app.ts', line: 1 },
      {
        vscode: fakeVscode,
        workingDir: () => root,
        findGitRoot: async () => { throw new Error('sidecar down'); },
        detectRepo: async () => 'acme/widgets',
        resolveWorkingDir: async () => root,
      },
    );
    assert.deepEqual(messages, ['Could not open src/app.ts in the editor.']);

    messages.length = 0;
    await handleOpenFileInEditor(
      state,
      { number: 7, owner: 'acme', repo: 'widgets', file: 'src/app.ts', line: 1 },
      {
        vscode: fakeVscode,
        workingDir: () => root,
        findGitRoot: async () => root,
        detectRepo: async () => {
          state.selectionRevision = 3;
          return 'other/repo';
        },
        resolveWorkingDir: async () => root,
      },
    );
    assert.deepEqual(messages, []);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
