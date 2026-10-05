import { startTransition, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, ChevronUp, Search, X } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { TooltipProvider } from '@/components/ui/tooltip'
import type { FileData, HunkData } from 'react-diff-view'
import { scrollBehavior } from '@/lib/motion'
import { useI18n } from '@/i18n/I18nProvider'
import type { LineComment } from '@/bridge/types'
import { cn } from '@/lib/utils'
import { parseDiffSafely } from '@/lib/diffParse'
import {
  buildDiffFileNavItems,
  buildDiffFileTree,
  displayPathForFile,
  findActiveFileIndex,
} from './fileNavigation'
import { buildFindingNavItems } from './findingNavigation'
import { FileTreeNodeView, FileView } from './DiffViewerRows'
export { DELETE_COMMENT_DESCRIPTION } from './diffHelpers'
import { findByPathSuffix, findingToneClass, groupComments, type IndexedComment, type PendingNew } from './diffHelpers'
import './DiffViewer.css'

const MAX_CHANGES = 500

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
