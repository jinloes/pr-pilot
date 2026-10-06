import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  compareSpread,
  countBy,
  falsePositives,
  loadGolden,
  loadRun,
  metricSpreads,
  misses,
  parseArgs,
  perPrRecall,
  renderReport,
  severityRank,
  spread,
  writeReport,
} from './reviewbench-report.mjs'

const KEY = 'o_r_1-aaaaaaaa'
const OTHER = 'o_s_2-bbbbbbbb'

function metrics(gp, gr) {
  return { grounded_precision: gp, grounded_recall: gr, augmented_precision: gp, augmented_recall: gr }
}

function summary(recall, perPr) {
  const overall = { ...metrics(0.5, recall), golden_tp_count: 3 }
  return {
    pr_count: perPr.length,
    micro: {
      overall,
      by_severity: { high: { grounded_recall: recall, golden_tp_count: 1 }, low: { grounded_recall: 1, golden_tp_count: 2 } },
      by_category: { correctness: { grounded_recall: recall, golden_tp_count: 3 } },
    },
    macro: { overall, by_severity: {}, by_category: {} },
    per_pr: perPr.map(([key, r]) => ({ pr_key: key, metrics: { overall: metrics(0.5, r) } })),
  }
}

function candidate(file, line, status, covered, category = 'correctness') {
  return {
    file,
    start_line: line,
    end_line: line,
    message: `finding at ${line}`,
    status,
    covered_golden_indices: covered,
    result_category: category,
  }
}

const GOLDEN = [
  { file: 'a.py', start_line: 10, end_line: 12, message: 'Off by one.', tp_fp: 'tp', severity: 'high', category: 'correctness' },
  { file: 'a.py', start_line: 40, end_line: 40, message: 'Rename.', tp_fp: 'tp', severity: 'low', category: 'maintainability' },
  { file: 'b.py', start_line: 5, end_line: 5, message: 'Noise.', tp_fp: 'fp', severity: 'low', category: 'style' },
  { file: 'c.py', start_line: 1, end_line: 1, message: 'Leak.', tp_fp: 'tp', severity: 'medium', category: 'reliability' },
]

function writeRun(root, name, rounds) {
  const dir = path.join(root, name)
  for (const { round, summary: s, details, diagnostics = {} } of rounds) {
    fs.mkdirSync(path.join(dir, 'scoring'), { recursive: true })
    fs.writeFileSync(path.join(dir, 'scoring', `round-${round}.json`), JSON.stringify(s))
    fs.writeFileSync(path.join(dir, 'scoring', `round-${round}.details.json`), JSON.stringify(details))
    for (const [key, value] of Object.entries(diagnostics)) {
      const diagDir = path.join(dir, 'diagnostics', `round-${round}`)
      fs.mkdirSync(diagDir, { recursive: true })
      fs.writeFileSync(path.join(diagDir, `${key}.json`), JSON.stringify(value))
    }
  }
  return dir
}

function fixture(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'reviewbench-report-'))
  try {
    const goldenDir = path.join(root, 'golden')
    fs.mkdirSync(goldenDir)
    fs.writeFileSync(path.join(goldenDir, `${KEY}.json`), JSON.stringify({ findings: GOLDEN }))
    const run = writeRun(root, 'run', [
      {
        round: 1,
        summary: summary(0.6, [[KEY, 0.6]]),
        details: [
          {
            pr_key: KEY,
            candidate_findings: [
              candidate('a.py', 40, 'matched_tp', [1]),
              candidate('a.py', 90, 'novel_fp', [], 'style'),
            ],
          },
        ],
        diagnostics: { [KEY]: { stages: [], dropped: [{ file: 'a.py', line: 14, message: 'dropped bound check' }] } },
      },
      {
        round: 2,
        summary: summary(0.8, [[KEY, 0.8]]),
        details: [
          {
            pr_key: KEY,
            candidate_findings: [candidate('a.py', 40, 'matched_tp', [1]), candidate('c.py', 1, 'matched_tp', [3])],
          },
        ],
      },
    ])
    fn({ root, goldenDir, run })
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
}

test('parseArgs requires --run and validates the window', () => {
  assert.deepEqual(parseArgs(['--run', 'r', '--baseline', 'b', '--window', '0']), {
    run: 'r',
    baseline: 'b',
    golden: '',
    window: 0,
    out: '',
    help: false,
  })
  assert.throws(() => parseArgs([]), /--run is required/)
  assert.throws(() => parseArgs(['--run', 'r', '--window', '-1']), /non-negative/)
  assert.throws(() => parseArgs(['--run']), /needs a value/)
  assert.throws(() => parseArgs(['--bogus']), /Unknown option/)
  assert.equal(parseArgs(['--help']).help, true)
})

test('spread ignores missing values', () => {
  assert.deepEqual(spread([0.2, null, 0.6, undefined]), { mean: 0.4, min: 0.2, max: 0.6, n: 2 })
  assert.equal(spread([null]), null)
})

test('severityRank orders known severities first', () => {
  assert.deepEqual(['low', 'weird', 'HIGH', 'medium'].sort((a, b) => severityRank(a) - severityRank(b)), [
    'HIGH',
    'medium',
    'low',
    'weird',
  ])
})

test('compareSpread only calls a change beyond noise when round ranges separate', () => {
  const s = (mean, min, max, n = 3) => ({ mean, min, max, n })
  assert.deepEqual(compareSpread(s(0.3, 0.25, 0.35), s(0.5, 0.45, 0.55)).verdict, 'beyond noise')
  assert.deepEqual(compareSpread(s(0.3, 0.2, 0.5), s(0.4, 0.3, 0.5)).verdict, 'within noise')
  assert.equal(compareSpread(s(0.3, 0.3, 0.3, 1), s(0.9, 0.9, 0.9, 1)).verdict, 'unknown (one round)')
  assert.deepEqual(compareSpread(null, s(0.4, 0.4, 0.4)), { delta: null, verdict: '' })
})

test('countBy sorts by count then name', () => {
  assert.deepEqual(countBy([{ c: 'b' }, { c: 'a' }, { c: 'b' }, {}], (x) => x.c), [
    ['b', 2],
    ['a', 1],
    ['unknown', 1],
  ])
})

test('metricSpreads and perPrRecall summarize across rounds', () => {
  fixture(({ run }) => {
    const loaded = loadRun(run)
    const m = metricSpreads(loaded)
    assert.ok(Math.abs(m.headline.grounded_recall.mean - 0.7) < 1e-9)
    assert.equal(m.headline.grounded_recall.min, 0.6)
    assert.equal(m.bySeverity.high.golden, 1)
    assert.equal(m.byCategory.correctness.recall.max, 0.8)
    assert.ok(Math.abs(perPrRecall(loaded)[KEY].mean - 0.7) < 1e-9)
  })
})

test('misses lists unreliably caught golden true positives with nearby context', () => {
  fixture(({ run, goldenDir }) => {
    const loaded = loadRun(run)
    const golden = loadGolden(goldenDir, [KEY, OTHER])
    assert.deepEqual(Object.keys(golden), [KEY])
    const result = misses(loaded, golden, 5)
    assert.deepEqual(
      result.map((m) => [m.index, m.caughtIn, m.rounds]),
      [
        [0, 0, 2],
        [3, 1, 2],
      ],
    )
    const [offByOne, leak] = result
    assert.deepEqual(offByOne.droppedNearby.map((d) => [d.round, d.line]), [[1, 14]])
    assert.equal(offByOne.nearby.length, 0)
    assert.equal(offByOne.elsewhereInFile, 3)
    assert.deepEqual(leak.nearby.map((o) => o.round), [2])
    assert.equal(misses(loaded, golden, 1)[0].droppedNearby.length, 0)
  })
})

test('falsePositives keeps only false-positive statuses', () => {
  fixture(({ run }) => {
    assert.deepEqual(
      falsePositives(loadRun(run)).map((f) => [f.round, f.prKey, f.start_line, f.status]),
      [[1, KEY, 90, 'novel_fp']],
    )
  })
})

test('loadRun fails without judged rounds', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'reviewbench-report-'))
  try {
    assert.throws(() => loadRun(root), /No judged rounds/)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('renderReport covers metrics, misses, false positives, and a baseline comparison', () => {
  fixture(({ root, goldenDir, run }) => {
    const baselineDir = writeRun(root, 'base', [
      { round: 1, summary: summary(0.2, [[KEY, 0.2]]), details: [] },
      { round: 2, summary: summary(0.3, [[KEY, 0.3]]), details: [] },
    ])
    const report = renderReport({
      run: loadRun(run),
      golden: loadGolden(goldenDir, [KEY]),
      baseline: loadRun(baselineDir),
      window: 5,
    })
    assert.match(report, /\| Grounded recall \| 70\.0% \(60\.0%–80\.0%\) \|/)
    assert.match(report, /## Compared with base/)
    assert.match(report, /\| Grounded recall \| 25\.0% \(20\.0%–30\.0%\) \| 70\.0% \(60\.0%–80\.0%\) \| \+45\.0 pts \| beyond noise \|/)
    assert.match(report, /### Pull requests that gained recall/)
    assert.match(report, /1 never caught, 1 caught in only some rounds\. 1 had a critique-dropped finding/)
    assert.match(report, /#0 — high\/correctness, caught 0\/2/)
    assert.match(report, /\*\*Dropped by critique\*\* \(round 1, line 14\): dropped bound check/)
    assert.match(report, /## False positives\n\n1 finding\(s\) across 2 round\(s\)/)
    assert.doesNotMatch(report, /\n{3,}/)
  })
})

test('writeReport names the comparison report after the baseline', () => {
  fixture(({ root, goldenDir, run }) => {
    const out = writeReport({ run, baseline: run, golden: goldenDir, window: 10, out: '' }, root)
    assert.equal(out, path.join(run, 'report-vs-run.md'))
    assert.ok(fs.readFileSync(out, 'utf8').startsWith('# ReviewBench report: run'))
    assert.throws(() => writeReport({ run, golden: path.join(root, 'missing') }, root), /No golden findings/)
  })
})
