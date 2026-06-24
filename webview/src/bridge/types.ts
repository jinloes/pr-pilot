/**
 * Messages sent FROM the IntelliJ plugin TO the webview via JCEF executeJavaScript.
 */
export interface PRListLoadedMessage {
  type: 'prListLoaded'
  prs: PR[]
  defaultRepo?: string
  listStatus?: PRListStatus
}

export interface PRListStatus {
  searchScope: PRSearchScope
  currentRepo?: string
  resultLimit: number
  limited: boolean
}

export interface PRLoadingMessage {
  type: 'prLoading'
}

export interface DraftLoadingMessage {
  type: 'draftLoading'
  prKey?: string
}

export interface DraftLoadedMessage {
  type: 'draftLoaded'
  prKey?: string
  prState: 'NO_DRAFT' | 'DRAFT_PRESENT' | 'MERGED'
  reviewId?: string
  result?: ReviewResult
  diff?: string
  validationDiff?: string
  staleCommits?: boolean
  importedFromGitHub?: boolean
  status?: string
}

export interface ReviewGeneratingMessage {
  type: 'reviewGenerating'
  prKey?: string
  message: string
}

export interface ReviewChunkMessage {
  type: 'reviewChunk'
  prKey?: string
  kind: 'text' | 'thinking'
  chunk: string
}

export interface ReviewResultMessage {
  type: 'reviewResult'
  prKey?: string
  result: ReviewResult
  diff: string
  validationDiff?: string
}

export interface ReviewErrorMessage {
  type: 'reviewError'
  prKey?: string
  message: string
}

export interface DraftSavedMessage {
  type: 'draftSaved'
  prKey?: string
  reviewId: string
  commentsDropped: boolean
}

export interface DraftSaveErrorMessage {
  type: 'draftSaveError'
  prKey?: string
  message: string
}

export interface ReviewSubmittedMessage {
  type: 'reviewSubmitted'
  prKey?: string
}

export interface ReviewSubmitErrorMessage {
  type: 'reviewSubmitError'
  prKey?: string
  message: string
}

export interface DraftDeletedMessage {
  type: 'draftDeleted'
  prKey?: string
}

export interface DraftDeleteErrorMessage {
  type: 'draftDeleteError'
  prKey?: string
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
  prKey?: string
  chunk: string
}

export interface ChatResponseMessage {
  type: 'chatResponse'
  prKey?: string
  response: string
}

export interface ChatErrorMessage {
  type: 'chatError'
  prKey?: string
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
  severity?: Severity
  category?: Category
  confidence?: Confidence
  rationale?: string
}

export type Severity = 'blocker' | 'major' | 'minor' | 'nit'
export type Category = 'correctness' | 'security' | 'performance' | 'tests' | 'maintainability' | 'style'
export type Confidence = 'low' | 'medium' | 'high'

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
  searchScope?: PRSearchScope
  assignedToMe?: boolean
  reviewRequested?: boolean
}

export type PRSearchScope = 'currentRepo' | 'authored' | 'assigned' | 'reviewRequested'

export interface GenerateReviewRequest {
  type: 'generateReview'
  number: number
  owner: string
  repo: string
  /** Optional per-review override of the focus areas; falls back to the saved setting. */
  focusAreas?: string
  /** Optional per-review override of custom instructions; falls back to the saved setting. */
  customInstructions?: string
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

export interface OpenSettingsRequest {
  type: 'openSettings'
}

export interface RunAuthLoginRequest {
  type: 'runAuthLogin'
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
  | OpenSettingsRequest
  | RunAuthLoginRequest
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
