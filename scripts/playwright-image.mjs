// Keeps every Playwright container image reference in step with the @playwright/test version locked
// in webview/package-lock.json. Visual baselines are only valid in the matching image, so CI, the
// release workflow, and the documented local command must all use one tag and one digest.
//
//   node scripts/playwright-image.mjs   # exits 1 and explains each mismatch
//
// scripts/verify.mjs also runs this in-process when one of these files changes.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const LOCKFILE = 'webview/package-lock.json'
export const IMAGE_SOURCES = [
  '.github/workflows/ci.yml',
  '.github/workflows/release.yml',
  'webview/AGENTS.md',
]

const IMAGE = /mcr\.microsoft\.com\/playwright:([^\s"'`\\]+)/g
const DIGEST = /^sha256:[0-9a-f]{64}$/
const HOW_TO_BUMP = 'see "Playwright container image" in docs/architecture/ci.md'

export function lockedPlaywrightVersion(lockText) {
  const version = JSON.parse(lockText).packages?.['node_modules/@playwright/test']?.version
  if (!version) throw new Error(`${LOCKFILE} does not lock node_modules/@playwright/test`)
  return version
}

/** Returns each image reference in `text` with its tag, digest (or ''), and 1-based line. */
export function findImages(text) {
  return [...text.matchAll(IMAGE)].map((match) => {
    const [tag, digest = ''] = match[1].split('@')
    return { reference: match[0], tag, digest, line: text.slice(0, match.index).split('\n').length }
  })
}

/** `sources` maps a repository-relative file name to its contents. */
export function playwrightImageErrors(lockText, sources) {
  const version = lockedPlaywrightVersion(lockText)
  const errors = []
  const found = []
  for (const [file, text] of Object.entries(sources)) {
    const images = findImages(text)
    if (images.length === 0) {
      errors.push(`${file}: no mcr.microsoft.com/playwright image reference found; ${HOW_TO_BUMP}`)
    }
    for (const image of images) {
      const where = `${file}:${image.line}`
      found.push({ where, reference: image.reference })
      if (!image.tag.startsWith(`v${version}-`)) {
        errors.push(
          `${where}: image tag ${image.tag} does not match @playwright/test ${version} in ` +
            `${LOCKFILE}; use v${version}-<distro> and its digest (${HOW_TO_BUMP})`,
        )
      }
      if (!DIGEST.test(image.digest)) {
        errors.push(
          `${where}: pin ${image.tag} by digest as ` +
            `mcr.microsoft.com/playwright:${image.tag}@sha256:<64 hex> (${HOW_TO_BUMP})`,
        )
      }
    }
  }
  if (new Set(found.map((image) => image.reference)).size > 1) {
    const listing = found.map((image) => `${image.where}: ${image.reference}`).join('\n  ')
    errors.push(`Playwright image references differ; use one tag and digest everywhere:\n  ${listing}`)
  }
  return errors
}

export function checkPlaywrightImages(repoRoot) {
  const read = (file) => fs.readFileSync(path.join(repoRoot, file), 'utf8')
  const sources = Object.fromEntries(IMAGE_SOURCES.map((file) => [file, read(file)]))
  return playwrightImageErrors(read(LOCKFILE), sources)
}

function main() {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  let errors
  try {
    errors = checkPlaywrightImages(repoRoot)
  } catch (error) {
    errors = [String(error?.message ?? error)]
  }
  if (errors.length === 0) {
    console.log(`Playwright image pins match ${LOCKFILE}.`)
    return 0
  }
  errors.forEach((error) => console.error(error))
  return 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main())
}
