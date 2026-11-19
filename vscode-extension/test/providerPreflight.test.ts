import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

import {
  buildClaudeAuthProbeCommand,
  candidateBinaryPaths,
  existsOnPath,
  resolveCandidateBinary,
  resolveFromPath,
} from '../src/claude';
import {
  classifyClaudeAuthProbe,
  probeClaudeAuthentication,
} from '../src/providerSetup';
import { providerNotInstalledMessage } from '../src/userFacingError';

test('providerNotInstalledMessage names the Copilot CLI and binary', () => {
  const msg = providerNotInstalledMessage('copilot');
  assert.match(msg, /copilot/i);
  assert.match(msg, /GitHub Copilot CLI/);
  assert.match(msg, /try again/i);
});

test('providerNotInstalledMessage names the Claude CLI and binary', () => {
  const msg = providerNotInstalledMessage('claude');
  assert.match(msg, /claude/i);
  assert.match(msg, /Claude Code CLI/);
  assert.match(msg, /try again/i);
});

test('existsOnPath finds a binary present in a PATH directory', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'prpilot-path-'));
  const original = process.env.PATH;
  try {
    const binaryName = process.platform === 'win32' ? 'somebinary.cmd' : 'somebinary';
    fs.writeFileSync(path.join(dir, binaryName), '');
    process.env.PATH = dir;
    assert.equal(existsOnPath('somebinary'), true);
    assert.equal(existsOnPath('not-there-xyz'), false);
  } finally {
    process.env.PATH = original;
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('existsOnPath returns false when PATH is empty', () => {
  const original = process.env.PATH;
  try {
    process.env.PATH = '';
    assert.equal(existsOnPath('anything'), false);
  } finally {
    process.env.PATH = original;
  }
});

test('Claude auth probe only marks a conclusive signed-out response unavailable', () => {
  const responseError = Object.assign(new Error('command failed'), { code: 1 });
  assert.equal(classifyClaudeAuthProbe(responseError, '', 'Not logged in. Run claude auth login.'), 'unavailable');
  assert.equal(classifyClaudeAuthProbe(responseError, '', "error: unknown command 'auth'"), 'unverified');
  assert.equal(classifyClaudeAuthProbe(null, '', "error: unknown command 'auth'"), 'unverified');
  assert.equal(classifyClaudeAuthProbe(responseError, '', ''), 'unverified');
  assert.equal(classifyClaudeAuthProbe(null, 'authenticated', ''), 'ready');
});

test('Claude auth probe execution and timeout failures remain unverified', async () => {
  assert.equal(await probeClaudeAuthentication(() => {
    throw new Error('spawn failed');
  }), 'unverified');
  assert.equal(await probeClaudeAuthentication((complete) => {
    const timeout = Object.assign(new Error('timed out'), { killed: true });
    complete(timeout, '', 'Not logged in.');
  }), 'unverified');
  assert.equal(await probeClaudeAuthentication((complete) => {
    const executionError = Object.assign(new Error('spawn failed'), { code: 'ENOENT' });
    complete(executionError, '', 'Not logged in.');
  }), 'unverified');
});

test('candidateBinaryPaths and resolveFromPath cover Volta on POSIX and Windows extensions', () => {
  assert.ok(
    candidateBinaryPaths('claude', 'darwin', {}, '/Users/dev').includes('/Users/dev/.volta/bin/claude'),
  );
  assert.ok(
    candidateBinaryPaths('claude', 'win32', { ProgramFiles: 'C:\\Program Files' }, 'C:\\Users\\dev')
      .includes('C:\\Program Files\\Volta\\claude'),
  );

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'prpilot-winpath-'));
  try {
    const posixShim = path.join(dir, 'claude');
    fs.writeFileSync(posixShim, '#!/bin/sh\n');
    assert.equal(resolveCandidateBinary(posixShim, 'win32', { PATHEXT: '.EXE;.CMD' }), null);
    assert.equal(resolveFromPath('claude', 'win32', { Path: dir, PATHEXT: '.EXE;.CMD' }), null);

    const cmdPath = path.join(dir, 'claude.cmd');
    fs.writeFileSync(cmdPath, '@echo off\r\n');
    assert.equal(resolveCandidateBinary(posixShim, 'win32', { PATHEXT: '.EXE;.CMD' }), cmdPath);
    assert.equal(resolveFromPath('claude', 'win32', { Path: dir, PATHEXT: '.EXE;.CMD' }), cmdPath);
    assert.equal(resolveFromPath('claude.cmd', 'win32', { Path: dir, PATHEXT: '.EXE;.CMD' }), cmdPath);

    const comDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prpilot-wincom-'));
    try {
      const comPath = path.join(comDir, 'claude.com');
      fs.writeFileSync(comPath, 'MZ');
      assert.equal(resolveCandidateBinary(path.join(comDir, 'claude'), 'win32', {}), comPath);
      assert.equal(resolveCandidateBinary(comPath, 'win32', {}), comPath);
    } finally {
      fs.rmSync(comDir, { recursive: true, force: true });
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('buildClaudeAuthProbeCommand wraps Windows .cmd shims via call and rejects unsafe metacharacters', async () => {
  const spaced = 'C:\\Program Files (x86)\\Node Tools\\claude.cmd';
  assert.deepEqual(
    buildClaudeAuthProbeCommand(spaced, 'win32', { ComSpec: 'C:\\Windows\\System32\\cmd.exe' }),
    {
      command: 'C:\\Windows\\System32\\cmd.exe',
      args: ['/d', '/c', 'call', spaced, 'auth', 'status', '--text'],
    },
  );
  for (const unsafe of [
    'C:\\tools\\bad&calc.cmd',
    'C:\\tools\\bad%PATH%.cmd',
    'C:\\tools\\bad|more.bat',
    'C:\\tools\\bad!var!.cmd',
    'C:\\tools\\bad\0null.cmd',
  ]) {
    assert.equal(buildClaudeAuthProbeCommand(unsafe, 'win32', {}), null);
  }

  if (process.platform === 'win32') {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prpilot spaced '));
    try {
      const shimDir = path.join(root, 'dir with spaces');
      fs.mkdirSync(shimDir, { recursive: true });
      const shim = path.join(shimDir, 'claude.cmd');
      fs.writeFileSync(shim, '@echo off\r\necho authenticated\r\nexit /b 0\r\n');
      const invocation = buildClaudeAuthProbeCommand(shim, 'win32', process.env);
      assert.ok(invocation);
      const status = await probeClaudeAuthentication((complete) => {
        execFile(invocation.command, invocation.args, { timeout: 5_000, windowsHide: true }, (err, out, errOut) => {
          complete(err, String(out), String(errOut));
        });
      });
      assert.equal(status, 'ready');
    } finally {
      fs.rmSync(root, { recursive: true, force: true });
    }
  }
});
