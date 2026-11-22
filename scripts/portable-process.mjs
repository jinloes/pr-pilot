import fs from 'node:fs'
import path from 'node:path'

function quoteCmdArg(arg) {
  if (!/[\s"&|<>^()]/.test(arg)) return arg
  return `"${arg.replace(/"/g, '""')}"`
}

export function npmCliInvocation(args, options = {}) {
  const nodeExecutable = options.nodeExecutable ?? process.execPath
  const npmCli = options.npmCli ?? process.env.npm_execpath
  if (!npmCli) {
    throw new Error('npm_execpath is unavailable; run this command through an npm script.')
  }
  return { command: nodeExecutable, args: [npmCli, ...args], shell: false }
}

/**
 * Resolves a shell-free npm invocation even when called directly via `node` (without
 * `npm_execpath`), avoiding Node 24+ DEP0190 (`shell: true` with arguments) on Windows.
 */
export function resolveNpmInvocation(args, options = {}) {
  const platform = options.platform ?? process.platform
  const paths = platform === 'win32' ? path.win32 : path.posix
  const nodeExecutable = options.nodeExecutable ?? process.execPath
  const exists = options.existsSync ?? fs.existsSync
  const env = options.env ?? process.env

  const explicitCli = options.npmCli ?? env.npm_execpath
  if (explicitCli) {
    return { command: nodeExecutable, args: [explicitCli, ...args], shell: false }
  }

  const nodeDir = paths.dirname(nodeExecutable)
  const bundledCandidates =
    platform === 'win32'
      ? [
          paths.join(nodeDir, 'node_modules', 'npm', 'bin', 'npm-cli.js'),
          paths.join(nodeDir, '..', 'node_modules', 'npm', 'bin', 'npm-cli.js'),
        ]
      : [
          paths.join(nodeDir, '..', 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js'),
          paths.join(nodeDir, 'node_modules', 'npm', 'bin', 'npm-cli.js'),
        ]
  for (const candidate of bundledCandidates) {
    if (exists(candidate)) {
      return { command: nodeExecutable, args: [candidate, ...args], shell: false }
    }
  }

  if (platform === 'win32') {
    const comspec = env.ComSpec || 'cmd.exe'
    const cmdLine = ['npm.cmd', ...args].map(quoteCmdArg).join(' ')
    return {
      command: comspec,
      args: ['/d', '/s', '/c', `"${cmdLine}"`],
      shell: false,
      windowsVerbatimArguments: true,
    }
  }
  return { command: 'npm', args: [...args], shell: false }
}

export function gradleWrapperInvocation(args, options = {}) {
  const platform = options.platform ?? process.platform
  const paths = platform === 'win32' ? path.win32 : path
  const repoRoot = paths.resolve(options.repoRoot ?? process.cwd())
  const javaHome = options.javaHome ?? process.env.JAVA_HOME
  const javaExecutable = javaHome
    ? paths.join(javaHome, 'bin', platform === 'win32' ? 'java.exe' : 'java')
    : platform === 'win32' ? 'java.exe' : 'java'
  return {
    command: javaExecutable,
    args: [
      '-Dorg.gradle.appname=gradlew',
      '-classpath',
      paths.join(repoRoot, 'gradle', 'wrapper', 'gradle-wrapper.jar'),
      'org.gradle.wrapper.GradleWrapperMain',
      ...args,
    ],
  }
}
