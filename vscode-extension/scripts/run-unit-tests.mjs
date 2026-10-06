import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { npmCliInvocation } from '../../scripts/portable-process.mjs'

const npm = npmCliInvocation(['run', 'build:test'])
const build = spawnSync(npm.command, npm.args, { stdio: 'inherit', shell: false })
let status = build.status ?? 1
if (status === 0) {
  const testFiles = fs.readdirSync('dist-test/test')
    .filter((name) => name.endsWith('.test.js'))
    .map((name) => `dist-test/test/${name}`)
  const tests = spawnSync(
    process.execPath,
    ['--test', ...testFiles, '../scripts/portable-process.test.mjs', '../scripts/verify.test.mjs', '../scripts/reviewbench.test.mjs', '../scripts/reviewbench-report.test.mjs'],
    { stdio: 'inherit', shell: false },
  )
  status = tests.status ?? 1
}
fs.rmSync('dist-test', { recursive: true, force: true })
process.exit(status)
