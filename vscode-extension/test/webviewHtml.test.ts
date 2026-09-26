import test from 'node:test';
import assert from 'node:assert/strict';

import { readFileSync } from 'node:fs';
import * as path from 'node:path';

import { buildErrorHtml, buildMainWebviewHtml } from '../src/webviewHtml';

test('main webview HTML applies a restrictive CSP and nonces scripts', () => {
  const html = buildMainWebviewHtml(
    '<!doctype html><html lang="en"><head></head><body><script type="module" src="./assets/app.js"></script><link href="/assets/app.css"></body></html>',
    'vscode-webview://origin',
    (assetPath) => `vscode-resource://${assetPath}`,
    'fixed-nonce',
  );

  assert.match(html, /default-src 'none'/);
  assert.match(html, /script-src 'nonce-fixed-nonce'/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /<script nonce="fixed-nonce" type="module"/);
  assert.match(html, /src="vscode-resource:\/\/assets\/app\.js"/);
  assert.match(html, /href="vscode-resource:\/\/assets\/app\.css"/);
});

test('Activity Bar view uses native welcome content instead of a launcher webview', () => {
  const manifest = JSON.parse(readFileSync(path.join(__dirname, '..', '..', 'package.json'), 'utf8')) as {
    contributes: {
      views: Record<string, Array<Record<string, unknown>>>;
      viewsWelcome: Array<{ view: string; contents: string }>;
      viewsContainers: { activitybar: Array<{ id: string; icon: string }> };
    };
  };
  const [view] = manifest.contributes.views['pr-pilot'];

  assert.equal(view.id, 'pr-pilot.main');
  assert.equal(view.type, undefined);
  assert.equal(view.icon, './media/pr-pilot.svg');
  assert.equal(manifest.contributes.viewsContainers.activitybar[0].icon, './media/pr-pilot.svg');
  assert.deepEqual(manifest.contributes.viewsWelcome, [{
    view: 'pr-pilot.main',
    contents: 'Review pull requests with AI assistance in an editor tab.\n'
      + '[Open PR Pilot](command:pr-pilot.open)\n'
      + 'Or open [Settings](command:pr-pilot.openSettings).',
  }]);
});

test('opening PR Pilot no longer closes the sidebar or registers a webview view', () => {
  const source = readFileSync(path.join(__dirname, '..', '..', 'src', 'extension.ts'), 'utf8');

  assert.doesNotMatch(source, /workbench\.action\.closeSidebar/);
  assert.doesNotMatch(source, /registerWebviewViewProvider/);
  assert.match(source, /createTreeView\('pr-pilot\.main'/);
});

test('main webview HTML rejects documents without a head', () => {
  assert.throws(
    () => buildMainWebviewHtml('<html lang="en"><body></body></html>', 'source', (assetPath) => assetPath),
    /missing a <head>/,
  );
});

test('error HTML applies CSP and escapes message content', () => {
  const html = buildErrorHtml('<script>alert("unsafe")</script>', 'fixed-nonce');

  assert.match(html, /default-src 'none'/);
  assert.match(html, /style-src 'nonce-fixed-nonce'/);
  assert.match(html, /&lt;script&gt;alert\(&quot;unsafe&quot;\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});
