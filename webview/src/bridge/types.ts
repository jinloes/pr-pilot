/**
 * Messages sent FROM the IntelliJ plugin TO the webview via JCEF executeJavaScript.
 */
export interface PRListLoadedMessage {
  type: 'prListLoaded'
  prs: PR[]
}

export interface PRLoadingMessage {
  type: 'prLoading'
}

export interface ReviewLoadedMessage {
  type: 'reviewLoaded'
  summary: string
  verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT'
  lineComments: LineComment[]
}

export interface StatusUpdateMessage {
  type: 'statusUpdate'
  text: string
}

export interface ChatChunkMessage {
  type: 'chatChunk'
  chunk: string
}

export type IncomingMessage =
  | PRListLoadedMessage
  | PRLoadingMessage
  | ReviewLoadedMessage
  | StatusUpdateMessage
  | ChatChunkMessage

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
}

export interface AskClaudeRequest {
  type: 'askClaude'
  context: string
  question: string
}

export interface SaveDraftRequest {
  type: 'saveDraft'
}

export interface SubmitReviewRequest {
  type: 'submitReview'
  verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT'
}

export type OutgoingMessage =
  | SelectPRRequest
  | RefreshPRsRequest
  | GenerateReviewRequest
  | AskClaudeRequest
  | SaveDraftRequest
  | SubmitReviewRequest

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

/**
 * Registers a handler for messages pushed from the IntelliJ host.
 * The host calls window.__handleMessage(jsonString) via executeJavaScript.
 */
export function onHostMessage(handler: (message: IncomingMessage) => void): () => void {
  const w = window as unknown as { __handleMessage?: (json: string) => void }
  w.__handleMessage = (json: string) => {
    try {
      handler(JSON.parse(json) as IncomingMessage)
    } catch (e) {
      console.error('[bridge] failed to parse host message:', json, e)
    }
  }
  return () => {
    delete w.__handleMessage
  }
}
