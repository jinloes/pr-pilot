import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Fragment, startTransition, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { isDelete, isInsert } from 'react-diff-view'
import type { ChangeData, FileData, HunkData } from 'react-diff-view'
import {
  Check,
  ChevronDown,
  ChevronUp,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  ShieldCheck,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react'
import hljs from 'highlight.js/lib/core'
import hljsBash from 'highlight.js/lib/languages/bash'
import hljsCss from 'highlight.js/lib/languages/css'
import hljsGo from 'highlight.js/lib/languages/go'
import hljsJava from 'highlight.js/lib/languages/java'
import hljsJson from 'highlight.js/lib/languages/json'
import hljsJs from 'highlight.js/lib/languages/javascript'
import hljsKotlin from 'highlight.js/lib/languages/kotlin'
import hljsProto from 'highlight.js/lib/languages/protobuf'
import hljsPython from 'highlight.js/lib/languages/python'
import hljsRust from 'highlight.js/lib/languages/rust'
import hljsSql from 'highlight.js/lib/languages/sql'
import hljsTs from 'highlight.js/lib/languages/typescript'
import hljsXml from 'highlight.js/lib/languages/xml'
import hljsYaml from 'highlight.js/lib/languages/yaml'
import { scrollBehavior } from '@/lib/motion'
import { useI18n } from '@/i18n/I18nProvider'

hljs.registerLanguage('bash', hljsBash)
hljs.registerLanguage('css', hljsCss)
hljs.registerLanguage('go', hljsGo)
hljs.registerLanguage('java', hljsJava)
hljs.registerLanguage('json', hljsJson)
hljs.registerLanguage('javascript', hljsJs)
hljs.registerLanguage('kotlin', hljsKotlin)
hljs.registerLanguage('protobuf', hljsProto)
hljs.registerLanguage('python', hljsPython)
hljs.registerLanguage('rust', hljsRust)
hljs.registerLanguage('sql', hljsSql)
hljs.registerLanguage('typescript', hljsTs)
hljs.registerLanguage('xml', hljsXml)
hljs.registerLanguage('yaml', hljsYaml)

const EXT_LANG: Record<string, string> = {
  bash: 'bash', sh: 'bash', zsh: 'bash',
  css: 'css', scss: 'css', less: 'css',
  go: 'go',
  html: 'xml', htm: 'xml', svg: 'xml', xml: 'xml',
  java: 'java',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  json: 'json',
  kt: 'kotlin', kts: 'kotlin',
  proto: 'protobuf',
  py: 'python',
  rs: 'rust',
  sql: 'sql',
  ts: 'typescript', tsx: 'typescript',
  yaml: 'yaml', yml: 'yaml',
}

function syntaxHighlight(code: string, filePath: string): string {
  const ext = filePath.split('.').pop()?.toLowerCase() ?? ''
  const lang = EXT_LANG[ext]
  if (!lang) return escapeHtml(code)
  return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { LineComment } from '@/bridge/types'
import { AUTOSAVE_DEBOUNCE_MS } from '@/lib/autosave'
import { cn } from '@/lib/utils'
import { parseDiffSafely } from '@/lib/diffParse'
import {
  buildDiffFileNavItems,
  buildDiffFileTree,
  displayPathForFile,
  findActiveFileIndex,
  middleTruncateFileName,
  type DiffFileTreeNode,
} from './fileNavigation'
import { buildFindingNavItems, findingLabel } from './findingNavigation'
import './DiffViewer.css'

const MAX_CHANGES = 500

/** Deletions persist through draft autosave, so the copy follows its debounce. */
export const DELETE_COMMENT_DESCRIPTION = `This comment will be removed from your pending GitHub review at the next `
  + `autosave (within ${AUTOSAVE_DEBOUNCE_MS / 1000} seconds). Use Save now to update GitHub immediately.`

interface Props {
  diff: string
  comments: LineComment[]
  orphanComments?: LineComment[]
  focusedCommentIdx?: number
  commentFocusRequestId?: number
  onFocusComment?: (idx: number) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onAddComment?: (comment: LineComment) => void
  onVerifyComment?: (comment: LineComment) => void
  onSuggestFixComment?: (comment: LineComment) => void
  readOnly?: boolean
}

type IndexedComment = { comment: LineComment; globalIdx: number }
type LineCommentMap = Map<number, IndexedComment[]>
type FileCommentMap = Map<string, LineCommentMap>

function groupComments(comments: LineComment[]): FileCommentMap {
  const map: FileCommentMap = new Map()
  for (let i = 0; i < comments.length; i++) {
    const c = comments[i]
    if (!map.has(c.file)) map.set(c.file, new Map())
    const lineMap = map.get(c.file)!
    if (!lineMap.has(c.line)) lineMap.set(c.line, [])
    lineMap.get(c.line)!.push({ comment: c, globalIdx: i })
  }
  return map
}

function newLineOf(change: ChangeData): number | undefined {
  if (isInsert(change)) return change.lineNumber
  if (isDelete(change)) return undefined
  return change.newLineNumber
}

function oldLineOf(change: ChangeData): number | undefined {
  if (isDelete(change)) return change.lineNumber
  if (isInsert(change)) return undefined
  return change.oldLineNumber
}

function findByPathSuffix(map: FileCommentMap, path: string): LineCommentMap | undefined {
  for (const [key, val] of map) {
    if (path.endsWith(key) || key.endsWith(path)) return val
  }
  return undefined
}

interface PendingNew {
  file: string
  line: number
  rowId: string
}

export function DiffViewer({
  diff,
  comments,
  orphanComments = [],
  focusedCommentIdx,
  commentFocusRequestId,
  onFocusComment,
  onEditComment,
  onDeleteComment,
  onAddComment,
  onVerifyComment,
  onSuggestFixComment,
  readOnly = false,
}: Props) {
  const t = useI18n()
  const [pendingNew, setPendingNew] = useState<PendingNew | null>(null)
  const [showAll, setShowAll] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchCursor, setSearchCursor] = useState(0)
  const [matchCount, setMatchCount] = useState(0)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const toolbarRef = useRef<HTMLDivElement>(null)
  const sectionRefs = useRef<Map<string, HTMLElement>>(new Map())
  const scrollParentRef = useRef<HTMLElement | null>(null)
  const [activeFilePath, setActiveFilePath] = useState('')
  const [navigationView, setNavigationView] = useState<'files' | 'findings'>('files')
  const [pendingScrollPath, setPendingScrollPath] = useState<string | null>(null)
  const [pendingScrollCommentIdx, setPendingScrollCommentIdx] = useState<number | null>(null)
  const [rawDiffCopied, setRawDiffCopied] = useState(false)

  useEffect(() => {
    if (readOnly) setPendingNew(null)
  }, [readOnly])

  const openSearch = useCallback(() => {
    setSearchOpen(true)
    setTimeout(() => searchInputRef.current?.focus(), 0)
  }, [])

  const closeSearch = useCallback(() => {
    setSearchOpen(false)
    setSearchQuery('')
    setSearchCursor(0)
  }, [])

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault()
        openSearch()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [openSearch])

  useEffect(() => { setSearchCursor(0) }, [searchQuery])

  useEffect(() => {
    if (!containerRef.current) return
    const count = searchQuery
      ? containerRef.current.querySelectorAll('.diff-line--search-match').length
      : 0
    setMatchCount(count)
  }, [searchQuery, showAll, diff])

  useEffect(() => {
    if (!containerRef.current || !searchQuery || matchCount === 0) return
    const matches = Array.from(
      containerRef.current.querySelectorAll<HTMLElement>('.diff-line--search-match'),
    )
    const idx = Math.min(searchCursor, matches.length - 1)
    matches.forEach((el, i) => el.classList.toggle('diff-line--search-match--current', i === idx))
    matches[idx]?.scrollIntoView({ behavior: scrollBehavior(), block: 'nearest' })
  }, [searchCursor, searchQuery, matchCount])

  const parseResult = useMemo(() => parseDiffSafely(diff), [diff])
  const files: FileData[] = parseResult.files

  const totalChanges = files.reduce(
    (sum, f) => sum + f.hunks.reduce((s, h) => s + h.changes.length, 0),
    0,
  )
  const truncating = !showAll && totalChanges > MAX_CHANGES

  let remaining = MAX_CHANGES
  const visibleFiles: FileData[] = truncating
    ? files
        .map((file) => {
          if (remaining <= 0) return null
          const visibleHunks = file.hunks
            .map((hunk) => {
              if (remaining <= 0) return null
              const keep = hunk.changes.slice(0, remaining)
              remaining -= keep.length
              return keep.length > 0 ? { ...hunk, changes: keep } : null
            })
            .filter((h): h is HunkData => h !== null)
          return visibleHunks.length > 0 ? { ...file, hunks: visibleHunks } : null
        })
        .filter((f): f is FileData => f !== null)
    : files

  const byFile = groupComments(comments)
  const allNavItems = useMemo(() => buildDiffFileNavItems(files, comments), [files, comments])
  const visibleNavItems = useMemo(() => buildDiffFileNavItems(visibleFiles, comments), [visibleFiles, comments])
  const fileTree = useMemo(() => buildDiffFileTree(allNavItems), [allNavItems])
  const findings = useMemo(() => buildFindingNavItems(comments), [comments])
  const unanchoredFindings = useMemo(() => buildFindingNavItems(orphanComments), [orphanComments])

  const updateActiveFile = useCallback(() => {
    if (visibleNavItems.length === 0) return
    const rootTop = scrollParentRef.current?.getBoundingClientRect().top ?? 0
    const stickyOffset = (toolbarRef.current?.getBoundingClientRect().height ?? 0) + 12
    const sectionOffsets = visibleNavItems.map((item) => {
      const section = sectionRefs.current.get(item.displayPath)
      if (!section) return Number.POSITIVE_INFINITY
      return section.getBoundingClientRect().top - rootTop
    })
    const nextItem = visibleNavItems[findActiveFileIndex(sectionOffsets, stickyOffset)]
    if (nextItem) setActiveFilePath((current) => (current === nextItem.displayPath ? current : nextItem.displayPath))
  }, [visibleNavItems])

  const scrollToFile = useCallback((displayPath: string) => {
    const section = sectionRefs.current.get(displayPath)
    if (!section) return false
    section.scrollIntoView({ behavior: scrollBehavior(), block: 'start' })
    setActiveFilePath(displayPath)
    return true
  }, [])

  const handleSelectFile = useCallback((displayPath: string) => {
    const isVisible = visibleNavItems.some((item) => item.displayPath === displayPath)
    setActiveFilePath(displayPath)
    if (!isVisible && truncating) {
      setPendingScrollPath(displayPath)
      startTransition(() => setShowAll(true))
      return
    }
    setPendingScrollPath(null)
    scrollToFile(displayPath)
  }, [scrollToFile, truncating, visibleNavItems])

  const scrollToComment = useCallback((index: number) => {
    const target = document.getElementById(`diff-comment-${index}`)
    if (!target && truncating) {
      setPendingScrollCommentIdx(index)
      startTransition(() => setShowAll(true))
      return false
    }
    target?.scrollIntoView({ behavior: scrollBehavior(), block: 'center' })
    return Boolean(target)
  }, [truncating])

  const handleSelectFinding = useCallback((index: number, file: string) => {
    setActiveFilePath(file)
    onFocusComment?.(index)
    scrollToComment(index)
  }, [onFocusComment, scrollToComment])

  const handleSelectUnanchoredFinding = useCallback((index: number) => {
    document.getElementById(`orphan-comment-${index}`)
      ?.scrollIntoView({ behavior: scrollBehavior(), block: 'nearest' })
  }, [])

  useEffect(() => {
    setActiveFilePath((current) => {
      if (visibleNavItems.length === 0) return ''
      return visibleNavItems.some((item) => item.displayPath === current)
        ? current
        : visibleNavItems[0].displayPath
    })
  }, [visibleNavItems])

  useEffect(() => {
    if (!pendingScrollPath) return
    if (scrollToFile(pendingScrollPath)) setPendingScrollPath(null)
  }, [pendingScrollPath, scrollToFile, visibleFiles])

  useEffect(() => {
    if (pendingScrollCommentIdx === null) return
    if (scrollToComment(pendingScrollCommentIdx)) setPendingScrollCommentIdx(null)
  }, [pendingScrollCommentIdx, scrollToComment, visibleFiles])

  useEffect(() => {
    const scrollParent = findScrollParent(containerRef.current)
    scrollParentRef.current = scrollParent
    if (!scrollParent) return

    let frame = 0
    const scheduleUpdate = () => {
      cancelAnimationFrame(frame)
      frame = window.requestAnimationFrame(updateActiveFile)
    }

    scheduleUpdate()
    scrollParent.addEventListener('scroll', scheduleUpdate, { passive: true })
    window.addEventListener('resize', scheduleUpdate)
    return () => {
      cancelAnimationFrame(frame)
      scrollParent.removeEventListener('scroll', scheduleUpdate)
      window.removeEventListener('resize', scheduleUpdate)
    }
  }, [updateActiveFile])

  useEffect(() => {
    if (focusedCommentIdx === undefined) return
    scrollToComment(focusedCommentIdx)
  }, [commentFocusRequestId, focusedCommentIdx, scrollToComment])

  if (parseResult.status === 'empty') return null
  if (parseResult.status === 'unrenderable') {
    return (
      <div className="p-4">
        <Alert variant="destructive">
          <AlertTitle>PR Pilot could not render this diff</AlertTitle>
          <AlertDescription>
            The diff is non-empty but uses an unsupported or malformed format. Review the raw diff before submitting.
          </AlertDescription>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                void navigator.clipboard.writeText(diff).then(
                  () => setRawDiffCopied(true),
                  () => undefined,
                )
              }}
            >
              {rawDiffCopied ? 'Copied raw diff' : 'Copy raw diff'}
            </Button>
          </div>
          <details className="mt-3">
            <summary className="cursor-pointer text-sm font-medium">Show raw diff</summary>
            <pre className="mt-2 max-h-64 overflow-auto whitespace-pre rounded bg-muted p-3 text-xs text-foreground">{diff}</pre>
          </details>
        </Alert>
      </div>
    )
  }

  const activeFile = allNavItems.find((item) => item.displayPath === activeFilePath)
    ?? visibleNavItems.find((item) => item.displayPath === activeFilePath)
    ?? allNavItems[0]
  const activeFileIndex = activeFile ? allNavItems.findIndex((item) => item.displayPath === activeFile.displayPath) : 0

  return (
    <TooltipProvider delayDuration={400}>
      <div ref={containerRef} className="diff-viewer" tabIndex={-1}>
        <div className="diff-viewer__layout">
          <nav className="diff-file-nav" aria-label="Review navigation">
            <div className="diff-file-nav__tabs" role="group" aria-label="Review navigation views">
              <button
                type="button"
                aria-pressed={navigationView === 'files'}
                className={cn('diff-file-nav__tab', navigationView === 'files' && 'diff-file-nav__tab--active')}
                onClick={() => setNavigationView('files')}
              >
                Files
                <span className="diff-file-nav__count">{allNavItems.length}</span>
              </button>
              <button
                type="button"
                aria-pressed={navigationView === 'findings'}
                className={cn('diff-file-nav__tab', navigationView === 'findings' && 'diff-file-nav__tab--active')}
                onClick={() => setNavigationView('findings')}
              >
                Findings
                <span className="diff-file-nav__count">{findings.length + unanchoredFindings.length}</span>
              </button>
            </div>
            <div className="diff-file-nav__body">
              {navigationView === 'files' ? (
                <ul className="diff-file-tree">
                  {fileTree.map((node) => (
                    <FileTreeNodeView
                      key={node.key}
                      node={node}
                      activeFilePath={activeFile?.displayPath ?? ''}
                      onSelectFile={handleSelectFile}
                    />
                  ))}
                </ul>
              ) : (
                <div className="diff-findings">
                  {findings.length === 0 && unanchoredFindings.length === 0 && (
                    <p className="diff-findings__empty">No findings in this review.</p>
                  )}
                  {findings.length > 0 && (
                    <ol className="diff-findings__list" aria-label="Anchored findings">
                      {findings.map((finding) => (
                        <li key={finding.key}>
                          <button
                            type="button"
                            className={cn(
                              'diff-findings__item',
                              finding.index === focusedCommentIdx && 'diff-findings__item--active',
                            )}
                            aria-label={`${finding.label} in ${finding.comment.file}, line ${finding.comment.line}: ${finding.preview}`}
                            aria-current={finding.index === focusedCommentIdx ? 'location' : undefined}
                            onClick={() => handleSelectFinding(finding.index, finding.comment.file)}
                          >
                            <span className={cn('diff-findings__label', findingToneClass(finding.comment))}>
                              {finding.label}
                            </span>
                            <span className="diff-findings__location">
                              {finding.comment.file}:{finding.comment.line}
                            </span>
                            <span className="diff-findings__preview">{finding.preview}</span>
                          </button>
                        </li>
                      ))}
                    </ol>
                  )}
                  {unanchoredFindings.length > 0 && (
                    <div className="diff-findings__unanchored">
                      <p className="diff-findings__section-title">
                        Unanchored
                        <span className="diff-file-nav__count">{unanchoredFindings.length}</span>
                      </p>
                      <ol className="diff-findings__list" aria-label="Unanchored findings">
                        {unanchoredFindings.map((finding) => (
                          <li key={finding.key}>
                            <button
                              type="button"
                              className="diff-findings__item"
                              aria-label={`Unanchored ${finding.label} in ${finding.comment.file}, line ${finding.comment.line}: ${finding.preview}`}
                              onClick={() => handleSelectUnanchoredFinding(finding.index)}
                            >
                              <span className={cn('diff-findings__label', findingToneClass(finding.comment))}>
                                {finding.label}
                              </span>
                              <span className="diff-findings__location">
                                {finding.comment.file}:{finding.comment.line}
                              </span>
                              <span className="diff-findings__preview">{finding.preview}</span>
                            </button>
                          </li>
                        ))}
                      </ol>
                    </div>
                  )}
                </div>
              )}
            </div>
          </nav>

          <div className="diff-viewer__content">
            <div ref={toolbarRef} className="diff-viewer__toolbar">
              <div className="diff-viewer__current-file" data-testid="diff-current-file">
                <span className="diff-viewer__current-label">Viewing</span>
                <span
                  className="diff-viewer__current-path"
                  data-testid="diff-current-file-path"
                  title={activeFile?.displayPath ?? ''}
                >
                  {activeFile?.displayPath ?? '—'}
                </span>
                <span className="diff-viewer__current-count">
                  {allNavItems.length > 0 ? `${activeFileIndex + 1}/${allNavItems.length}` : '0/0'}
                </span>
              </div>

              {!searchOpen && (
                <Button variant="ghost" size="sm" onClick={openSearch} aria-label="Find in diff" className="gap-1.5">
                  <Search className="h-3.5 w-3.5" /> Find
                </Button>
              )}
              {searchOpen && (
                <div className="diff-search-bar">
                  <Search className="w-3 h-3 text-muted-foreground shrink-0" />
                  <input
                    ref={searchInputRef}
                    type="text"
                    className="diff-search-input"
                    placeholder="Find in diff…"
                    aria-label={t('diff.search')}
                    value={searchQuery}
                    onChange={(e) => {
                      setSearchQuery(e.target.value)
                      setSearchCursor(0)
                    }}
                    onKeyDown={(e) => {
                      if (e.key === 'Escape') { e.stopPropagation(); closeSearch() }
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        if (matchCount > 0) setSearchCursor((c) => e.shiftKey ? (c - 1 + matchCount) % matchCount : (c + 1) % matchCount)
                      }
                    }}
                  />
                  <span className="diff-search-count">
                    {searchQuery ? (matchCount === 0 ? 'No results' : `${Math.min(searchCursor + 1, matchCount)} / ${matchCount}`) : ''}
                  </span>
                  <Button variant="ghost" size="sm" className="h-6 w-6 p-0 text-muted-foreground" onClick={() => matchCount > 0 && setSearchCursor((c) => (c - 1 + matchCount) % matchCount)} disabled={matchCount === 0} aria-label="Previous match"><ChevronUp className="w-3 h-3" /></Button>
                  <Button variant="ghost" size="sm" className="h-6 w-6 p-0 text-muted-foreground" onClick={() => matchCount > 0 && setSearchCursor((c) => (c + 1) % matchCount)} disabled={matchCount === 0} aria-label="Next match"><ChevronDown className="w-3 h-3" /></Button>
                  <Button variant="ghost" size="sm" className="h-6 w-6 p-0 text-muted-foreground" onClick={closeSearch} aria-label="Close search"><X className="w-3 h-3" /></Button>
                </div>
              )}
            </div>

            {visibleFiles.map((file, fileIndex) => {
              const displayPath = displayPathForFile(file)
              const fileComments =
                byFile.get(file.newPath) ??
                byFile.get(file.oldPath) ??
                findByPathSuffix(byFile, file.newPath) ??
                new Map<number, IndexedComment[]>()
              return (
                <FileView
                  key={`${file.oldRevision}-${file.newRevision}-${file.newPath}`}
                  file={file}
                  fileIndex={fileIndex}
                  displayPath={displayPath}
                  comments={fileComments}
                  focusedCommentIdx={focusedCommentIdx}
                  searchQuery={searchQuery}
                  pendingNew={pendingNew?.file === displayPath ? pendingNew : undefined}
                  onSectionRef={(element) => {
                    if (element) sectionRefs.current.set(displayPath, element)
                    else sectionRefs.current.delete(displayPath)
                  }}
                  onLineClick={onAddComment && !readOnly
                    ? ({ line, rowId }) => setPendingNew({ file: displayPath, line, rowId })
                    : undefined}
                  onPendingCancel={() => setPendingNew(null)}
                  onPendingSave={(type, body) => {
                    if (pendingNew) onAddComment?.({ file: pendingNew.file, line: pendingNew.line, type, body })
                    setPendingNew(null)
                  }}
                  onEditComment={onEditComment}
                  onDeleteComment={onDeleteComment}
                  onVerifyComment={onVerifyComment}
                  onSuggestFixComment={onSuggestFixComment}
                  readOnly={readOnly}
                />
              )
            })}
            {truncating && (
              <div className="flex items-center justify-between px-4 py-2 border-t border-border bg-card text-xs text-muted-foreground font-mono">
                <span>Showing {MAX_CHANGES} of {totalChanges} changed lines</span>
                <Button variant="outline" size="sm" className="h-6 text-xs" onClick={() => startTransition(() => setShowAll(true))}>
                  Show full diff ↓
                </Button>
              </div>
            )}
          </div>
        </div>
      </div>
    </TooltipProvider>
  )
}

function findScrollParent(node: HTMLElement | null): HTMLElement | null {
  let current = node?.parentElement ?? null
  while (current) {
    const style = window.getComputedStyle(current)
    if (/(auto|scroll)/.test(style.overflowY)) return current
    current = current.parentElement
  }
  return null
}

function statusAbbreviation(status: string): string {
  switch (status) {
    case 'add':
      return 'A'
    case 'delete':
      return 'D'
    case 'rename':
      return 'R'
    case 'copy':
      return 'C'
    default:
      return 'M'
  }
}

function folderLabel(node: DiffFileTreeNode): string {
  const parts = node.name.split('/')
  if (parts.length <= 2) return node.name
  return `${parts[0]}/.../${parts[parts.length - 1]}`
}

function findingToneClass(comment: LineComment): string {
  if (comment.severity === 'blocker' || comment.severity === 'major' || comment.type === 'issue') {
    return 'text-status-issue'
  }
  if (comment.severity === 'minor' || comment.type === 'suggestion') return 'text-status-suggestion'
  return 'text-status-note'
}

function FileTreeNodeView({
  node,
  activeFilePath,
  onSelectFile,
}: {
  node: DiffFileTreeNode
  activeFilePath: string
  onSelectFile: (displayPath: string) => void
}) {
  if (node.file) {
    return (
      <li>
        <button
          type="button"
          className={cn('diff-file-tree__file', node.file.displayPath === activeFilePath && 'diff-file-tree__file--active')}
          onClick={() => onSelectFile(node.file!.displayPath)}
          aria-label={node.file.displayPath}
          aria-current={node.file.displayPath === activeFilePath ? 'location' : undefined}
          title={node.file.displayPath}
        >
          <span className={cn('diff-file-tree__status', `diff-file-tree__status--${node.file.status}`)} aria-hidden="true">
            {statusAbbreviation(node.file.status)}
          </span>
          <span className="diff-file-tree__label">{middleTruncateFileName(node.name)}</span>
          {node.file.commentCount > 0 && <span className="diff-file-tree__meta">{node.file.commentCount}</span>}
        </button>
      </li>
    )
  }

  return (
    <li>
      <div className="diff-file-tree__folder" title={node.name}>{folderLabel(node)}</div>
      <ul className="diff-file-tree__children">
        {node.children.map((child) => (
          <FileTreeNodeView
            key={child.key}
            node={child}
            activeFilePath={activeFilePath}
            onSelectFile={onSelectFile}
          />
        ))}
      </ul>
    </li>
  )
}

// ── File block ────────────────────────────────────────────────────────────────

interface FileViewProps {
  file: FileData
  fileIndex: number
  displayPath: string
  comments: LineCommentMap
  focusedCommentIdx?: number
  searchQuery?: string
  pendingNew?: PendingNew
  onSectionRef: (element: HTMLElement | null) => void
  onLineClick?: (target: { line: number; rowId: string }) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onVerifyComment?: (comment: LineComment) => void
  onSuggestFixComment?: (comment: LineComment) => void
  readOnly: boolean
}

function FileView({
  file,
  fileIndex,
  displayPath,
  comments,
  focusedCommentIdx,
  searchQuery,
  pendingNew,
  onSectionRef,
  onLineClick,
  onPendingCancel,
  onPendingSave,
  onEditComment,
  onDeleteComment,
  onVerifyComment,
  onSuggestFixComment,
  readOnly,
}: FileViewProps) {
  return (
    <section
      ref={onSectionRef}
      className="diff-file focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      data-testid={`diff-file-section-${fileIndex}`}
      data-file-path={displayPath}
      aria-labelledby={`diff-file-${CSS.escape(displayPath)}`}
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- A read-only diff has no gutter buttons, so its horizontal scroll needs a keyboard focus target.
      tabIndex={readOnly ? 0 : undefined}
    >
      <div className="diff-file__header">
        <h2 id={`diff-file-${CSS.escape(displayPath)}`} className="diff-file__path">{displayPath}</h2>
        {file.type !== 'modify' && (
          <span className={`diff-file__badge diff-file__badge--${file.type}`}>{file.type}</span>
        )}
      </div>
      <table className="diff-table">
        <caption className="sr-only">Changes in {displayPath}</caption>
        <thead className="sr-only"><tr><th scope="col">Old line</th><th scope="col">New line</th><th scope="col">Code</th></tr></thead>
        <tbody>
          {file.hunks.map((hunk) => (
            <HunkRows
              key={hunk.content}
              hunk={hunk}
              filePath={displayPath}
              comments={comments}
              focusedCommentIdx={focusedCommentIdx}
              searchQuery={searchQuery}
              pendingNew={pendingNew}
              onLineClick={onLineClick}
              onPendingCancel={onPendingCancel}
              onPendingSave={onPendingSave}
              onEditComment={onEditComment}
              onDeleteComment={onDeleteComment}
              onVerifyComment={onVerifyComment}
              onSuggestFixComment={onSuggestFixComment}
              readOnly={readOnly}
            />
          ))}
        </tbody>
      </table>
    </section>
  )
}

// ── Hunk rows ─────────────────────────────────────────────────────────────────

interface HunkRowsProps {
  hunk: HunkData
  filePath: string
  comments: LineCommentMap
  focusedCommentIdx?: number
  searchQuery?: string
  pendingNew?: PendingNew
  onLineClick?: (target: { line: number; rowId: string }) => void
  onPendingCancel: () => void
  onPendingSave: (type: LineComment['type'], body: string) => void
  onEditComment?: (idx: number, body: string) => void
  onDeleteComment?: (idx: number) => void
  onVerifyComment?: (comment: LineComment) => void
  onSuggestFixComment?: (comment: LineComment) => void
  readOnly: boolean
}

function HunkRows({
  hunk,
  filePath,
  comments,
  focusedCommentIdx,
  searchQuery,
  pendingNew,
  onLineClick,
  onPendingCancel,
  onPendingSave,
  onEditComment,
  onDeleteComment,
  onVerifyComment,
  onSuggestFixComment,
  readOnly,
}: HunkRowsProps) {
  const highlighted = useMemo(
    () => hunk.changes.map((c) => syntaxHighlight(c.content, filePath)),
    [hunk, filePath],
  )

  return (
    <Fragment>
      <tr className="diff-hunk-header">
        <td className="diff-gutter" colSpan={2} />
        <td className="diff-hunk-label">{hunk.content}</td>
      </tr>
      {hunk.changes.map((change, i) => {
        const newLine = newLineOf(change)
        const oldLine = oldLineOf(change)
        const lineComments = newLine !== undefined ? (comments.get(newLine) ?? []) : []
        const clickableLine = newLine ?? oldLine
        const rowId = `${change.type}:${oldLine ?? 'na'}:${newLine ?? 'na'}:${i}`
        const canAddOldLineComment = onLineClick && oldLine !== undefined && clickableLine !== undefined
        const canAddNewLineComment = onLineClick && newLine !== undefined && clickableLine !== undefined
        const handleAddComment = () => {
          if (onLineClick && clickableLine !== undefined) onLineClick({ line: clickableLine, rowId })
        }
        return (
          <Fragment key={i}>
            <tr className={cn(`diff-line diff-line--${change.type}`, searchQuery && change.content.toLowerCase().includes(searchQuery.toLowerCase()) && 'diff-line--search-match')}>
              <td
                className={cn('diff-gutter', canAddOldLineComment && 'diff-gutter--clickable')}
              >
                {canAddOldLineComment ? (
                  <button type="button" className="diff-gutter__button" onClick={handleAddComment} aria-label={`Add comment on ${filePath}, old line ${oldLine}`}>
                    {oldLine}
                  </button>
                ) : (oldLine ?? '')}
              </td>
              <td
                className={cn('diff-gutter', canAddNewLineComment && 'diff-gutter--clickable')}
              >
                {canAddNewLineComment ? (
                  <button type="button" className="diff-gutter__button" onClick={handleAddComment} aria-label={`Add comment on ${filePath}, new line ${newLine}`}>
                    {newLine}
                  </button>
                ) : (newLine ?? '')}
              </td>
              <td className="diff-code">
                <div className="diff-code__scroll">
                  <span className="diff-prefix">
                    {change.type === 'insert' ? '+' : change.type === 'delete' ? '-' : ' '}
                  </span>
                  <span dangerouslySetInnerHTML={{ __html: highlighted[i] }} />
                </div>
              </td>
            </tr>

            {lineComments.map(({ comment, globalIdx }) => (
              <InlineCommentRow
                key={`c-${globalIdx}`}
                comment={comment}
                globalIdx={globalIdx}
                focused={globalIdx === focusedCommentIdx}
                onEdit={onEditComment ? (body) => onEditComment(globalIdx, body) : undefined}
                onDelete={onDeleteComment ? () => onDeleteComment(globalIdx) : undefined}
                onVerify={onVerifyComment ? () => onVerifyComment(comment) : undefined}
                onSuggestFix={onSuggestFixComment ? () => onSuggestFixComment(comment) : undefined}
                readOnly={readOnly}
              />
            ))}

            {pendingNew?.rowId === rowId && (
              <NewCommentRow file={filePath} line={pendingNew.line} onSave={onPendingSave} onCancel={onPendingCancel} />
            )}
          </Fragment>
        )
      })}
    </Fragment>
  )
}

// ── Inline comment row ────────────────────────────────────────────────────────

const COMMENT_BADGE_CLASS: Record<LineComment['type'], string> = {
  issue:      'text-status-issue border-status-issue/50 bg-status-issue/10',
  suggestion: 'text-status-suggestion border-status-suggestion/50 bg-status-suggestion/10',
  note:       'text-status-note border-status-note/50 bg-status-note/10',
}

const SEVERITY_BADGE_CLASS: Record<NonNullable<LineComment['severity']>, string> = {
  blocker: 'text-status-issue border-status-issue/60 bg-status-issue/5',
  major:   'text-status-issue border-status-issue/40 bg-status-issue/10',
  minor:   'text-status-suggestion border-status-suggestion/40 bg-status-suggestion/10',
  nit:     'text-status-note border-status-note/40 bg-status-note/10',
}

interface InlineCommentRowProps {
  comment: LineComment
  globalIdx: number
  focused: boolean
  onEdit?: (body: string) => void
  onDelete?: () => void
  onVerify?: () => void
  onSuggestFix?: () => void
  readOnly: boolean
}

function InlineCommentRow({
  comment,
  globalIdx,
  focused,
  onEdit,
  onDelete,
  onVerify,
  onSuggestFix,
  readOnly,
}: InlineCommentRowProps) {
  const [editing, setEditing] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [draft, setDraft] = useState(comment.body)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!editing) setDraft(comment.body)
  }, [comment.body, editing])

  useEffect(() => {
    if (readOnly) {
      setEditing(false)
      setDeleteDialogOpen(false)
    }
  }, [readOnly])

  useEffect(() => {
    if (editing && textareaRef.current) {
      const el = textareaRef.current
      el.focus()
      el.style.height = 'auto'
      el.style.height = el.scrollHeight + 'px'
    }
  }, [editing])

  function handleSave() {
    const trimmed = draft.trim()
    if (trimmed && onEdit) onEdit(trimmed)
    setEditing(false)
  }

  return (
    <tr
      id={`diff-comment-${globalIdx}`}
      className={cn('diff-comment-row', focused && 'diff-comment-row--focused')}
    >
      <td colSpan={3} className={`diff-comment-cell diff-comment-cell--${comment.type}`}>
        <div className="diff-comment">
          <div className="diff-comment__header">
            <div className="diff-comment__identity">
              <Badge
                variant="outline"
                className={cn(
                  'text-[9px] font-bold tracking-wide uppercase px-1.5 py-0',
                  comment.severity ? SEVERITY_BADGE_CLASS[comment.severity] : COMMENT_BADGE_CLASS[comment.type],
                )}
              >
                {findingLabel(comment)}
              </Badge>
              {(comment.category || comment.confidence) && (
                <span className="diff-comment__metadata">
                  {comment.category}
                  {comment.category && comment.confidence && <span aria-hidden="true"> · </span>}
                  {comment.confidence && (
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="cursor-help">{comment.confidence} confidence</span>
                      </TooltipTrigger>
                      {comment.rationale && <TooltipContent side="top" className="max-w-xs">{comment.rationale}</TooltipContent>}
                    </Tooltip>
                  )}
                </span>
              )}
            </div>
            {(onVerify || onSuggestFix || onEdit || onDelete) && !editing && (
              <div className="diff-comment__actions">
                {onVerify && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="diff-comment__ai-action text-muted-foreground hover:text-amber-400"
                        onClick={onVerify}
                        aria-label="Verify with AI"
                        disabled={readOnly}
                      >
                        <ShieldCheck className="w-3.5 h-3.5" />
                        <span className="diff-comment__action-label">Verify</span>
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent side="top">Verify with AI</TooltipContent>
                  </Tooltip>
                )}
                {onSuggestFix && comment.type !== 'note' && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="diff-comment__ai-action text-muted-foreground hover:text-primary"
                        onClick={onSuggestFix}
                        aria-label="Suggest fix with AI"
                        disabled={readOnly}
                      >
                        <Sparkles className="w-3.5 h-3.5" />
                        <span className="diff-comment__action-label">Suggest fix</span>
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent side="top">Suggest fix with AI</TooltipContent>
                  </Tooltip>
                )}
                {(onEdit || onDelete) && (
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-6 w-6 p-0 text-muted-foreground hover:text-foreground"
                        aria-label="More finding actions"
                        disabled={readOnly}
                      >
                        <MoreHorizontal className="w-3.5 h-3.5" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      {onEdit && (
                        <DropdownMenuItem className="gap-2 text-xs" onSelect={() => setEditing(true)}>
                          <Pencil className="h-3.5 w-3.5" />
                          Edit comment
                        </DropdownMenuItem>
                      )}
                      {onEdit && onDelete && <DropdownMenuSeparator />}
                      {onDelete && (
                        <DropdownMenuItem
                          className="gap-2 text-xs text-status-issue focus:text-status-issue"
                          onSelect={() => setDeleteDialogOpen(true)}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                          Delete comment
                        </DropdownMenuItem>
                      )}
                    </DropdownMenuContent>
                  </DropdownMenu>
                )}
                <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Delete this comment?</AlertDialogTitle>
                      <AlertDialogDescription>{DELETE_COMMENT_DESCRIPTION}</AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancel</AlertDialogCancel>
                      <AlertDialogAction
                        onClick={onDelete}
                        className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                      >
                        Delete
                      </AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
            )}
          </div>
          {editing ? (
            <div className="flex flex-col gap-1.5">
              <textarea
                ref={textareaRef}
                className="diff-comment__textarea"
                value={draft}
                rows={2}
                aria-label={`Edit comment on ${comment.file}, line ${comment.line}`}
                onChange={(e) => {
                  setDraft(e.target.value)
                  e.target.style.height = 'auto'
                  e.target.style.height = e.target.scrollHeight + 'px'
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave()
                  if (e.key === 'Escape') setEditing(false)
                }}
              />
              <div className="flex gap-1.5">
                <Button size="sm" className="h-6 text-xs gap-1" onClick={handleSave}><Check className="w-3 h-3" />Save</Button>
                <Button variant="ghost" size="sm" className="h-6 text-xs gap-1" onClick={() => setEditing(false)}><X className="w-3 h-3" />Cancel</Button>
              </div>
            </div>
          ) : (
            <div className="diff-comment__body">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{comment.body}</ReactMarkdown>
          </div>
          )}
        </div>
      </td>
    </tr>
  )
}

// ── New comment form ──────────────────────────────────────────────────────────

function NewCommentRow({
  file,
  line,
  onSave,
  onCancel,
}: {
  file: string
  line: number
  onSave: (type: LineComment['type'], body: string) => void
  onCancel: () => void
}) {
  const [type, setType] = useState<LineComment['type']>('note')
  const [body, setBody] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => { textareaRef.current?.focus() }, [])

  function handleSave() {
    const trimmed = body.trim()
    if (trimmed) onSave(type, trimmed)
  }

  return (
    <tr className="diff-comment-row diff-comment-row--new">
      <td colSpan={3} className="diff-comment-cell diff-comment-cell--new">
        <div className="diff-comment">
          <div className="flex items-center gap-2 mb-1.5">
            <Select value={type} onValueChange={(v) => setType(v as LineComment['type'])}>
              <SelectTrigger className="h-7 w-28 text-xs border-border bg-background" aria-label={`Comment type for ${file}, line ${line}`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="note" className="text-xs">note</SelectItem>
                <SelectItem value="issue" className="text-xs">issue</SelectItem>
                <SelectItem value="suggestion" className="text-xs">suggestion</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <textarea
            ref={textareaRef}
            className="diff-comment__textarea"
            placeholder="Leave a comment…"
            value={body}
            rows={2}
            aria-label={`Comment on ${file}, line ${line}`}
            onChange={(e) => {
              setBody(e.target.value)
              e.target.style.height = 'auto'
              e.target.style.height = e.target.scrollHeight + 'px'
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave()
              if (e.key === 'Escape') onCancel()
            }}
          />
          <div className="flex gap-1.5 mt-1.5">
            <Button size="sm" className="h-6 text-xs gap-1" onClick={handleSave}><Plus className="w-3 h-3" />Add</Button>
            <Button variant="ghost" size="sm" className="h-6 text-xs gap-1" onClick={onCancel}><X className="w-3 h-3" />Cancel</Button>
          </div>
        </div>
      </td>
    </tr>
  )
}
