import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  DEFAULT_JUDGE_MODEL,
  REVIEWBENCH_REF,
  gradleArgsValue,
  judgeArgs,
  parseArgs,
  piModelsConfig,
  reviewerRunArgs,
  roundsToJudge,
} from './reviewbench.mjs'

const NOW = new Date('2026-10-05T12:34:56.789Z')

test('defaults to one judged round of the test set with the official judge model', () => {
  const opts = parseArgs([], NOW)
  assert.equal(opts.set, 'test')
  assert.equal(opts.rounds, 1)
  assert.equal(opts.judgeProvider, 'github-copilot')
  assert.equal(opts.judgeModel, DEFAULT_JUDGE_MODEL)
  assert.equal(opts.ref, REVIEWBENCH_REF)
  assert.equal(opts.run, path.join('build', 'reviewbench', 'runs', '2026-10-05T12-34-56-789Z'))
  assert.deepEqual(opts.reviewerArgs, [])
})

test('passes everything after -- to the reviewer untouched', () => {
  const opts = parseArgs(['--set', 'full', '--rounds', '3', '--', '--model', 'gpt-5.5', '--rounds', 'x'], NOW)
  assert.equal(opts.set, 'full')
  assert.equal(opts.rounds, 3)
  assert.deepEqual(opts.reviewerArgs, ['--model', 'gpt-5.5', '--rounds', 'x'])
})

test('rejects invalid options', () => {
  assert.throws(() => parseArgs(['--set', 'all']), /--set must be test or full/)
  assert.throws(() => parseArgs(['--rounds', '0']), /positive integer/)
  assert.throws(() => parseArgs(['--limit']), /needs a value/)
  assert.throws(() => parseArgs(['--ref', 'main']), /commit SHA/)
  assert.throws(() => parseArgs(['--bogus']), /Unknown option/)
  assert.throws(() => parseArgs(['--skip-review', '--skip-judge']), /nothing to do/)
})

test('help short-circuits validation', () => {
  assert.equal(parseArgs(['--help', '--bogus']).help, true)
})

test('reviewer args put user overrides last', () => {
  const opts = parseArgs(['--limit', '2', '--run', 'out', '--', '--provider', 'claude'], NOW)
  assert.deepEqual(reviewerRunArgs(opts, '/c', '/r'), [
    '--corpus', '/c', '--out', 'out', '--set', 'test', '--rounds', '1',
    '--limit', '2', '--repos-dir', '/r', '--provider', 'claude',
  ])
})

test('quotes Gradle --args values that contain whitespace or quotes', () => {
  assert.equal(gradleArgsValue(['--out', '/a b', 'plain', '']), "--out '/a b' plain ''")
  assert.equal(gradleArgsValue(["it's"]), `"it's"`)
  assert.throws(() => gradleArgsValue([`a'b"c`]), /both quote types/)
})

test('judges each round separately so findings from different rounds never merge', () => {
  const opts = parseArgs(['--run', '/runs/x', '--judge-concurrency', '4'], NOW)
  const args = judgeArgs(opts, '/corpus', 2, '/judge-repos')
  assert.deepEqual(args.slice(0, 3), ['run', 'judge', '--'])
  const value = (flag) => args[args.indexOf(flag) + 1]
  assert.equal(value('--candidate'), path.resolve('/runs/x', 'findings', 'round-2'))
  assert.equal(value('--output'), path.resolve('/runs/x', 'scoring', 'round-2.json'))
  assert.equal(value('--golden'), path.join('/corpus', 'golden'))
  assert.equal(value('--model'), DEFAULT_JUDGE_MODEL)
  assert.equal(value('--concurrency'), '4')
})

test('finds rounds with findings in numeric order', () => {
  const run = fs.mkdtempSync(path.join(os.tmpdir(), 'reviewbench-test-'))
  try {
    assert.deepEqual(roundsToJudge(run), [])
    for (const dir of ['round-10', 'round-2', 'round-x', 'notes']) {
      fs.mkdirSync(path.join(run, 'findings', dir), { recursive: true })
    }
    fs.writeFileSync(path.join(run, 'findings', 'round-3'), '')
    assert.deepEqual(roundsToJudge(run), [2, 10])
  } finally {
    fs.rmSync(run, { recursive: true, force: true })
  }
})

test('registers the judge model on the configured Copilot endpoint via chat completions', () => {
  const config = piModelsConfig('https://copilot.example', 'claude-sonnet-5')
  const provider = config.providers['github-copilot']
  assert.equal(provider.baseUrl, 'https://copilot.example')
  assert.equal(provider.models.length, 1)
  assert.equal(provider.models[0].id, 'claude-sonnet-5')
  assert.equal(provider.models[0].api, 'openai-completions')
  assert.equal(provider.models[0].reasoning, true)
  assert.equal(provider.models[0].compat.supportsDeveloperRole, false)
})
