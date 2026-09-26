import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  coverageGain,
  formatBudget,
  parseDiffCoverage,
  splitDiffCoverage,
  unlistedCount,
  type DiffCoverage,
} from './diffCoverage'

interface GoldenCase {
  name: string
  input: string
  body: string
  coverage: DiffCoverage | null
}

// Shared with core/src/test/java/com/jinloes/prpilot/model/DiffCoverageTest.java, so both
// parsers must reach the same verdict for every case.
const GOLDEN = new URL('../../../core/src/test/resources/diff-coverage/trailer.golden.txt', import.meta.url)

function loadGolden(): GoldenCase[] {
  const cases: GoldenCase[] = []
  let name: string | null = null
  let input: string | null = null
  let body: string | null = null
  let coverage: string | null = null
  let paths: string[] = []
  for (const line of readFileSync(GOLDEN, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue
    if (line.startsWith('case ')) {
      name = line.slice('case '.length)
      input = null
      body = null
      coverage = null
      paths = []
    } else if (keyword(line, 'input')) {
      input = unescape(value(line, 'input'))
    } else if (keyword(line, 'body')) {
      body = unescape(value(line, 'body'))
    } else if (keyword(line, 'coverage')) {
      coverage = value(line, 'coverage')
    } else if (keyword(line, 'path')) {
      paths.push(unescape(value(line, 'path')))
    } else if (line === 'end') {
      if (name === null || input === null || body === null || coverage === null) {
        throw new Error(`incomplete golden case ${name}`)
      }
      cases.push({ name, input, body, coverage: expectedCoverage(coverage, paths) })
      name = null
    } else {
      throw new Error(`unrecognized golden line: ${line}`)
    }
  }
  return cases
}

function expectedCoverage(spec: string, paths: string[]): DiffCoverage | null {
  if (spec === 'none') {
    if (paths.length > 0) throw new Error('paths on a rejected case')
    return null
  }
  const fields = spec.split(' ')
  if (fields.length !== 4 || Number(fields[1]) !== paths.length) throw new Error(`bad coverage spec: ${spec}`)
  return {
    omitted: Number(fields[0]),
    listed: paths.length,
    budget: Number(fields[2]),
    scanComplete: fields[3] === 'complete',
    paths,
  }
}

function keyword(line: string, name: string): boolean {
  return line === name || line.startsWith(`${name} `)
}

function value(line: string, name: string): string {
  return line.length === name.length ? '' : line.slice(name.length + 1)
}

function unescape(escaped: string): string {
  let out = ''
  for (let index = 0; index < escaped.length; index++) {
    const c = escaped[index]
    if (c !== '\\') {
      out += c
      continue
    }
    if (index + 1 >= escaped.length) throw new Error('dangling escape')
    const next = escaped[++index]
    if (next === '\\') out += '\\'
    else if (next === 'n') out += '\n'
    else if (next === 'r') out += '\r'
    else if (next === 't') out += '\t'
    else if (next === 'u') {
      if (index + 4 >= escaped.length) throw new Error('short \\u escape')
      out += String.fromCharCode(Number.parseInt(escaped.slice(index + 1, index + 5), 16))
      index += 4
    } else {
      throw new Error(`unknown escape \\${next}`)
    }
  }
  return out
}

function trailerOf(coverage: DiffCoverage): string {
  const scan = coverage.scanComplete ? 'complete' : 'incomplete'
  return `[pr-pilot:diff-coverage] omitted=${coverage.omitted} listed=${coverage.listed} budget=${coverage.budget} scan=${scan}\n`
    + coverage.paths.map((path) => `[pr-pilot:omitted] ${path}\n`).join('')
}

const goldenCases = loadGolden()

void test('the shared golden fixture loads accepted and rejected cases', () => {
  assert.ok(goldenCases.length >= 30)
  assert.ok(goldenCases.some((golden) => golden.coverage !== null))
  assert.ok(goldenCases.some((golden) => golden.coverage === null))
  assert.equal(goldenCases.find((golden) => golden.name === 'mid-body-header-is-not-a-trailer')?.coverage, null)
})

for (const golden of goldenCases) {
  void test(`splitDiffCoverage reaches the shared verdict: ${golden.name}`, () => {
    const split = splitDiffCoverage(golden.input)

    assert.deepEqual(split, { body: golden.body, coverage: golden.coverage })
    assert.deepEqual(parseDiffCoverage(golden.input), golden.coverage)
    if (golden.coverage === null) {
      assert.equal(split.body, golden.input)
    } else {
      assert.equal(split.body + trailerOf(golden.coverage), golden.input)
    }
  })
}

void test('a legacy byte-cut marker is not coverage', () => {
  const legacyMarker = `[... diff ${'trun'}cated at ${250} KB ...]`

  assert.equal(parseDiffCoverage(`diff --git a/a b/a\n+y\n\n${legacyMarker}`), null)
  assert.equal(parseDiffCoverage(`diff --git a/a b/a\n+y\n\n${legacyMarker}\n`), null)
})

void test('missing input parses to no coverage and an empty body', () => {
  assert.deepEqual(splitDiffCoverage(undefined), { body: '', coverage: null })
  assert.deepEqual(splitDiffCoverage(null), { body: '', coverage: null })
})

void test('unlistedCount counts omitted files the trailer does not name', () => {
  const coverage = parseDiffCoverage(
    '[pr-pilot:diff-coverage] omitted=5 listed=2 budget=250000 scan=complete\n'
    + '[pr-pilot:omitted] a.ts\n[pr-pilot:omitted] b.ts\n',
  )

  assert.ok(coverage)
  assert.equal(unlistedCount(coverage), 3)
})

void test('coverageGain counts files the chunked diff includes beyond the review diff', () => {
  const omitting = (omitted: number): DiffCoverage => ({
    omitted,
    listed: 0,
    budget: 250000,
    scanComplete: true,
    paths: [],
  })

  assert.equal(coverageGain(omitting(3), null), 3)
  assert.equal(coverageGain(omitting(3), omitting(1)), 2)
  assert.equal(coverageGain(omitting(2), omitting(2)), 0)
  assert.equal(coverageGain(omitting(1), omitting(4)), 0)
  assert.equal(coverageGain(null, omitting(2)), 0)
  assert.equal(coverageGain(null, null), 0)
})

void test('formatBudget renders decimal byte budgets', () => {
  assert.equal(formatBudget(250000), '250 KB')
  assert.equal(formatBudget(1000000), '1 MB')
  assert.equal(formatBudget(1500000), '1.5 MB')
  assert.equal(formatBudget(16384), '16.4 KB')
  assert.equal(formatBudget(512), '512 bytes')
})
