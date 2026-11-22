import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { buildNodeTestInvocation } from '../../scripts/compact-test-reporter.mjs'
import { npmCliInvocation } from '../../scripts/portable-process.mjs'

const npm = npmCliInvocation(['run', 'build:test'])
const build = spawnSync(npm.command, npm.args, { stdio: 'inherit', shell: false })
let status = build.status ?? 1
if (status === 0) {
  const testFiles = fs.readdirSync('dist-test/test')
    .filter((name) => name.endsWith('.test.js'))
    .map((name) => `dist-test/test/${name}`)
  const scriptTests = fs.readdirSync('../scripts')
    .filter((name) => name.endsWith('.test.mjs'))
    .map((name) => `../scripts/${name}`)
  const { nodeArgs } = buildNodeTestInvocation({
    files: [...testFiles, ...scriptTests],
    argv: process.argv.slice(2),
  })
  const tests = spawnSync(process.execPath, nodeArgs, { stdio: 'inherit', shell: false })
  status = tests.status ?? 1
}
if (process.env.PR_PILOT_KEEP_DIST_TEST !== '1') {
  fs.rmSync('dist-test', { recursive: true, force: true })
}
process.exit(status)
