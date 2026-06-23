import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

import {
  apiBase,
  buildPRSearchQuery,
  detectCurrentRepo,
  isRetriableNetworkError,
  isRetriableStatus,
  normalizeGithubBaseUrl,
} from '../src/github';

test('buildPRSearchQuery uses current repo when scope is currentRepo', () => {
  assert.equal(
    buildPRSearchQuery('open', 'currentRepo', 'acme/platform'),
    'is:pr is:open draft:false repo:acme/platform',
  );
});

test('buildPRSearchQuery falls back to authored PRs when current repo is missing', () => {
  assert.equal(
    buildPRSearchQuery('open', 'currentRepo'),
    'is:pr is:open draft:false author:@me',
  );
});

test('buildPRSearchQuery supports review-requested scope', () => {
  assert.equal(
    buildPRSearchQuery('open', 'reviewRequested', 'acme/platform'),
    'is:pr is:open draft:false review-requested:@me',
  );
});

test('buildPRSearchQuery supports assigned scope', () => {
  assert.equal(
    buildPRSearchQuery('closed', 'assigned'),
    'is:pr is:closed draft:false assignee:@me',
  );
});

test('buildPRSearchQuery supports authored scope', () => {
  assert.equal(
    buildPRSearchQuery('all', 'authored'),
    'is:pr draft:false author:@me',
  );
});

test('apiBase rejects non-https base URLs', () => {
  assert.throws(
    () => apiBase('http://github.example.com'),
    /must start with https:\/\//,
  );
});

test('isRetriableStatus retries on 429 and 5xx only', () => {
  assert.equal(isRetriableStatus(429), true);
  assert.equal(isRetriableStatus(500), true);
  assert.equal(isRetriableStatus(503), true);
  assert.equal(isRetriableStatus(404), false);
  assert.equal(isRetriableStatus(422), false);
});

test('isRetriableNetworkError recognizes transient network failures', () => {
  assert.equal(isRetriableNetworkError(new Error('ETIMEDOUT while connecting')), true);
  assert.equal(isRetriableNetworkError(new Error('socket hang up')), true);
  assert.equal(isRetriableNetworkError(new Error('request timeout')), true);
  assert.equal(isRetriableNetworkError(new Error('bad credentials')), false);
});

test('normalizeGithubBaseUrl defaults to github.com and trims trailing slash', () => {
  assert.equal(normalizeGithubBaseUrl(''), 'https://github.com');
  assert.equal(normalizeGithubBaseUrl('https://github.example.com/'), 'https://github.example.com');
});

// ── detectCurrentRepo ───────────────────────────────────────────────────────

function withGitConfig(config: string, fn: (dir: string) => void): void {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-repo-'));
  try {
    fs.mkdirSync(path.join(dir, '.git'));
    fs.writeFileSync(path.join(dir, '.git', 'config'), config);
    fn(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('detectCurrentRepo reads the origin remote (https)', () => {
  withGitConfig(
    '[remote "origin"]\n\turl = https://github.com/acme/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), 'acme/platform'),
  );
});

test('detectCurrentRepo reads the origin remote (scp-style ssh)', () => {
  withGitConfig(
    '[remote "origin"]\n\turl = git@github.com:acme/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), 'acme/platform'),
  );
});

test('detectCurrentRepo picks origin, not the first remote in the file', () => {
  // Regression: a non-origin remote listed first must not win, or the list would
  // search the wrong repo and (for a personal fork) show only the user's PRs.
  withGitConfig(
    '[remote "upstream"]\n\turl = https://github.com/upstream-org/platform.git\n' +
      '[remote "origin"]\n\turl = https://github.com/acme/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), 'acme/platform'),
  );
});

test('detectCurrentRepo returns null when there is no origin remote', () => {
  withGitConfig(
    '[remote "upstream"]\n\turl = https://github.com/upstream-org/platform.git\n',
    (dir) => assert.equal(detectCurrentRepo(dir), null),
  );
});
