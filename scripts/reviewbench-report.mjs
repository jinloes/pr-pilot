#!/usr/bin/env node
// Turns ReviewBench scoring into something to act on: metric spread across rounds, the golden
// findings PR Pilot missed (with what it said nearby and what the critique dropped), its false
// positives, and, with --baseline, how a change moved each metric and each pull request.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const DEFAULT_WINDOW = 10
const SEVERITY_ORDER = ['critical', 'high', 'medium', 'low']
const FALSE_POSITIVE = new Set(['matched_fp', 'novel_fp'])
const HEADLINE = [
  ['Grounded precision', 'grounded_precision'],
  ['Grounded recall', 'grounded_recall'],
  ['Augmented precision', 'augmented_precision'],
  ['Augmented recall', 'augmented_recall'],
]

const USAGE = `Usage: node scripts/reviewbench-report.mjs --run DIR [--baseline DIR] [options]

Summarizes a judged ReviewBench run into DIR/report.md (or DIR/report-vs-<baseline>.md).

  --run DIR        Judged run directory (contains scoring/round-N.json)
  --baseline DIR   Earlier run to compare against
  --golden DIR     Golden findings (default build/reviewbench/ReviewBench/golden)
  --window N       Lines around a missed finding that count as nearby (default ${DEFAULT_WINDOW})
  --out FILE       Report path
  --help           Show this help`

export function parseArgs(argv) {
  const opts = { run: '', baseline: '', golden: '', window: DEFAULT_WINDOW, out: '', help: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--help' || arg === '-h') return { ...opts, help: true }
    const value = () => {
      const next = argv[++i]
      if (next === undefined || next === '') throw new Error(`${arg} needs a value.`)
      return next
    }
    switch (arg) {
      case '--run':
        opts.run = value()
        break
      case '--baseline':
        opts.baseline = value()
        break
      case '--golden':
        opts.golden = value()
        break
      case '--out':
        opts.out = value()
        break
      case '--window': {
        const raw = value()
        opts.window = Number(raw)
        if (!Number.isInteger(opts.window) || opts.window < 0) {
          throw new Error('--window must be a non-negative integer.')
        }
        break
      }
      default:
        throw new Error(`Unknown option: ${arg}`)
    }
  }
  if (!opts.run) throw new Error('--run is required.')
  return opts
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function roundNumbers(dir, suffix) {
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir)
    .map((name) => new RegExp(`^round-(\\d+)${suffix.replace('.', '\\.')}$`).exec(name))
    .filter(Boolean)
    .map((match) => Number(match[1]))
    .sort((a, b) => a - b)
}

/** Reads every judged round of a run, with its per-PR diagnostics when the runner wrote them. */
export function loadRun(runDir) {
  const scoring = path.join(runDir, 'scoring')
  const rounds = roundNumbers(scoring, '.json').map((round) => {
    const details = path.join(scoring, `round-${round}.details.json`)
    const diagnosticsDir = path.join(runDir, 'diagnostics', `round-${round}`)
    const diagnostics = {}
    if (fs.existsSync(diagnosticsDir)) {
      for (const name of fs.readdirSync(diagnosticsDir).filter((n) => n.endsWith('.json'))) {
        diagnostics[name.slice(0, -'.json'.length)] = readJson(path.join(diagnosticsDir, name))
      }
    }
    return {
      round,
      summary: readJson(path.join(scoring, `round-${round}.json`)),
      details: fs.existsSync(details) ? readJson(details) : [],
      diagnostics,
    }
  })
  if (rounds.length === 0) throw new Error(`No judged rounds in ${scoring}.`)
  return { dir: runDir, rounds }
}

export function loadGolden(goldenDir, keys) {
  const golden = {}
  for (const key of keys) {
    const file = path.join(goldenDir, `${key}.json`)
    if (fs.existsSync(file)) golden[key] = readJson(file).findings ?? []
  }
  return golden
}

/** Mean, minimum, and maximum of the non-null values, or null when there are none. */
export function spread(values) {
  const present = values.filter((v) => typeof v === 'number' && Number.isFinite(v))
  if (present.length === 0) return null
  return {
    mean: present.reduce((sum, v) => sum + v, 0) / present.length,
    min: Math.min(...present),
    max: Math.max(...present),
    n: present.length,
  }
}

export function severityRank(severity) {
  const index = SEVERITY_ORDER.indexOf(String(severity).toLowerCase())
  return index === -1 ? SEVERITY_ORDER.length : index
}

/** Headline metrics and per-severity/category recall, each as a spread across rounds. */
export function metricSpreads(run, average = 'micro') {
  const pick = (fn) => spread(run.rounds.map((r) => fn(r.summary[average] ?? {})))
  const headline = Object.fromEntries(HEADLINE.map(([, key]) => [key, pick((m) => m.overall?.[key])]))
  const grouped = (group) => {
    const names = new Set(run.rounds.flatMap((r) => Object.keys(r.summary[average]?.[group] ?? {})))
    return Object.fromEntries(
      [...names].map((name) => [
        name,
        {
          recall: pick((m) => m[group]?.[name]?.grounded_recall),
          golden: run.rounds[0].summary[average]?.[group]?.[name]?.golden_tp_count ?? 0,
        },
      ]),
    )
  }
  return { headline, bySeverity: grouped('by_severity'), byCategory: grouped('by_category') }
}

/** Grounded recall of each pull request as a spread across rounds. */
export function perPrRecall(run) {
  const values = {}
  for (const { summary } of run.rounds) {
    for (const pr of summary.per_pr ?? []) {
      ;(values[pr.pr_key] ??= []).push(pr.metrics?.overall?.grounded_recall)
    }
  }
  return Object.fromEntries(Object.entries(values).map(([key, v]) => [key, spread(v)]))
}

function near(finding, golden, window) {
  return (
    finding.file === golden.file &&
    finding.line >= (golden.start_line ?? 0) - window &&
    finding.line <= (golden.end_line ?? golden.start_line ?? 0) + window
  )
}

/**
 * Every golden true positive some round missed, with how many rounds caught it, and PR Pilot's
 * submitted and critique-dropped findings near it.
 */
export function misses(run, golden, window = DEFAULT_WINDOW) {
  const result = []
  for (const [key, findings] of Object.entries(golden)) {
    const judged = run.rounds.filter((r) => r.details.some((d) => d.pr_key === key))
    if (judged.length === 0) continue
    const caught = new Map()
    const ours = []
    const dropped = []
    for (const r of judged) {
      const candidates = r.details.find((d) => d.pr_key === key).candidate_findings ?? []
      for (const c of candidates) {
        for (const gi of c.covered_golden_indices ?? []) {
          caught.set(gi, (caught.get(gi) ?? new Set()).add(r.round))
        }
        ours.push({
          round: r.round,
          file: c.file,
          line: c.start_line,
          status: c.status,
          message: c.message,
        })
      }
      for (const d of r.diagnostics[key]?.dropped ?? []) dropped.push({ round: r.round, ...d })
    }
    findings.forEach((g, index) => {
      if (g.tp_fp !== 'tp') return
      const caughtIn = caught.get(index)?.size ?? 0
      if (caughtIn === judged.length) return
      result.push({
        prKey: key,
        index,
        golden: g,
        caughtIn,
        rounds: judged.length,
        nearby: ours.filter((o) => near(o, g, window)),
        elsewhereInFile: ours.filter((o) => o.file === g.file && !near(o, g, window)).length,
        droppedNearby: dropped.filter((d) => near(d, g, window)),
      })
    })
  }
  return result.sort(
    (a, b) =>
      a.caughtIn - b.caughtIn ||
      severityRank(a.golden.severity) - severityRank(b.golden.severity) ||
      a.prKey.localeCompare(b.prKey) ||
      a.index - b.index,
  )
}

/** PR Pilot findings the judge scored as false positives, across all rounds. */
export function falsePositives(run) {
  return run.rounds.flatMap((r) =>
    r.details.flatMap((d) =>
      (d.candidate_findings ?? [])
        .filter((c) => FALSE_POSITIVE.has(c.status))
        .map((c) => ({ round: r.round, prKey: d.pr_key, ...c })),
    ),
  )
}

export function countBy(items, keyOf) {
  const counts = {}
  for (const item of items) {
    const key = keyOf(item) ?? 'unknown'
    counts[key] = (counts[key] ?? 0) + 1
  }
  return Object.entries(counts).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
}

/**
 * A change is only "beyond noise" when the two runs' round ranges do not overlap; with a single
 * round per run there is no range, so nothing can be called beyond noise.
 */
export function compareSpread(base, current) {
  if (!base || !current) return { delta: null, verdict: '' }
  const delta = current.mean - base.mean
  if (base.n < 2 || current.n < 2) return { delta, verdict: 'unknown (one round)' }
  const separated = current.min > base.max || current.max < base.min
  return { delta, verdict: separated ? 'beyond noise' : 'within noise' }
}

const pct = (v) => (v === null || v === undefined ? '—' : `${(v * 100).toFixed(1)}%`)
const pts = (v) => (v === null ? '—' : `${v >= 0 ? '+' : ''}${(v * 100).toFixed(1)} pts`)
const range = (s) => (!s ? '—' : s.n > 1 ? `${pct(s.mean)} (${pct(s.min)}–${pct(s.max)})` : pct(s.mean))
const clip = (text, max = 280) => {
  const flat = String(text ?? '').replace(/\s+/g, ' ').trim()
  return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat
}
const cell = (text) => String(text).replaceAll('|', '\\|')

function table(header, rows) {
  return [
    `| ${header.join(' | ')} |`,
    `| ${header.map(() => '---').join(' | ')} |`,
    ...rows.map((row) => `| ${row.map(cell).join(' | ')} |`),
  ].join('\n')
}

function groupTable(title, column, groups, sortKeys) {
  const names = Object.keys(groups).sort(sortKeys)
  if (names.length === 0) return ''
  return `### ${title}\n\n${table(
    [column, 'Golden TPs', 'Grounded recall'],
    names.map((n) => [n, groups[n].golden, range(groups[n].recall)]),
  )}\n`
}

export function renderReport({ run, golden, baseline, window = DEFAULT_WINDOW }) {
  const rounds = run.rounds.length
  const metrics = metricSpreads(run)
  const missed = misses(run, golden, window)
  const fps = falsePositives(run)
  const out = []
  out.push(`# ReviewBench report: ${path.basename(run.dir)}`, '')
  out.push(
    `Pull requests: ${run.rounds[0].summary.pr_count ?? '?'}. Rounds: ${rounds}. Metrics are` +
      ' micro-averaged (pooled counts); ranges show the minimum and maximum across rounds.',
    '',
  )
  out.push('## Metrics', '')
  out.push(table(['Metric', 'Value'], HEADLINE.map(([label, key]) => [label, range(metrics.headline[key])])), '')
  out.push(groupTable('Recall by severity', 'Severity', metrics.bySeverity, (a, b) => severityRank(a) - severityRank(b)))
  out.push(groupTable('Recall by category', 'Category', metrics.byCategory, (a, b) => a.localeCompare(b)))

  if (baseline) out.push(...renderComparison(run, baseline))

  out.push('## Missed golden findings', '')
  if (missed.length === 0) {
    out.push('Every golden true positive was caught in every round.', '')
  } else {
    const never = missed.filter((m) => m.caughtIn === 0)
    const droppedCount = missed.filter((m) => m.droppedNearby.length > 0).length
    out.push(
      `${never.length} never caught, ${missed.length - never.length} caught in only some rounds.` +
        ` ${droppedCount} had a critique-dropped finding within ${window} lines, so validation` +
        ' rather than detection may have lost them.',
      '',
    )
    out.push(
      table(
        ['Severity', 'Count'],
        countBy(missed, (m) => m.golden.severity).sort((a, b) => severityRank(a[0]) - severityRank(b[0])),
      ),
      '',
    )
    out.push(table(['Category', 'Count'], countBy(missed, (m) => m.golden.category)), '')
    for (const m of missed) {
      const g = m.golden
      const lines = g.end_line && g.end_line !== g.start_line ? `${g.start_line}-${g.end_line}` : g.start_line
      out.push(
        `### ${m.prKey} #${m.index} — ${g.severity}/${g.category}, caught ${m.caughtIn}/${m.rounds}`,
        '',
        `\`${g.file}:${lines}\`${g.difficulty ? ` · difficulty ${g.difficulty}` : ''}${
          g.context_required ? ` · needs ${g.context_required}` : ''
        }`,
        '',
        `> ${clip(g.message, 600)}`,
        '',
      )
      for (const d of m.droppedNearby) {
        out.push(`- **Dropped by critique** (round ${d.round}, line ${d.line}): ${clip(d.message)}`)
      }
      for (const o of m.nearby) {
        out.push(`- Ours, ${o.status} (round ${o.round}, line ${o.line}): ${clip(o.message)}`)
      }
      if (m.droppedNearby.length === 0 && m.nearby.length === 0) {
        out.push(
          m.elsewhereInFile > 0
            ? `- Nothing nearby; ${m.elsewhereInFile} other finding(s) elsewhere in this file.`
            : '- Nothing from PR Pilot in this file.',
        )
      }
      out.push('')
    }
  }

  out.push('## False positives', '')
  if (fps.length === 0) {
    out.push('No findings were judged false positives.', '')
  } else {
    out.push(`${fps.length} finding(s) across ${rounds} round(s) were judged false positives.`, '')
    out.push(table(['Category', 'Count'], countBy(fps, (f) => f.result_category)), '')
    for (const f of fps) {
      out.push(
        `- \`${f.prKey}\` round ${f.round}, \`${f.file}:${f.start_line}\`, ${f.status}` +
          `${f.result_category ? ` (${f.result_category})` : ''}: ${clip(f.message)}`,
      )
    }
    out.push('')
  }
  return `${out.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd()}\n`
}

function renderComparison(run, baseline) {
  const now = metricSpreads(run)
  const before = metricSpreads(baseline)
  const out = [`## Compared with ${path.basename(baseline.dir)}`, '']
  const rows = HEADLINE.map(([label, key]) => {
    const c = compareSpread(before.headline[key], now.headline[key])
    return [label, range(before.headline[key]), range(now.headline[key]), pts(c.delta), c.verdict]
  })
  for (const [name, group] of Object.entries(now.bySeverity).sort((a, b) => severityRank(a[0]) - severityRank(b[0]))) {
    const prior = before.bySeverity[name]?.recall
    const c = compareSpread(prior, group.recall)
    rows.push([`Recall, ${name}`, range(prior), range(group.recall), pts(c.delta), c.verdict])
  }
  out.push(table(['Metric', 'Baseline', 'This run', 'Change', 'Signal'], rows), '')
  if (run.rounds.length < 2 || baseline.rounds.length < 2) {
    out.push('Use --rounds 3 or more on both runs before trusting a difference.', '')
  }
  const beforePr = perPrRecall(baseline)
  const changes = Object.entries(perPrRecall(run))
    .filter(([key, s]) => beforePr[key] && s)
    .map(([key, s]) => ({ key, delta: s.mean - beforePr[key].mean, before: beforePr[key], now: s }))
    .filter((c) => Math.abs(c.delta) > 1e-9)
    .sort((a, b) => a.delta - b.delta)
  const section = (title, items) => {
    if (items.length === 0) return
    out.push(
      `### ${title}`,
      '',
      table(
        ['Pull request', 'Baseline recall', 'This run', 'Change'],
        items.map((c) => [c.key, range(c.before), range(c.now), pts(c.delta)]),
      ),
      '',
    )
  }
  section('Pull requests that lost recall', changes.filter((c) => c.delta < 0).slice(0, 15))
  section(
    'Pull requests that gained recall',
    changes
      .filter((c) => c.delta > 0)
      .reverse()
      .slice(0, 15),
  )
  return out
}

/** Writes the report and returns its path. */
export function writeReport(opts, repoRoot = process.cwd()) {
  const run = loadRun(path.resolve(repoRoot, opts.run))
  const baseline = opts.baseline ? loadRun(path.resolve(repoRoot, opts.baseline)) : null
  const goldenDir = path.resolve(repoRoot, opts.golden || path.join('build', 'reviewbench', 'ReviewBench', 'golden'))
  if (!fs.existsSync(goldenDir)) throw new Error(`No golden findings at ${goldenDir}.`)
  const keys = new Set(run.rounds.flatMap((r) => r.details.map((d) => d.pr_key)))
  for (const r of run.rounds) for (const pr of r.summary.per_pr ?? []) keys.add(pr.pr_key)
  const report = renderReport({ run, golden: loadGolden(goldenDir, keys), baseline, window: opts.window })
  const out = opts.out
    ? path.resolve(repoRoot, opts.out)
    : path.join(run.dir, baseline ? `report-vs-${path.basename(baseline.dir)}.md` : 'report.md')
  fs.mkdirSync(path.dirname(out), { recursive: true })
  fs.writeFileSync(out, report)
  return out
}

export function main(argv) {
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
  try {
    console.log(`Report: ${writeReport(opts)}`)
    return 0
  } catch (failure) {
    console.error(failure.message)
    return 1
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)))
}
