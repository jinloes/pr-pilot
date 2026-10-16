// Consistency checks for the agent-facing docs: every AGENTS.md, ARCHITECTURE.md, CODEMAP.md, and
// the docs/architecture and docs/codemap topic files they index. scripts/verify.mjs runs these
// in-process whenever one of those files changes.
import fs from 'node:fs'
import path from 'node:path'

// Agents read larger files in several pieces, so each doc should stay a single read.
export const AGENT_DOC_MAX_BYTES = 20_000

// Each topic directory must be reachable from its root doc.
const TOPIC_ROOTS = { 'docs/architecture': 'ARCHITECTURE.md', 'docs/codemap': 'CODEMAP.md' }
const DECISION_INDEX_HEADING = 'Key design decisions'
const SKIP_DIRS = new Set(['node_modules', 'build', 'dist', 'out', 'coverage'])
const MAX_DOC_DEPTH = 3
const LINK = /\]\(\s*<?([^)\s>]+)>?(?:\s+(?:"[^"]*"|'[^']*'))?\s*\)/g

function normalize(file) {
  return file.replaceAll('\\', '/').replace(/^\.\//, '')
}

function topicDirOf(file) {
  return Object.keys(TOPIC_ROOTS).find((dir) => {
    const rest = file.startsWith(`${dir}/`) ? file.slice(dir.length + 1) : ''
    return rest.endsWith('.md') && !rest.includes('/')
  })
}

export function isAgentDoc(file) {
  const normalized = normalize(file)
  return (
    normalized === 'AGENTS.md' ||
    normalized.endsWith('/AGENTS.md') ||
    Object.values(TOPIC_ROOTS).includes(normalized) ||
    topicDirOf(normalized) !== undefined
  )
}

/** GitHub's heading anchor: lowercase, punctuation dropped, each space turned into a hyphen. */
export function githubSlug(heading) {
  return heading
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N}\p{Pc} -]/gu, '')
    .replaceAll(' ', '-')
}

function blankFencedCode(lines) {
  let fence = null
  return lines.map((line) => {
    const marker = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1]
    if (fence) {
      if (marker && marker[0] === fence[0] && marker.length >= fence.length) fence = null
      return ''
    }
    if (marker) fence = marker
    return marker ? '' : line
  })
}

function headingsOf(lines) {
  const seen = new Map()
  const headings = []
  for (const [index, line] of lines.entries()) {
    const match = /^ {0,3}(#{1,6})\s+(.*?)(?:\s+#+)?\s*$/.exec(line)
    if (!match) continue
    const base = githubSlug(match[2])
    const count = seen.get(base) ?? 0
    seen.set(base, count + 1)
    const slug = count === 0 ? base : `${base}-${count}`
    headings.push({ level: match[1].length, text: match[2], slug, line: index + 1 })
  }
  return headings
}

function linksOf(lines) {
  const text = lines.map((line) => line.replace(/`[^`]*`/g, (span) => ' '.repeat(span.length)))
  const joined = text.join('\n')
  return [...joined.matchAll(LINK)].map((match) => ({
    url: match[1],
    line: joined.slice(0, match.index).split('\n').length,
  }))
}

export function parseMarkdown(text) {
  const lines = blankFencedCode(text.split(/\r?\n/))
  const ids = [...text.matchAll(/\s(?:id|name)="([^"]+)"/g)].map((match) => match[1].toLowerCase())
  return { headings: headingsOf(lines), links: linksOf(lines), ids }
}

function decode(value) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

/** Resolves a link to a repository-relative target, or returns null for an external URL. */
export function resolveLink(fromFile, url) {
  if (/^[a-z][a-z\d+.-]*:/i.test(url) || url.startsWith('//')) return null
  const hash = url.indexOf('#')
  const rawPath = decode(hash === -1 ? url : url.slice(0, hash))
  const anchor = hash === -1 ? '' : decode(url.slice(hash + 1)).toLowerCase()
  let target = fromFile
  if (rawPath.startsWith('/')) target = path.posix.normalize(rawPath.slice(1))
  else if (rawPath !== '') {
    target = path.posix.normalize(path.posix.join(path.posix.dirname(fromFile), rawPath))
  }
  return { target, anchor }
}

function listAgentDocs(repoRoot) {
  const found = []
  const walk = (relativeDir, depth) => {
    const entries = fs.readdirSync(path.join(repoRoot, relativeDir), { withFileTypes: true })
    for (const entry of entries) {
      const relative = relativeDir ? `${relativeDir}/${entry.name}` : entry.name
      if (entry.isDirectory()) {
        const skip = entry.name.startsWith('.') || SKIP_DIRS.has(entry.name)
        if (!skip && depth < MAX_DOC_DEPTH) walk(relative, depth + 1)
      } else if (entry.isFile() && isAgentDoc(relative)) {
        found.push(relative)
      }
    }
  }
  walk('', 0)
  return found.sort()
}

function linkProblem(repoRoot, link, resolved, parse) {
  const { target, anchor } = resolved
  if (target === '..' || target.startsWith('../')) return `link ${link.url} leaves the repository`
  if (!fs.existsSync(path.join(repoRoot, target))) return `broken link ${link.url} (no ${target})`
  if (!anchor || !target.endsWith('.md')) return null
  const doc = parse(target)
  const anchors = new Set([...doc.headings.map((heading) => heading.slug), ...doc.ids])
  return anchors.has(anchor) ? null : `broken anchor ${link.url} (no heading #${anchor} in ${target})`
}

function sectionLinks(doc, headingText) {
  const start = doc.headings.find((heading) => heading.level === 2 && heading.text === headingText)
  if (!start) return []
  const end = doc.headings.find((heading) => heading.level <= 2 && heading.line > start.line)
  return doc.links.filter((link) => link.line > start.line && (!end || link.line < end.line))
}

function checkDecisionIndex(docs, errors) {
  const architecture = docs.get('ARCHITECTURE.md')
  if (!architecture) return
  for (const heading of architecture.headings.filter((h) => h.level === 3)) {
    errors.push(
      `ARCHITECTURE.md:${heading.line}: move "### ${heading.text}" into a docs/architecture/*.md ` +
        `topic file and index it under "${DECISION_INDEX_HEADING}"`,
    )
  }
  const counts = new Map()
  for (const link of sectionLinks(architecture, DECISION_INDEX_HEADING)) {
    const resolved = resolveLink('ARCHITECTURE.md', link.url)
    if (!resolved?.anchor) continue
    const key = `${resolved.target}#${resolved.anchor}`
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  for (const [file, doc] of docs) {
    if (topicDirOf(file) !== 'docs/architecture') continue
    for (const heading of doc.headings.filter((h) => h.level === 3)) {
      const count = counts.get(`${file}#${heading.slug}`) ?? 0
      if (count === 1) continue
      const problem = count === 0 ? 'has no index line' : `has ${count} index lines`
      errors.push(
        `${file}:${heading.line}: "### ${heading.text}" ${problem} under ARCHITECTURE.md ` +
          `"${DECISION_INDEX_HEADING}"`,
      )
    }
  }
}

function checkTopicFilesLinked(docs, errors) {
  for (const [dir, rootFile] of Object.entries(TOPIC_ROOTS)) {
    const root = docs.get(rootFile)
    const linked = new Set(
      (root?.links ?? []).map((link) => resolveLink(rootFile, link.url)?.target).filter(Boolean),
    )
    for (const file of docs.keys()) {
      if (topicDirOf(file) === dir && !linked.has(file)) {
        errors.push(`${file}: not linked from ${rootFile}, so agents will not find it`)
      }
    }
  }
}

/**
 * Checks every agent doc under `repoRoot`. Errors are broken links or anchors and gaps in the
 * ARCHITECTURE.md decision index or the topic-file links; warnings are docs over the size limit.
 */
export function checkAgentDocs(repoRoot) {
  const errors = []
  const warnings = []
  const cache = new Map()
  const parse = (file) => {
    if (!cache.has(file)) {
      cache.set(file, parseMarkdown(fs.readFileSync(path.join(repoRoot, file), 'utf8')))
    }
    return cache.get(file)
  }
  const docs = new Map(listAgentDocs(repoRoot).map((file) => [file, parse(file)]))
  for (const [file, doc] of docs) {
    const size = fs.statSync(path.join(repoRoot, file)).size
    if (size > AGENT_DOC_MAX_BYTES) {
      warnings.push(
        `${file} is ${size} bytes; split it so agents can read it in one pass ` +
          `(limit ${AGENT_DOC_MAX_BYTES}).`,
      )
    }
    for (const link of doc.links) {
      const resolved = resolveLink(file, link.url)
      const problem = resolved && linkProblem(repoRoot, link, resolved, parse)
      if (problem) errors.push(`${file}:${link.line}: ${problem}`)
    }
  }
  checkDecisionIndex(docs, errors)
  checkTopicFilesLinked(docs, errors)
  return { errors, warnings }
}
