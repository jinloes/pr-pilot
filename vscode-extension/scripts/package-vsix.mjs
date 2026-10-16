import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { gradleWrapperInvocation, npmCliInvocation } from '../../scripts/portable-process.mjs'

function run(invocation, cwd) {
  const result = spawnSync(invocation.command, invocation.args, {
    cwd,
    stdio: 'inherit',
    shell: false,
  })
  if (result.error) console.error(result.error.message)
  if (result.status !== 0) process.exit(result.status ?? 1)
}

const extensionDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = path.resolve(extensionDir, '..')
run(npmCliInvocation(['run', 'build']), path.join(repoRoot, 'webview'))
run(gradleWrapperInvocation([':sidecar:bootJar'], { repoRoot }), repoRoot)
run(npmCliInvocation(['run', 'build']), extensionDir)
run(npmCliInvocation(['run', 'stage:webview']), extensionDir)
run(npmCliInvocation(['run', 'stage:sidecar']), extensionDir)
run(npmCliInvocation(['run', 'vsce', '--', 'package', ...process.argv.slice(2)]), extensionDir)
