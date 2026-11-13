import type { PointerEvent as ReactPointerEvent, RefObject } from 'react'
import type {
  ChatContextLocation,
  DeepReviewPreparedMessage,
  LineComment,
  PR,
  RetainedDeepReview,
  ReviewResult,
} from '../../bridge/types'
import { coverageGain, type DiffCoverage } from '@/lib/diffCoverage'
import { formatLocation } from '@/lib/selectionLocation'
import type { ReviewQualityAction, ReviewQualityReport } from '@/lib/reviewQuality'
import type { ExampleFixResult, VerifyResult } from '../ChatPane/structuredResult'
import type { PaneState, Verdict } from './reviewState'
import type { ReviewActivity } from './reviewActivity'
import type { PublishedBodySections } from './publishBody'

export interface DiffPreflight {
  fileCount: number
  changedLines: number
}

export interface ChunkRecommendation {
  recommendChunked: boolean
  reason: string
}

/** Text the reviewer selected in the review pane, and where in the diff it came from. */
export interface ChatSelection {
  text: string
  location?: ChatContextLocation
}

export interface PendingChatMessage {
  q: string
  ctx: string
  /** Where `ctx` came from in the diff, when known. */
  location?: ChatContextLocation
  id: number
  token?: string
  contextSummary?: string[]
}

export interface ReviewViewModel {
  intellijAssisted: boolean
  intellijAssistedEnabled: boolean
  deepSetup: DeepReviewPreparedMessage | null
  retainedDeepReviews: RetainedDeepReview[]
  deepMaintenanceError: string
  deepBusy: boolean
  pr: PR | null
  state: PaneState
  activity: ReviewActivity
  result: ReviewResult | null
  diff: string
  validationDiff: string
  diffUnavailable: boolean
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  qualityReport: ReviewQualityReport | null
  qualityRiskCount: number
  preflight: DiffPreflight | null
  recommendation: ChunkRecommendation
  /** Changed files chunked review would add over the standard review diff. */
  coverageGain: number
  /** What the engine appends below the reviewer's text when publishing. */
  publishSections: PublishedBodySections
  /** True once a save this session moved comments GitHub rejected inline into the review body. */
  commentsMovedToBody: boolean
  focusAreasOverride: string
  customInstructionsOverride: string
  /** Remembered instructions the host applies to every review of this repository. */
  repositoryInstructions: string
  repositoryInstructionsDraft: string
  repositoryInstructionsSaving: boolean
  repositoryInstructionsError: string
  /** True after the host confirmed the latest save, until the draft is edited again. */
  repositoryInstructionsSaved: boolean
  chunkedMode: boolean
  showReviewOverrides: boolean
  saving: boolean
  submitting: boolean
  deleting: boolean
  autosaveDirty: boolean
  focusedCommentIdx: number
  commentFocusRequestId: number
  showChat: boolean
  chatVisible: boolean
  selectedContext: string
  selectedContextLocation?: ChatContextLocation
  pendingChatMessage: PendingChatMessage | null
  chatHeight: number
  chatAvailableHeight: number
  contextSummary: string[]
  qualityExpanded: boolean
  hasReview: boolean
  /** True while the diff is editable, so a chat reply can become an inline comment. */
  canPlaceChatReply: boolean
  /** Chat reply waiting for the reviewer to pick the diff line it should be commented on. */
  chatCommentDraft: string | null
  statusMessage: string
}

export interface ReviewActions {
  setIntellijAssisted: (value: boolean) => void
  continueDeepReview: (server: string) => void
  ordinaryReview: () => void
  listDeepReviews: () => void
  cleanupDeepReview: (id: string) => void
  setFocusAreasOverride: (value: string) => void
  setCustomInstructionsOverride: (value: string) => void
  setRepositoryInstructionsDraft: (value: string) => void
  saveRepositoryInstructions: () => void
  setChunkedMode: (value: boolean) => void
  generate: () => void
  generateIncremental: () => void
  cancel: () => void
  save: () => void
  deleteDraft: () => void
  reloadDraft: () => void
  keepDraft: () => void
  reanchorDraft: () => void
  submit: (verdict: Verdict, comment?: string) => void
  verifyComment: (comment: LineComment) => void
  suggestFixComment: (comment: LineComment) => void
  applyVerifyAction: (verify: VerifyResult, token: string) => void
  attachExamplePatch: (fix: ExampleFixResult, token: string) => void
  editCommentHandlers: {
    onEditComment: (index: number, body: string) => void
    onDeleteComment: (index: number) => void
    onRemoveSuggestion: (index: number) => void
    onAddComment: (comment: LineComment) => void
  }
  orphanHandlers: {
    onEditOrphan: (orphan: LineComment, body: string) => void
    onDeleteOrphan: (orphan: LineComment) => void
  }
  runQualityCheck: () => void
  applyQualityRepair: (action: ReviewQualityAction) => void
  collapseQualityCheck: () => void
  focusComment: (index: number) => void
  focusPreviousComment: () => void
  focusNextComment: () => void
  toggleChat: () => void
  openChat: () => void
  clearSelectedContext: () => void
  askAboutSelection: (question: string) => void
  pendingMessageSent: () => void
  /** Starts "pick a line" mode in the diff with this chat reply as the new comment's text. */
  placeChatReply: (body: string) => void
  cancelChatReplyPlacement: () => void
  setChatHeight: (height: number) => void
  commitChatHeight: (height: number) => void
  startChatResize: (event: ReactPointerEvent) => void
  openPr: () => void
  openSettings: () => void
  openAuthGuide: () => void
  discardPendingChanges: () => boolean
}

export interface ReviewRefs {
  paneRef: RefObject<HTMLDivElement | null>
  reviewBodyRef: RefObject<HTMLDivElement | null>
}

export interface ReviewController {
  model: ReviewViewModel
  actions: ReviewActions
  refs: ReviewRefs
}

export interface UseReviewControllerProps {
  pr: PR | null
  onDirtyStateChange?: (dirty: boolean) => void
}

export interface InFlightSave {
  saveId: number
  prKey: string
  snapshot: string
  isAuto: boolean
}

export interface PendingAutosave {
  pr: PR
  result: ReviewResult
  orphans: LineComment[]
  snapshot: string
}

export interface WatchdogRef {
  current: ReturnType<typeof setTimeout> | null
}

export const MUTATION_WATCHDOG_MS = 45_000
export const SELECTION_CAPTURE_DEBOUNCE_MS = 150

/** Selections inside the chat composer or any text field are typing, not context to attach. */
export function isSelectionInFormField(selection: Selection | null): boolean {
  const anchor = selection?.anchorNode ?? null
  const element = anchor instanceof Element ? anchor : anchor?.parentElement ?? null
  if (!element) return false
  if (element.closest('.chat-pane__input, input, textarea, [contenteditable="true"]')) return true
  const active = document.activeElement
  return active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement
}

export function newOperationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
}

export function summarizeDiffPreflight(diff: string): DiffPreflight | null {
  if (!diff.trim()) return null
  const rows = diff.split(/\r?\n/)
  const files = new Set<string>()
  let changedLines = 0
  for (const row of rows) {
    if (row.startsWith('+++ b/')) {
      files.add(row.slice('+++ b/'.length).trim())
      continue
    }
    if ((row.startsWith('+') && !row.startsWith('+++')) || (row.startsWith('-') && !row.startsWith('---'))) {
      changedLines += 1
    }
  }
  return { fileCount: files.size, changedLines }
}

function sizeRecommendation(preflight: DiffPreflight): ChunkRecommendation {
  if (preflight.fileCount >= 8) {
    return { recommendChunked: true, reason: 'Many changed files.' }
  }
  if (preflight.changedLines >= 300) {
    return { recommendChunked: true, reason: 'Large changed-line count.' }
  }
  return { recommendChunked: false, reason: 'Single-pass review is likely sufficient.' }
}

export function chunkRecommendation(
  preflight: DiffPreflight | null,
  reviewCoverage: DiffCoverage | null,
  chunkCoverage: DiffCoverage | null,
): ChunkRecommendation {
  if (!preflight) {
    return { recommendChunked: false, reason: 'Recommendation appears once the diff is loaded.' }
  }
  const gain = coverageGain(reviewCoverage, chunkCoverage)
  if (gain > 0) {
    return {
      recommendChunked: true,
      reason: `Chunked review includes ${gain} changed file${gain === 1 ? '' : 's'} that single-pass review omits.`,
    }
  }
  const bySize = sizeRecommendation(preflight)
  if (!reviewCoverage) return bySize
  return bySize.recommendChunked
    ? { recommendChunked: true, reason: `${bySize.reason} Chunked review would not add coverage.` }
    : { recommendChunked: false, reason: 'Chunked review would not add coverage.' }
}

export function chatContextSummary(
  pr: PR | null,
  diff: string,
  reviewCoverage: DiffCoverage | null,
  result: ReviewResult | null,
  selectedContext: string,
  selectedContextLocation?: ChatContextLocation,
): string[] {
  if (!pr) return []
  const items = ['PR title/body']
  if (diff) items.push(reviewCoverage ? 'diff excerpt' : 'diff')
  if (result) items.push('generated review')
  if (selectedContext) {
    items.push(selectedContextLocation ? `selected text (${formatLocation(selectedContextLocation)})` : 'selected text')
  }
  return items
}

/** The diff location a review comment is anchored to. */
export function commentLocation(comment: Pick<LineComment, 'file' | 'line'>): ChatContextLocation | undefined {
  if (!comment.file) return undefined
  return comment.line > 0 ? { file: comment.file, startLine: comment.line, endLine: comment.line } : { file: comment.file }
}

export function clearWatchdog(ref: WatchdogRef) {
  if (ref.current !== null) {
    clearTimeout(ref.current)
    ref.current = null
  }
}

export function armWatchdog(ref: WatchdogRef, onTimeout: () => void) {
  clearWatchdog(ref)
  ref.current = setTimeout(() => {
    ref.current = null
    onTimeout()
  }, MUTATION_WATCHDOG_MS)
}
