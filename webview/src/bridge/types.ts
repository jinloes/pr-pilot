import { BRIDGE_PROTOCOL_VERSION, parseIncomingMessage } from './validation'

/**
 * Messages sent FROM the IntelliJ plugin TO the webview via JCEF executeJavaScript.
 */
export interface PRListLoadedMessage {
  type: 'prListLoaded'
  prs: PR[]
  defaultRepo?: string
  listStatus?: PRListStatus
  providerReadiness?: ProviderReadiness
}

export interface PRListStatus {
  searchScope: PRSearchScope
  currentRepo?: string
  resultLimit: number
  limited: boolean
  reviewStatusAvailable: boolean
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
  recoveryPending?: boolean
  status?: string
  providerReadiness?: ProviderReadiness
  /** Experimental host setting; absent or anything but `true` hides IntelliJ-assisted controls. */
  intellijAssistedEnabled?: boolean
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

export interface ValidationDiffUpdatedMessage {
  type: 'validationDiffUpdated'
  prKey: string
  validationDiff: string
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
  saveId: number
}

export interface DraftSaveErrorMessage {
  type: 'draftSaveError'
  prKey?: string
  message: string
  saveId: number
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
  hasReviewDraft: boolean
}

export interface ActivatePrMessage {
  type: 'activatePR'
  pr: PR
  source?: 'notification'
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
  reason: 'gh_not_installed' | 'gh_not_authenticated' | 'provider_not_installed' | 'provider_not_authenticated' | 'load_failed' | 'draft_index_unavailable'
  detail: string
  providerReadiness?: ProviderReadiness
}

export type HostTheme = 'light' | 'dark' | 'highContrastLight' | 'highContrastDark'

export interface ThemeChangedMessage {
  type: 'themeChanged'
  theme: HostTheme
}

export interface DeepReviewPreparedMessage {
  type: 'deepReviewPrepared'
  operationId: string
  prKey: string
  retainedId: string
  worktree: string
  head: string
  servers: string[]
  message: string
}
export interface RetainedDeepReview {
  id: string
  repository: string
  worktree: string
  head: string
  createdAt: number
}
export type IncomingMessage = { readonly protocolVersion: typeof BRIDGE_PROTOCOL_VERSION } & (
  | DeepReviewPreparedMessage
  | { type: 'retainedDeepReviews'; operationId: string; retained: RetainedDeepReview[] }
  | { type: 'deepReviewMaintenanceError'; operationId: string; message: string }
  | PRListLoadedMessage
  | PRLoadingMessage
  | DraftLoadingMessage
  | DraftLoadedMessage
  | ReviewGeneratingMessage
  | ReviewChunkMessage
  | ReviewResultMessage
  | ValidationDiffUpdatedMessage
  | ReviewErrorMessage
  | DraftSavedMessage
  | DraftSaveErrorMessage
  | ReviewSubmittedMessage
  | ReviewSubmitErrorMessage
  | DraftDeletedMessage
  | DraftDeleteErrorMessage
  | PrDraftStatusUpdatedMessage
  | ActivatePrMessage
  | ChatChunkMessage
  | ChatResponseMessage
  | ChatErrorMessage
  | SetupRequiredMessage
  | ThemeChangedMessage)

export interface PR {
  number: number
  title: string
  owner: string
  repo: string
  author: string
  createdAt: string
  htmlUrl: string
  isDraft: boolean
  hasReviewDraft: boolean
  reviewStatus: ReviewStatus
}

export type ReviewStatus = 'UNREVIEWED' | 'REVIEWED' | 'UPDATED_SINCE_REVIEW' | 'UNAVAILABLE'

export interface ProviderReadiness {
  provider: 'claude' | 'copilot'
  available: boolean
  detail: string
  binaryStatus?: 'ready' | 'missing'
  authenticationStatus?: 'ready' | 'unavailable' | 'unverified'
  authCommand?: string
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
export type Category = 'correctness' | 'security' | 'performance' | 'tests' | 'maintainability' | 'compatibility' | 'style'
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
  operationId: string
  number: number
  owner: string
  repo: string
  /** Optional complete validation diff used by engine-owned chunking. */
  diff?: string
  /** Runs bounded batches plus a mandatory engine-owned global reconciliation pass. */
  chunkedReview?: boolean
  intellijAssisted?: boolean
  /** Optional per-review override of the focus areas; falls back to the saved setting. */
  focusAreas?: string
  /** Optional per-review override of custom instructions; falls back to the saved setting. */
  customInstructions?: string
}

export interface AskClaudeRequest {
  type: 'askClaude'
  operationId: string
  context: string
  question: string
}

export interface SaveDraftRequest {
  type: 'saveDraft'
  number: number
  owner: string
  repo: string
  saveId: number
  result?: ReviewResult
  /** Final model-authored baseline before reviewer edits; merged across chunked batches. */
  generatedResult?: ReviewResult
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
  operationId: string
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
  operationId: string
}

export interface WebviewLayoutChangedRequest {
  type: 'webviewLayoutChanged'
  reason: string
}

export type OutgoingMessage =
  | { type: 'continueDeepReview'; operationId: string; number: number; owner: string; repo: string; retainedId: string; server: string }
  | { type: 'listDeepReviews'; operationId: string }
  | { type: 'cleanupDeepReview'; operationId: string; retainedId: string; projectClosed: true }
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
  | WebviewLayoutChangedRequest

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
  const versionedMessage = { protocolVersion: BRIDGE_PROTOCOL_VERSION, ...message }
  if (_vsCodeApi) {
    _vsCodeApi.postMessage(versionedMessage)
  } else {
    const w = window as unknown as { cefQuery?: (opts: { request: string }) => void }
    if (w.cefQuery) {
      w.cefQuery({ request: JSON.stringify(versionedMessage) })
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
      const message = parseIncomingMessage(event.data)
      if (message) _dispatch(message)
      else console.error('[bridge] rejected invalid host message')
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
          const parsed = parseIncomingMessage(JSON.parse(payload))
          if (!parsed) {
            console.error('[bridge] rejected invalid host message')
            return
          }
          msg = parsed
        } catch (e) {
          console.error('[bridge] failed to parse host message:', payload, e)
          return
        }
      } else {
        const parsed = parseIncomingMessage(payload)
        if (!parsed) {
          console.error('[bridge] rejected invalid host message')
          return
        }
        msg = parsed
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
