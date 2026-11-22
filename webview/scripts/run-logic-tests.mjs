import { readdirSync, statSync } from 'node:fs'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildNodeTestInvocation } from '../../scripts/compact-test-reporter.mjs'

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)))

const groups = [
  { label: 'src/lib/*.test.ts', dir: path.join(root, 'src', 'lib'), match: (name) => name.endsWith('.test.ts') },
  { label: 'src/bridge/*.test.ts', dir: path.join(root, 'src', 'bridge'), match: (name) => name.endsWith('.test.ts') },
  { label: 'src/theme/contrast.test.ts', file: path.join(root, 'src', 'theme', 'contrast.test.ts') },
]

const files = groups.flatMap((group) => {
  if ('file' in group) {
    if (!statSync(group.file, { throwIfNoEntry: false })?.isFile()) {
      throw new Error(`Expected logic test file is missing: ${path.relative(root, group.file)}`)
    }
    return [group.file]
  }
  return readdirSync(group.dir, { withFileTypes: true })
    .filter((entry) => entry.isFile() && group.match(entry.name))
    .map((entry) => path.join(group.dir, entry.name))
    .sort((left, right) => left.localeCompare(right))
})

const uniqueFiles = [...new Set(files)]
if (uniqueFiles.length !== files.length) {
  throw new Error('Logic test expansion produced duplicate files')
}
if (uniqueFiles.length === 0) {
  throw new Error('Logic test expansion matched no files')
}

const relativeFiles = uniqueFiles.map((file) => path.relative(root, file))
const { verbose, selectedFiles, nodeArgs } = buildNodeTestInvocation({
  files: relativeFiles,
  argv: process.argv.slice(2),
})
if (verbose) {
  console.error(`Logic test globs: ${groups.map((group) => group.label).join(', ')}`)
  console.error(`Logic test files (${selectedFiles.length}): ${selectedFiles.join(', ')}`)
}

const child = spawn(
  process.execPath,
  ['--import', 'tsx', ...nodeArgs],
  { cwd: root, stdio: 'inherit' },
)

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal)
    return
  }
  process.exitCode = code ?? 1
})

child.on('error', (error) => {
  console.error(error)
  process.exitCode = 1
})
