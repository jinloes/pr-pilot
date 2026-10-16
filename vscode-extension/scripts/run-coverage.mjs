import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const extensionDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const c8Bin = path.join(extensionDir, 'node_modules', 'c8', 'bin', 'c8.js')
const result = spawnSync(
  process.execPath,
  [
    c8Bin,
    '--all',
    '--exclude-after-remap',
    '--include', 'src/**/*.ts',
    '--exclude', 'test/**',
    '--exclude', 'integration-test/**',
    '--reporter=text',
    '--reporter=lcov',
    '--report-dir', 'coverage',
    process.execPath,
    './scripts/run-unit-tests.mjs',
  ],
  {
    cwd: extensionDir,
    stdio: 'inherit',
    shell: false,
    env: { ...process.env, PR_PILOT_KEEP_DIST_TEST: '1' },
  },
)
if (result.error) console.error(result.error.message)
try {
  relativizeLcov(path.join(extensionDir, 'coverage', 'lcov.info'))
} finally {
  fs.rmSync(path.join(extensionDir, 'dist-test'), { recursive: true, force: true })
}
process.exit(result.status ?? 1)

// diff-cover runs from the repository root and matches LCOV SF: entries against git diff paths.
function relativizeLcov(lcovPath) {
  if (!fs.existsSync(lcovPath)) return
  const repoRoot = path.resolve(extensionDir, '..')
  const content = fs.readFileSync(lcovPath, 'utf8')
  const normalized = content.replace(/^SF:(.+)$/gm, (_, sourcePath) => {
    const absolute = path.isAbsolute(sourcePath)
      ? sourcePath
      : path.resolve(extensionDir, sourcePath)
    return `SF:${path.relative(repoRoot, absolute).replaceAll('\\', '/')}`
  })
  fs.writeFileSync(lcovPath, normalized)
}