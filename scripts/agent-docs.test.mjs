import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  AGENT_DOC_MAX_BYTES,
  checkAgentDocs,
  githubSlug,
  isAgentDoc,
  parseMarkdown,
  resolveLink,
} from './agent-docs.mjs'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const VALID_DOCS = {
  'AGENTS.md': '# Guide\n\nSee `CODEMAP.md`.\n',
  'ARCHITECTURE.md': [
    '# Architecture',
    '',
    '## Key design decisions',
    '',
    '**Hosts** ([hosts.md](docs/architecture/hosts.md))',
    '- [Sidecar is the only transport](docs/architecture/hosts.md#sidecar-is-the-only-transport)',
    '- [Webview — styling](docs/architecture/hosts.md#webview--styling)',
    '',
    '## Settings persistence',
    '',
    'Body links may repeat an anchor: [transport](docs/architecture/hosts.md#sidecar-is-the-only-transport).',
    '',
  ].join('\n'),
  'CODEMAP.md': '# Code map\n\n- [core](docs/codemap/core.md)\n',
  'docs/architecture/hosts.md': [
    '# Hosts',
    '',
    'Back to the [index](../../ARCHITECTURE.md#key-design-decisions).',
    '',
    '### Sidecar is the only transport',
    '',
    '### Webview — styling',
    '',
  ].join('\n'),
  'docs/codemap/core.md': '# core\n\nPart of the [code map](../../CODEMAP.md).\n',
  'webview/AGENTS.md': '# Webview\n',
}

function withDocs(overrides, run) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-docs-test-'))
  try {
    for (const [file, text] of Object.entries({ ...VALID_DOCS, ...overrides })) {
      if (text === null) continue
      fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
      fs.writeFileSync(path.join(root, file), text)
    }
    return run(root)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
}

const errorsFor = (overrides) => withDocs(overrides, (root) => checkAgentDocs(root).errors)

test('githubSlug matches GitHub heading anchors', () => {
  assert.equal(githubSlug('Notification parity'), 'notification-parity')
  assert.equal(githubSlug('Webview — styling'), 'webview--styling')
  assert.equal(githubSlug('`diagrams/`'), 'diagrams')
  assert.equal(githubSlug('Chat pane: verify/fix results'), 'chat-pane-verifyfix-results')
  assert.equal(githubSlug('Keep `snake_case` names'), 'keep-snake_case-names')
  assert.equal(githubSlug('[Linked](x.md) heading'), 'linked-heading')
})

test('isAgentDoc covers agent docs and their topic files only', () => {
  for (const file of ['AGENTS.md', 'webview/AGENTS.md', 'ARCHITECTURE.md', 'CODEMAP.md',
    'docs/architecture/webview.md', 'docs\\codemap\\core.md']) {
    assert.equal(isAgentDoc(file), true, file)
  }
  for (const file of ['README.md', 'core/README.md', 'docs/intellij-assisted-review.md',
    'docs/architecture/nested/x.md', 'docs/architecture/notes.txt', 'diagrams/architecture.md']) {
    assert.equal(isAgentDoc(file), false, file)
  }
})

test('parseMarkdown numbers duplicate headings and skips code', () => {
  const doc = parseMarkdown([
    '# Title',
    '## Same',
    '## Same',
    '```md',
    '## Not a heading [x](missing.md)',
    '```',
    'Inline `[y](missing.md)` is code; [real',
    'link](target.md "title") spans lines.',
    '<a id="Custom-Anchor"></a>',
  ].join('\n'))
  assert.deepEqual(doc.headings.map((h) => h.slug), ['title', 'same', 'same-1'])
  assert.deepEqual(doc.links, [{ url: 'target.md', line: 8 }])
  assert.deepEqual(doc.ids, ['custom-anchor'])
})

test('resolveLink handles relative, root-relative, anchor-only and external links', () => {
  assert.deepEqual(resolveLink('docs/codemap/core.md', '../../CODEMAP.md#Module-Maps'), {
    target: 'CODEMAP.md',
    anchor: 'module-maps',
  })
  assert.deepEqual(resolveLink('docs/a.md', '/core/README%20x.md'), { target: 'core/README x.md', anchor: '' })
  assert.deepEqual(resolveLink('docs/a.md', '#local'), { target: 'docs/a.md', anchor: 'local' })
  assert.equal(resolveLink('docs/a.md', 'https://example.com/x.md'), null)
  assert.equal(resolveLink('docs/a.md', 'mailto:someone@example.com'), null)
})

test('a consistent doc set has no errors or warnings', () => {
  withDocs({}, (root) => assert.deepEqual(checkAgentDocs(root), { errors: [], warnings: [] }))
})

test('broken links, anchors and links leaving the repository are errors', () => {
  const errors = errorsFor({
    'webview/AGENTS.md': '# Webview\n\n[a](../missing.md) [b](../CODEMAP.md#nope) [c](../../outside.md)\n',
  })
  assert.equal(errors.length, 3)
  assert.match(errors[0], /^webview\/AGENTS\.md:3: broken link \.\.\/missing\.md \(no missing\.md\)$/)
  assert.match(errors[1], /broken anchor \.\.\/CODEMAP\.md#nope \(no heading #nope in CODEMAP\.md\)/)
  assert.match(errors[2], /link \.\.\/\.\.\/outside\.md leaves the repository/)
})

test('every topic-file ### needs exactly one decision-index line', () => {
  const unindexed = errorsFor({
    'docs/architecture/hosts.md': `${VALID_DOCS['docs/architecture/hosts.md']}### New rule\n`,
  })
  assert.deepEqual(unindexed, [
    'docs/architecture/hosts.md:8: "### New rule" has no index line under ARCHITECTURE.md "Key design decisions"',
  ])
  const duplicated = errorsFor({
    'ARCHITECTURE.md': VALID_DOCS['ARCHITECTURE.md'].replace(
      '\n\n## Settings',
      '\n- [Again](docs/architecture/hosts.md#webview--styling)\n\n## Settings',
    ),
  })
  assert.equal(duplicated.length, 1)
  assert.match(duplicated[0], /"### Webview — styling" has 2 index lines/)
})

test('decision text in ARCHITECTURE.md itself is an error', () => {
  const errors = errorsFor({ 'ARCHITECTURE.md': `${VALID_DOCS['ARCHITECTURE.md']}### Inline rule\n` })
  assert.equal(errors.length, 1)
  assert.match(errors[0], /^ARCHITECTURE\.md:12: move "### Inline rule" into a docs\/architecture/)
})

test('topic files must be linked from their root doc', () => {
  const errors = errorsFor({ 'docs/codemap/webview.md': '# webview\n' })
  assert.deepEqual(errors, ['docs/codemap/webview.md: not linked from CODEMAP.md, so agents will not find it'])
})

test('oversized docs are warnings, not errors', () => {
  withDocs({ 'webview/AGENTS.md': `# Webview\n\n${'x'.repeat(AGENT_DOC_MAX_BYTES)}\n` }, (root) => {
    const { errors, warnings } = checkAgentDocs(root)
    assert.deepEqual(errors, [])
    assert.equal(warnings.length, 1)
    assert.match(warnings[0], /^webview\/AGENTS\.md is \d+ bytes; split it/)
  })
})

test('docs in skipped directories are ignored', () => {
  const errors = errorsFor({ 'webview/node_modules/pkg/AGENTS.md': '[x](missing.md)\n' })
  assert.deepEqual(errors, [])
})

test('the repository agent docs are consistent', () => {
  assert.deepEqual(checkAgentDocs(repoRoot).errors, [])
})
