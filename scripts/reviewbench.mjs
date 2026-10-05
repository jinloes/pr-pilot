// Runs PR Pilot against ReviewBench (https://github.com/review-bench/ReviewBench) and scores it
// with ReviewBench's own judge. Both the reviews and the judge can run on a Copilot seat.
//
//   node scripts/reviewbench.mjs                          # 25-PR test set, 1 round
//   node scripts/reviewbench.mjs --limit 1                # smoke test
//   node scripts/reviewbench.mjs --set full --rounds 3    # leaderboard-shaped run (expensive)
//   node scripts/reviewbench.mjs --run build/reviewbench/runs/<id>   # resume or re-judge a run
//   node scripts/reviewbench.mjs -- --model gpt-5.5 --effort high  # args after -- go to the reviewer
//
// Results are private tuning numbers: only ReviewBench's portal can publish leaderboard scores.
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { gradleWrapperInvocation } from './portable-process.mjs'
import { nodeVersionSupported, MIN_NODE } from './verify.mjs'

export const REVIEWBENCH_URL = 'https://github.com/review-bench/ReviewBench'
// Pinned so golden-set or judge-prompt changes upstream never silently move our numbers.
export const REVIEWBENCH_REF = 'ceb0794a3768da6ef4a56e5311dfb4afd29e5dee'
export const DEFAULT_JUDGE_PROVIDER = 'github-copilot'
// ReviewBench's official judge model.
export const DEFAULT_JUDGE_MODEL = 'claude-sonnet-5'
export const DEFAULT_COPILOT_BASE_URL = 'https://api.githubcopilot.com'

const COPILOT_HEADERS = {
  'User-Agent': 'GitHubCopilotChat/0.35.0',
  'Editor-Version': 'vscode/1.107.0',
  'Editor-Plugin-Version': 'copilot-chat/0.35.0',
  'Copilot-Integration-Id': 'vscode-chat',
}

const USAGE = `Usage: node scripts/reviewbench.mjs [options] [-- reviewer options]

  --set test|full           25-PR test set or full 219-PR set (default test)
  --rounds N                Review every PR N times; each round is judged separately (default 1)
  --limit N                 Only the first N PRs
  --run DIR                 Run directory to create, resume, or re-judge
                            (default build/reviewbench/runs/<timestamp>)
  --skip-review             Only judge existing findings in --run
  --skip-judge              Only produce findings
  --judge-provider NAME     pi provider for the judge (default ${DEFAULT_JUDGE_PROVIDER})
  --judge-model ID          Judge model (default ${DEFAULT_JUDGE_MODEL}, the official judge)
  --judge-concurrency N     PRs judged in parallel (default 2)
  --copilot-base-url URL    Copilot API endpoint for the judge (default ${DEFAULT_COPILOT_BASE_URL})
  --ref SHA                 ReviewBench commit (default ${REVIEWBENCH_REF.slice(0, 12)})
  --help                    Show this help

Reviewer options after -- are passed to ./gradlew :review-benchmark:reviewBench; run
"./gradlew :review-benchmark:reviewBench --args=--help" to list them.

The Copilot judge authenticates with COPILOT_GITHUB_TOKEN, or "gh auth token" when it is unset.`

const VALUE_OPTIONS = new Set([
  '--set',
  '--rounds',
  '--limit',
  '--run',
  '--judge-provider',
  '--judge-model',
  '--judge-concurrency',
  '--copilot-base-url',
  '--ref',
])
const POSITIVE_OPTIONS = new Set(['--rounds', '--limit', '--judge-concurrency'])

export function parseArgs(argv, now = new Date()) {
  const separator = argv.indexOf('--')
  const own = separator === -1 ? argv : argv.slice(0, separator)
  const reviewerArgs = separator === -1 ? [] : argv.slice(separator + 1)
  const opts = {
    set: 'test',
    rounds: 1,
    limit: 0,
    run: path.join('build', 'reviewbench', 'runs', now.toISOString().replace(/[:.]/g, '-')),
    skipReview: false,
    skipJudge: false,
    judgeProvider: DEFAULT_JUDGE_PROVIDER,
    judgeModel: DEFAULT_JUDGE_MODEL,
    judgeConcurrency: 2,
    copilotBaseUrl: DEFAULT_COPILOT_BASE_URL,
    ref: REVIEWBENCH_REF,
    help: false,
    reviewerArgs,
  }
  for (let i = 0; i < own.length; i++) {
    const arg = own[i]
    if (arg === '--help' || arg === '-h') return { ...opts, help: true }
    if (arg === '--skip-review') opts.skipReview = true
    else if (arg === '--skip-judge') opts.skipJudge = true
    else if (VALUE_OPTIONS.has(arg)) {
      const value = own[++i]
      if (value === undefined || value.trim() === '') throw new Error(`${arg} needs a value.`)
      const key = arg.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase())
      if (POSITIVE_OPTIONS.has(arg)) {
        if (!/^[1-9]\d*$/.test(value)) throw new Error(`${arg} must be a positive integer.`)
        opts[key] = Number(value)
      } else {
        opts[key] = value.trim()
      }
    } else {
      throw new Error(`Unknown option: ${arg}`)
    }
  }
  if (!['test', 'full'].includes(opts.set)) throw new Error('--set must be test or full.')
  if (!/^[0-9a-f]{7,40}$/.test(opts.ref)) throw new Error('--ref must be a commit SHA.')
  if (opts.skipReview && opts.skipJudge) throw new Error('--skip-review and --skip-judge leave nothing to do.')
  return opts
}

/** Arguments for the Java runner; reviewer options come last so they can override defaults. */
export function reviewerRunArgs(opts, corpusDir, reposDir) {
  const args = ['--corpus', corpusDir, '--out', opts.run, '--set', opts.set, '--rounds', String(opts.rounds)]
  if (opts.limit > 0) args.push('--limit', String(opts.limit))
  args.push('--repos-dir', reposDir, ...opts.reviewerArgs)
  return args
}

/** Gradle splits --args on whitespace but honours quotes, so quote any argument that needs it. */
export function gradleArgsValue(args) {
  return args
    .map((arg) => {
      if (arg !== '' && !/[\s'"]/.test(arg)) return arg
      if (!arg.includes("'")) return `'${arg}'`
      if (!arg.includes('"')) return `"${arg}"`
      throw new Error(`Cannot pass an argument containing both quote types to Gradle: ${arg}`)
    })
    .join(' ')
}

/** The rounds that have findings, so a partial or re-judged run scores what exists. */
export function roundsToJudge(run) {
  const findings = path.join(run, 'findings')
  if (!fs.existsSync(findings)) return []
  return fs
    .readdirSync(findings, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && /^round-\d+$/.test(entry.name))
    .map((entry) => Number(entry.name.slice('round-'.length)))
    .sort((a, b) => a - b)
}

export function judgeArgs(opts, corpusDir, round, judgeReposDir) {
  return [
    'run',
    'judge',
    '--',
    '--candidate',
    path.resolve(opts.run, 'findings', `round-${round}`),
    '--golden',
    path.join(corpusDir, 'golden'),
    '--provider',
    opts.judgeProvider,
    '--model',
    opts.judgeModel,
    '--output',
    path.resolve(opts.run, 'scoring', `round-${round}.json`),
    '--repo-dir',
    judgeReposDir,
    '--concurrency',
    String(opts.judgeConcurrency),
  ]
}

/**
 * pi's built-in Copilot entries target the individual-plan endpoint and predate the judge model,
 * so register the judge model on the endpoint every Copilot plan can reach. Chat completions is
 * used because pi sends Anthropic's older budget-thinking request shape, which Claude Sonnet 5
 * rejects, while chat completions maps pi's thinking level to reasoning effort.
 */
export function piModelsConfig(baseUrl, judgeModel) {
  return {
    providers: {
      'github-copilot': {
        baseUrl,
        models: [
          {
            id: judgeModel,
            name: judgeModel,
            api: 'openai-completions',
            reasoning: true,
            input: ['text'],
            contextWindow: 200000,
            maxTokens: 32000,
            headers: COPILOT_HEADERS,
            // Copilot's Claude endpoint silently drops "developer" messages, which pi sends for
            // reasoning models by default, so the judge would never see its JSON instructions.
            compat: { supportsDeveloperRole: false, supportsStore: false },
          },
        ],
      },
    },
  }
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', shell: false, ...options })
  if (result.error) throw result.error
  return result.status ?? 1
}

function capture(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', shell: false, ...options })
  return result.status === 0 ? result.stdout.trim() : ''
}

function npm(args, cwd, env) {
  if (process.env.npm_execpath) {
    return run(process.execPath, [process.env.npm_execpath, ...args], { cwd, env })
  }
  const win = process.platform === 'win32'
  return run(win ? 'npm.cmd' : 'npm', args, { cwd, env, shell: win })
}

function ensureCorpus(corpusDir, ref) {
  if (!fs.existsSync(path.join(corpusDir, '.git'))) {
    console.log(`Cloning ReviewBench into ${corpusDir}`)
    if (run('git', ['clone', '--quiet', '--', REVIEWBENCH_URL, corpusDir]) !== 0) {
      throw new Error('Could not clone ReviewBench.')
    }
  }
  if (capture('git', ['rev-parse', 'HEAD'], { cwd: corpusDir }) !== ref) {
    if (capture('git', ['cat-file', '-t', `${ref}^{commit}`], { cwd: corpusDir }) !== 'commit') {
      run('git', ['fetch', '--quiet', 'origin'], { cwd: corpusDir })
    }
    if (run('git', ['checkout', '--quiet', '--detach', ref], { cwd: corpusDir }) !== 0) {
      throw new Error(`Could not check out ReviewBench ${ref}.`)
    }
  }
}

function ensureJudgeDependencies(corpusDir) {
  const lock = path.join(corpusDir, 'package-lock.json')
  const marker = path.join(corpusDir, 'node_modules', '.pr-pilot-lock')
  const lockText = fs.readFileSync(lock, 'utf8')
  if (fs.existsSync(marker) && fs.readFileSync(marker, 'utf8') === lockText) return
  console.log('Installing ReviewBench judge dependencies')
  if (npm(['ci', '--no-audit', '--no-fund'], corpusDir) !== 0) {
    throw new Error('npm ci failed in the ReviewBench checkout.')
  }
  fs.writeFileSync(marker, lockText)
}

function judgeEnv(opts, piDir) {
  const env = { ...process.env, PI_CODING_AGENT_DIR: piDir }
  if (opts.judgeProvider !== 'github-copilot') return env
  fs.mkdirSync(piDir, { recursive: true })
  fs.writeFileSync(
    path.join(piDir, 'models.json'),
    `${JSON.stringify(piModelsConfig(opts.copilotBaseUrl, opts.judgeModel), null, 2)}\n`,
  )
  if (!env.COPILOT_GITHUB_TOKEN) {
    const token = capture('gh', ['auth', 'token'])
    if (!token) throw new Error('Set COPILOT_GITHUB_TOKEN or run "gh auth login" for the Copilot judge.')
    env.COPILOT_GITHUB_TOKEN = token
  }
  return env
}

function main(argv) {
  if (!nodeVersionSupported(process.version)) {
    console.error(`Node ${process.version} is too old; use >= ${MIN_NODE.join('.')} (see .nvmrc).`)
    return 1
  }
  let opts
  try {
    opts = parseArgs(argv)
  } catch (invalid) {
    console.error(`${invalid.message}\n\n${USAGE}`)
    return 2
  }
  if (opts.help) {
    console.log(USAGE)
    return 0
  }
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const workDir = path.join(repoRoot, 'build', 'reviewbench')
  const corpusDir = path.join(workDir, 'ReviewBench')
  opts.run = path.resolve(repoRoot, opts.run)

  ensureCorpus(corpusDir, opts.ref)

  if (!opts.skipReview) {
    const args = reviewerRunArgs(opts, corpusDir, path.join(workDir, 'repos'))
    const gradle = gradleWrapperInvocation(
      [':review-benchmark:reviewBench', '--console=plain', `--args=${gradleArgsValue(args)}`],
      { repoRoot },
    )
    const failureLog = path.join(opts.run, 'failures.tsv')
    fs.rmSync(failureLog, { force: true })
    if (run(gradle.command, gradle.args, { cwd: repoRoot }) !== 0) {
      console.error(
        fs.existsSync(failureLog)
          ? `\nSome reviews failed (see ${failureLog}). Judging a partial set would overstate` +
              ` recall, so rerun with --run ${opts.run} to retry them.`
          : '\nThe reviewer did not run; see the error above.',
      )
      return 1
    }
  }
  if (opts.skipJudge) return 0

  const rounds = roundsToJudge(opts.run)
  if (rounds.length === 0) {
    console.error(`No findings under ${path.join(opts.run, 'findings')}.`)
    return 1
  }
  ensureJudgeDependencies(corpusDir)
  const env = judgeEnv(opts, path.join(workDir, 'pi-agent'))
  let failed = 0
  for (const round of rounds) {
    console.log(`\nJudging round ${round} with ${opts.judgeProvider}/${opts.judgeModel}`)
    const args = judgeArgs(opts, corpusDir, round, path.join(workDir, 'judge-repos'))
    if (npm(args, corpusDir, env) !== 0) failed++
  }
  console.log(failed === 0 ? `\nScores: ${path.join(opts.run, 'scoring')}` : `\n${failed} round(s) failed to judge.`)
  return failed === 0 ? 0 : 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)))
}
