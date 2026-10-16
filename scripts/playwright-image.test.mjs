import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  checkPlaywrightImages,
  findImages,
  lockedPlaywrightVersion,
  playwrightImageErrors,
} from './playwright-image.mjs'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const DIGEST = `sha256:${'a'.repeat(64)}`
const IMAGE = `mcr.microsoft.com/playwright:v1.61.1-noble@${DIGEST}`

function lock(version) {
  const packages = version ? { 'node_modules/@playwright/test': { version } } : {}
  return JSON.stringify({ lockfileVersion: 3, packages })
}

const workflow = (image) => `jobs:\n  visual:\n    container:\n      image: ${image}\n`
const docs = (image) => `docker run --rm \\\n  ${image} \\\n  bash -lc "npm ci"\n`

function sources(overrides = {}) {
  return { 'ci.yml': workflow(IMAGE), 'release.yml': workflow(IMAGE), 'AGENTS.md': docs(IMAGE), ...overrides }
}

test('lockedPlaywrightVersion reads the locked @playwright/test version', () => {
  assert.equal(lockedPlaywrightVersion(lock('1.61.1')), '1.61.1')
  assert.throws(() => lockedPlaywrightVersion(lock()), /does not lock node_modules\/@playwright\/test/)
})

test('findImages splits tag and digest and reports 1-based lines', () => {
  assert.deepEqual(findImages(`jobs:\r\n  image: ${IMAGE}\r\n`), [
    { reference: IMAGE, tag: 'v1.61.1-noble', digest: DIGEST, line: 2 },
  ])
})

test('findImages stops at quotes and shell line continuations', () => {
  const text = '"mcr.microsoft.com/playwright:v1.61.1-noble" \\\n  mcr.microsoft.com/playwright:v1.61.1-jammy\\'
  assert.deepEqual(
    findImages(text).map((image) => [image.tag, image.digest, image.line]),
    [['v1.61.1-noble', '', 1], ['v1.61.1-jammy', '', 2]],
  )
})

test('matching digest-pinned references pass', () => {
  assert.deepEqual(playwrightImageErrors(lock('1.61.1'), sources()), [])
})

test('a tag for another Playwright version names the expected tag', () => {
  const stale = `mcr.microsoft.com/playwright:v1.60.0-noble@${DIGEST}`
  const errors = playwrightImageErrors(lock('1.61.1'), sources({ 'ci.yml': workflow(stale) }))
  assert.equal(errors.length, 2)
  assert.match(errors[0], /^ci\.yml:4: image tag v1\.60\.0-noble does not match @playwright\/test 1\.61\.1 /)
  assert.match(errors[0], /use v1\.61\.1-<distro> and its digest/)
  assert.match(errors[1], /^Playwright image references differ/)
})

test('the version must match the whole tag prefix', () => {
  for (const version of ['1.6.1', '1.61.10']) {
    const errors = playwrightImageErrors(lock(version), sources())
    assert.equal(errors.length, 3, version)
    errors.forEach((error) => assert.match(error, new RegExp(`does not match @playwright/test ${version} `)))
  }
})

test('a reference without a full sha256 digest fails', () => {
  const errors = playwrightImageErrors(lock('1.61.1'), {
    'ci.yml': workflow('mcr.microsoft.com/playwright:v1.61.1-noble'),
    'release.yml': workflow('mcr.microsoft.com/playwright:v1.61.1-noble@sha256:abc123'),
  })
  assert.equal(errors.length, 3)
  assert.match(errors[0], /^ci\.yml:4: pin v1\.61\.1-noble by digest as .*@sha256:<64 hex>/)
  assert.match(errors[1], /^release\.yml:4: pin v1\.61\.1-noble by digest/)
  assert.match(errors[2], /^Playwright image references differ/)
})

test('references that disagree are listed together with their locations', () => {
  const otherDigest = `mcr.microsoft.com/playwright:v1.61.1-noble@sha256:${'b'.repeat(64)}`
  const errors = playwrightImageErrors(lock('1.61.1'), sources({ 'AGENTS.md': docs(otherDigest) }))
  assert.equal(errors.length, 1)
  const [heading, ...locations] = errors[0].split('\n  ')
  assert.equal(heading, 'Playwright image references differ; use one tag and digest everywhere:')
  assert.deepEqual(locations, [`ci.yml:4: ${IMAGE}`, `release.yml:4: ${IMAGE}`, `AGENTS.md:2: ${otherDigest}`])
})

test('a source that lost its image reference fails', () => {
  const errors = playwrightImageErrors(lock('1.61.1'), sources({ 'release.yml': 'jobs: {}\n' }))
  assert.deepEqual(errors, [
    'release.yml: no mcr.microsoft.com/playwright image reference found; ' +
      'see "Playwright container image" in docs/architecture/ci.md',
  ])
})

test('the repository pins one image that matches the lockfile', () => {
  assert.deepEqual(checkPlaywrightImages(repoRoot), [])
})
