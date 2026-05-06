/**
 * Messages sent FROM the IntelliJ plugin TO the webview via JCEF executeJavaScript.
 */
export interface PRListLoadedMessage {
  type: 'prListLoaded'
  prs: PR[]
  defaultRepo?: string
}

export interface PRLoadingMessage {
  type: 'prLoading'
}

export interface DraftLoadingMessage {
  type: 'draftLoading'
}

export interface DraftLoadedMessage {
  type: 'draftLoaded'
  prState: 'NO_DRAFT' | 'DRAFT_PRESENT' | 'MERGED'
  reviewId?: string
  result?: ReviewResult
  diff?: string
  staleCommits?: boolean
  status?: string
}

export interface ReviewGeneratingMessage {
  type: 'reviewGenerating'
  message: string
}

export interface ReviewChunkMessage {
  type: 'reviewChunk'
  kind: 'text' | 'thinking'
  chunk: string
}

export interface ReviewResultMessage {
  type: 'reviewResult'
  result: ReviewResult
  diff: string
}

export interface ReviewErrorMessage {
  type: 'reviewError'
  message: string
}

export interface DraftSavedMessage {
  type: 'draftSaved'
  reviewId: string
  commentsDropped: boolean
}

export interface DraftSaveErrorMessage {
  type: 'draftSaveError'
  message: string
}

export interface ReviewSubmittedMessage {
  type: 'reviewSubmitted'
}

export interface ReviewSubmitErrorMessage {
  type: 'reviewSubmitError'
  message: string
}

export interface DraftDeletedMessage {
  type: 'draftDeleted'
}

export interface DraftDeleteErrorMessage {
  type: 'draftDeleteError'
  message: string
}

export interface PrDraftStatusUpdatedMessage {
  type: 'prDraftStatusUpdated'
  number: number
  owner: string
  repo: string
  hasDraft: boolean
}

export interface ChatChunkMessage {
  type: 'chatChunk'
  chunk: string
}

export interface ChatResponseMessage {
  type: 'chatResponse'
  response: string
}

export interface ChatErrorMessage {
  type: 'chatError'
  message: string
}

export type IncomingMessage =
  | PRListLoadedMessage
  | PRLoadingMessage
  | DraftLoadingMessage
  | DraftLoadedMessage
  | ReviewGeneratingMessage
  | ReviewChunkMessage
  | ReviewResultMessage
  | ReviewErrorMessage
  | DraftSavedMessage
  | DraftSaveErrorMessage
  | ReviewSubmittedMessage
  | ReviewSubmitErrorMessage
  | DraftDeletedMessage
  | DraftDeleteErrorMessage
  | PrDraftStatusUpdatedMessage
  | ChatChunkMessage
  | ChatResponseMessage
  | ChatErrorMessage

export interface PR {
  number: number
  title: string
  owner: string
  repo: string
  author: string
  createdAt: string
  htmlUrl: string
  hasDraft: boolean
}

export interface ReviewResult {
  summary: string
  verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT'
  lineComments: LineComment[]
}

export interface LineComment {
  file: string
  line: number
  type: 'issue' | 'suggestion' | 'note'
  body: string
}

/**
 * Messages sent FROM the webview TO the IntelliJ plugin via window.cefQuery (JCEF).
 */
export interface SelectPRRequest {
  type: 'selectPR'
  number: number
  owner: string
  repo: string
}

export interface RefreshPRsRequest {
  type: 'refreshPRs'
}

export interface GenerateReviewRequest {
  type: 'generateReview'
  number: number
  owner: string
  repo: string
}

export interface AskClaudeRequest {
  type: 'askClaude'
  context: string
  question: string
}

export interface SaveDraftRequest {
  type: 'saveDraft'
  number: number
  owner: string
  repo: string
}

export interface SubmitReviewRequest {
  type: 'submitReview'
  number: number
  owner: string
  repo: string
  verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT'
  comment: string
}

export interface DeleteDraftRequest {
  type: 'deleteDraft'
  number: number
  owner: string
  repo: string
}

export interface CancelReviewRequest {
  type: 'cancelReview'
}

export interface OpenUrlRequest {
  type: 'openUrl'
  url: string
}

export interface ClearChatRequest {
  type: 'clearChat'
}

export type OutgoingMessage =
  | SelectPRRequest
  | RefreshPRsRequest
  | GenerateReviewRequest
  | AskClaudeRequest
  | SaveDraftRequest
  | SubmitReviewRequest
  | DeleteDraftRequest
  | CancelReviewRequest
  | OpenUrlRequest
  | ClearChatRequest

/**
 * Sends a message to the IntelliJ host.
 * No-op with console log when running outside JCEF (dev mode).
 */
export function sendToHost(message: OutgoingMessage): void {
  const w = window as unknown as { cefQuery?: (opts: { request: string }) => void }
  if (w.cefQuery) {
    w.cefQuery({ request: JSON.stringify(message) })
  } else {
    console.debug('[bridge] sendToHost (no JCEF):', message)
  }
}

// Module-level subscriber set so every onHostMessage caller gets every message.
const _handlers = new Set<(message: IncomingMessage) => void>()

function _ensureGlobalDispatcher() {
  const w = window as unknown as { __handleMessage?: (json: string) => void }
  if (w.__handleMessage) return
  w.__handleMessage = (json: string) => {
    let msg: IncomingMessage
    try {
      msg = JSON.parse(json) as IncomingMessage
    } catch (e) {
      console.error('[bridge] failed to parse host message:', json, e)
      return
    }
    _handlers.forEach((h) => h(msg))
  }
}

/**
 * Registers a handler for messages pushed from the IntelliJ host.
 * Multiple callers each get every message (fan-out). Returns a cleanup function.
 */
export function onHostMessage(handler: (message: IncomingMessage) => void): () => void {
  _handlers.add(handler)
  _ensureGlobalDispatcher()
  return () => {
    _handlers.delete(handler)
  }
}
