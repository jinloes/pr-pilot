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

export interface SetupRequiredMessage {
  type: 'setupRequired'
  /** 'gh_not_installed' | 'gh_not_authenticated' | 'load_failed' */
  reason: 'gh_not_installed' | 'gh_not_authenticated' | 'load_failed'
  detail: string
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
  | SetupRequiredMessage

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
 * Messages sent FROM the webview TO the host (IntelliJ via JCEF or VS Code).
 */
export interface SelectPRRequest {
  type: 'selectPR'
  number: number
  owner: string
  repo: string
}

export interface RefreshPRsRequest {
  type: 'refreshPRs'
  state?: string
  assignedToMe?: boolean
  reviewRequested?: boolean
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
  result?: ReviewResult
  /**
   * Comments the webview pre-validated as having no anchor in the diff. The host
   * skips these when building the inline-comment POST and appends them to the
   * review body in a "Comments not attached inline" section instead.
   */
  orphans?: LineComment[]
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

// VS Code injects acquireVsCodeApi() into the webview's global scope.
// It can only be called once per session — store the result as a singleton.
declare function acquireVsCodeApi(): {
  postMessage(msg: unknown): void
  getState(): unknown
  setState(state: unknown): void
}

type VsCodeApi = ReturnType<typeof acquireVsCodeApi>

function _getVsCodeApi(): VsCodeApi | null {
  try {
    return typeof acquireVsCodeApi !== 'undefined' ? acquireVsCodeApi() : null
  } catch {
    return null
  }
}

const _vsCodeApi: VsCodeApi | null = _getVsCodeApi()

/**
 * Sends a message to the host (IntelliJ via JCEF, VS Code, or dev console).
 */
export function sendToHost(message: OutgoingMessage): void {
  if (_vsCodeApi) {
    _vsCodeApi.postMessage(message)
  } else {
    const w = window as unknown as { cefQuery?: (opts: { request: string }) => void }
    if (w.cefQuery) {
      w.cefQuery({ request: JSON.stringify(message) })
    } else {
      console.debug('[bridge] sendToHost (no host):', message)
    }
  }
}

// Module-level subscriber set so every onHostMessage caller gets every message.
const _handlers = new Set<(message: IncomingMessage) => void>()

function _dispatch(msg: IncomingMessage) {
  _handlers.forEach((h) => h(msg))
}

function _ensureGlobalDispatcher() {
  if (_vsCodeApi) {
    // VS Code delivers messages via window.addEventListener('message', ...).
    // Register once; the listener is never removed because it must outlive any
    // individual component that calls onHostMessage.
    window.addEventListener('message', (event: MessageEvent) => {
      _dispatch(event.data as IncomingMessage)
    })
  } else {
    // JCEF: the plugin calls window.__handleMessage(payload). Payload is normally a parsed
    // object (the host embeds JSON directly as a JS expression), but legacy/dev code may pass
    // a JSON string — accept both.
    type HostMessage = IncomingMessage | string
    const w = window as unknown as { __handleMessage?: (payload: HostMessage) => void }
    if (w.__handleMessage) return
    w.__handleMessage = (payload: HostMessage) => {
      let msg: IncomingMessage
      if (typeof payload === 'string') {
        try {
          msg = JSON.parse(payload) as IncomingMessage
        } catch (e) {
          console.error('[bridge] failed to parse host message:', payload, e)
          return
        }
      } else {
        msg = payload
      }
      _dispatch(msg)
    }
  }
}

// Wire up the dispatcher immediately so handlers registered before the first
// message arrives are all notified.
_ensureGlobalDispatcher()

/**
 * Registers a handler for messages pushed from the host.
 * Multiple callers each get every message (fan-out). Returns a cleanup function.
 */
export function onHostMessage(handler: (message: IncomingMessage) => void): () => void {
  _handlers.add(handler)
  return () => {
    _handlers.delete(handler)
  }
}
