import test from 'node:test';
import assert from 'node:assert/strict';

import { buildPRSearchQuery } from '../src/github';

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