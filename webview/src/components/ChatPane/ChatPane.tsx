import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, Check, Send, Square, X, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import { onHostMessage, sendToHost, type PR } from '@/bridge/types'
import { LiveStatus } from '../a11y/LiveStatus'
import { MarkdownContent } from '../MarkdownContent/MarkdownContent'
import { useI18n } from '@/i18n/I18nProvider'
import { parseStructuredResult, type ExampleFixResult, type StructuredResult, type VerifyResult } from './structuredResult'

interface Message {
  role: 'user' | 'assistant'
  content: string
  isError?: boolean
  /** The user stopped this turn before the provider answered. */
  stopped?: boolean
  contextSummary?: string[]
  /**
   * Opaque caller token copied from the `pendingMessage` this reply answers. ChatPane never
   * interprets it — it exists so the caller can map a verify result back to the exact comment
   * that prompted it, rather than to "whatever was verified most recently".
   */
  token?: string
}

interface Props {
  pr: PR
  selectedContext?: string
  onContextUsed?: () => void
  pendingMessage?: {
    q: string
    ctx: string
    id: number
    token?: string
    contextSummary?: string[]
  }
  onPendingMessageSent?: () => void
  contextSummary?: string[]
  /** Applies a verify verdict's suggested action to the comment identified by `token`. */
  onApplyVerifyAction?: (result: VerifyResult, token: string) => void
}

function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
}

function newOperationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function ChatPane({
  pr,
  selectedContext,
  onContextUsed,
  pendingMessage,
  onPendingMessageSent,
  contextSummary = [],
  onApplyVerifyAction,
}: Props) {
  const t = useI18n()
  const [messages, setMessages] = useState<Message[]>([])
  const [streaming, setStreaming] = useState('')
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [activeContextSummary, setActiveContextSummary] = useState<string[] | null>(null)
  const [appliedTokens, setAppliedTokens] = useState<Set<string>>(new Set())
  const messagesRef = useRef<HTMLDivElement>(null)
  const sentPendingMessageIdRef = useRef<number | null>(null)
  const pendingTokenRef = useRef<string | undefined>(undefined)
  const pendingContextSummaryRef = useRef<string[]>([])
  const activeOperationIdRef = useRef<string | null>(null)
  const stoppedTurnRef = useRef(false)

  useEffect(() => {
    setMessages([])
    setStreaming('')
    setInput('')
    setBusy(false)
    setActiveContextSummary(null)
    setAppliedTokens(new Set())
    pendingTokenRef.current = undefined
    pendingContextSummaryRef.current = []
    activeOperationIdRef.current = null
    stoppedTurnRef.current = false
  }, [pr.number, pr.owner, pr.repo])

  useEffect(() => {
    return onHostMessage((msg) => {
      if ('prKey' in msg && msg.prKey && msg.prKey !== prKey(pr)) return

      // Chat messages carry no operation id, so after Stop anything until the next question
      // belongs to the stopped turn and must not resurrect it.
      if ((msg.type === 'chatChunk' || msg.type === 'chatResponse' || msg.type === 'chatError')
        && stoppedTurnRef.current) return

      switch (msg.type) {
        case 'chatChunk':
          setStreaming((s) => s + msg.chunk)
          break
        case 'chatResponse': {
          const token = pendingTokenRef.current
          const responseContextSummary = pendingContextSummaryRef.current.length > 0
            ? [...pendingContextSummaryRef.current]
            : undefined
          pendingTokenRef.current = undefined
          pendingContextSummaryRef.current = []
          setActiveContextSummary(null)
          setStreaming('')
          setMessages((prev) => [...prev, {
            role: 'assistant',
            content: msg.response,
            token,
            contextSummary: responseContextSummary,
          }])
          setBusy(false)
          activeOperationIdRef.current = null
          break
        }
        case 'chatError': {
          const responseContextSummary = pendingContextSummaryRef.current.length > 0
            ? [...pendingContextSummaryRef.current]
            : undefined
          pendingTokenRef.current = undefined
          pendingContextSummaryRef.current = []
          setActiveContextSummary(null)
          setStreaming('')
          setMessages((prev) => [
            ...prev,
            {
              role: 'assistant',
              content: msg.message,
              isError: true,
              contextSummary: responseContextSummary,
            },
          ])
          setBusy(false)
          activeOperationIdRef.current = null
          break
        }
        default:
          break
      }
    })
  }, [pr])

  useEffect(() => {
    if (!pendingMessage || busy || sentPendingMessageIdRef.current === pendingMessage.id) return
    const { q, ctx, id, token } = pendingMessage
    const requestContextSummary = pendingMessage.contextSummary ?? contextSummary
    sentPendingMessageIdRef.current = id
    pendingTokenRef.current = token
    pendingContextSummaryRef.current = [...requestContextSummary]
    setActiveContextSummary(requestContextSummary)
    setMessages((prev) => [...prev, {
      role: 'user',
      content: q,
      contextSummary: requestContextSummary,
    }])
    setBusy(true)
    onPendingMessageSent?.()
    const operationId = newOperationId()
    stoppedTurnRef.current = false
    activeOperationIdRef.current = operationId
    sendToHost({ type: 'askClaude', operationId, context: ctx, question: q })
  }, [pendingMessage, busy, onPendingMessageSent, contextSummary])

  function handleApplyVerifyAction(result: VerifyResult, token: string) {
    onApplyVerifyAction?.(result, token)
    setAppliedTokens((prev) => new Set(prev).add(token))
  }

  useEffect(() => {
    const messagesElement = messagesRef.current
    if (messagesElement) {
      messagesElement.scrollTop = messagesElement.scrollHeight
    }
  }, [messages.length, busy])

  function handleClear() {
    const operationId = activeOperationIdRef.current ?? newOperationId()
    setMessages([])
    setStreaming('')
    setBusy(false)
    setActiveContextSummary(null)
    pendingTokenRef.current = undefined
    pendingContextSummaryRef.current = []
    activeOperationIdRef.current = null
    stoppedTurnRef.current = false
    sendToHost({ type: 'clearChat', operationId })
  }

  function handleStop() {
    const operationId = activeOperationIdRef.current
    if (!operationId) return
    activeOperationIdRef.current = null
    stoppedTurnRef.current = true
    pendingTokenRef.current = undefined
    pendingContextSummaryRef.current = []
    setActiveContextSummary(null)
    setStreaming('')
    setBusy(false)
    setMessages((prev) => [...prev, { role: 'assistant', content: 'Response stopped.', stopped: true }])
    sendToHost({ type: 'cancelChat', operationId })
  }

  function handleSend() {
    const q = input.trim()
    if (!q || busy) return
    const ctx = selectedContext ?? ''
    pendingContextSummaryRef.current = [...contextSummary]
    setActiveContextSummary(contextSummary)
    setMessages((prev) => [...prev, { role: 'user', content: q, contextSummary }])
    setInput('')
    setBusy(true)
    onContextUsed?.()
    const operationId = newOperationId()
    stoppedTurnRef.current = false
    activeOperationIdRef.current = operationId
    sendToHost({ type: 'askClaude', operationId, context: ctx, question: q })
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const hasContent = messages.length > 0 || !!streaming || busy
  const latestMessageContext = [...messages]
    .reverse()
    .find((message) => message.contextSummary && message.contextSummary.length > 0)
    ?.contextSummary
  const displayedContextSummary = busy && activeContextSummary && activeContextSummary.length > 0
    ? activeContextSummary
    : (latestMessageContext ?? contextSummary)

  return (
    <section className="flex flex-1 min-h-0 flex-col border-t border-border bg-card" aria-labelledby="chat-heading">
      <LiveStatus message={busy ? 'AI response in progress' : streaming ? 'AI response started' : ''} />
      <div className="flex items-center justify-between px-3 py-1.5 border-b border-border shrink-0">
        <h2 id="chat-heading" className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          Chat
        </h2>
        {displayedContextSummary.length > 0 && (
          <span
            className="min-w-0 flex-1 truncate px-2 text-[11px] text-muted-foreground"
            title={`Context: ${displayedContextSummary.join(', ')}`}
          >
            Context: {displayedContextSummary.join(', ')}
          </span>
        )}
        {hasContent && (
          <Button variant="ghost" size="sm" onClick={handleClear} className="h-6 px-2 text-xs">
            Clear
          </Button>
        )}
      </div>

      {/* Keep this flexible region mounted so an empty chat anchors its composer to the panel bottom. */}
      <div
        ref={messagesRef}
        data-testid="chat-messages"
        role="region"
        aria-label="Chat messages"
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- Scrollable chat history needs a keyboard focus target.
        tabIndex={0}
        className="flex-1 min-h-0 overflow-y-auto p-3 space-y-3 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
      >
        {hasContent && (
          <>
          {messages.map((m, i) => {
            const structured = m.role === 'assistant' && !m.isError ? parseStructuredResult(m.content) : null
            return (
              <div key={i} className={cn('flex flex-col gap-1', m.role === 'user' ? 'items-end' : 'items-start')}>
                <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">
                  {m.role === 'user' ? 'you' : 'ai'}
                </span>
                {structured ? (
                  <StructuredResultCard
                    result={structured}
                    onApplyVerifyAction={
                      m.token && onApplyVerifyAction
                        ? (verify) => handleApplyVerifyAction(verify, m.token!)
                        : undefined
                    }
                    applied={!!m.token && appliedTokens.has(m.token)}
                  />
                ) : (
                  <div
                    className={cn(
                      'rounded-md px-3 py-2 text-sm max-w-[90%]',
                      m.role === 'user'
                        ? 'bg-primary text-primary-foreground'
                        : m.isError
                          ? 'flex items-start gap-2 border border-status-issue/50 bg-status-issue/10 text-foreground'
                          : m.stopped
                            ? 'border border-border text-muted-foreground italic'
                            : 'bg-secondary text-secondary-foreground',
                    )}
                  >
                    {m.isError ? (
                      <>
                        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-status-issue" aria-hidden="true" />
                        <span>{m.content}</span>
                      </>
                    ) : m.stopped ? (
                      m.content
                    ) : (
                      <MarkdownContent className={cn(
                        '[&_p]:my-0.5 [&_li]:my-0 [&_blockquote]:italic',
                        m.role === 'user'
                          ? '[&_code]:bg-primary-foreground/20 [&_pre]:bg-primary-foreground/20 [&_blockquote]:border-primary-foreground/40 [&_a]:text-primary-foreground'
                          : '[&_code]:bg-background/50 [&_pre]:bg-background/50 [&_blockquote]:border-muted-foreground/40',
                      )}>
                        {m.content}
                      </MarkdownContent>
                    )}
                  </div>
                )}
              </div>
            )
          })}


          {busy && !streaming && (
            <div className="flex flex-col gap-1 items-start">
              <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">ai</span>
              <div className="bg-secondary rounded-md px-3 py-2 flex gap-1">
                {[0, 200, 400].map((delay) => (
                  <span
                    key={delay}
                    className="w-1.5 h-1.5 rounded-full bg-muted-foreground animate-bounce"
                    style={{ animationDelay: `${delay}ms` }}
                  />
                ))}
              </div>
            </div>
          )}

          {streaming && (() => {
            const streamedStructured = parseStructuredResult(streaming)
            return (
              <div className="flex flex-col gap-1 items-start">
                <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">ai</span>
                {streamedStructured ? (
                  <StructuredResultCard result={streamedStructured} />
                ) : (
                  <div className="bg-secondary text-secondary-foreground rounded-md px-3 py-2 text-sm max-w-[90%]">
                    <MarkdownContent className="[&_code]:bg-background/50 [&_pre]:bg-background/50 [&_p]:my-0.5">
                      {streaming}
                    </MarkdownContent>
                    <span className="inline-block w-2 h-3.5 bg-primary animate-pulse ml-0.5 align-text-bottom" />
                  </div>
                )}
              </div>
            )
          })()}
          </>
        )}
      </div>

      {/* Selected context badge */}
      {selectedContext && (
        <div className="mx-3 mb-1 flex items-center gap-2 rounded border border-border bg-muted/50 px-2 py-1">
          <span className="flex-1 truncate text-xs text-muted-foreground">{selectedContext}</span>
          <Button
            variant="ghost"
            size="sm"
            onClick={onContextUsed}
            className="h-6 w-6 p-0 shrink-0"
            aria-label="Clear selected context"
          >
            <X className="w-3 h-3" />
          </Button>
        </div>
      )}

      {/* Input */}
      <div className="flex gap-2 p-3 pt-2 shrink-0">
        <label htmlFor="pr-chat-input" className="sr-only">{t('chat.input')}</label>
        <Textarea
          id="pr-chat-input"
          className="min-h-[60px] resize-none text-sm bg-background border-input focus-visible:ring-ring"
          placeholder="Ask about this PR…"
          title="Enter to send · Shift+Enter for newline"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={2}
        />
        {busy ? (
          <Button
            size="sm"
            variant="secondary"
            onClick={handleStop}
            className="self-end shrink-0"
            title="Stop response"
            aria-label="Stop response"
          >
            <Square className="w-4 h-4" />
          </Button>
        ) : (
          <Button
            size="sm"
            onClick={handleSend}
            disabled={!input.trim()}
            className="self-end shrink-0"
            title="Send (Enter)"
            aria-label="Send"
          >
            <Send className="w-4 h-4" />
          </Button>
        )}
      </div>
      <p className="px-3 pb-2 text-[11px] text-muted-foreground">
        Uses the active PR context shown above · right-click selected text to ask about it
      </p>
    </section>
  )
}

const VERDICT_STYLE: Record<VerifyResult['verdict'], { label: string; className: string; Icon: typeof Check }> = {
  valid: { label: 'Valid', className: 'text-status-approve border-status-approve/50 bg-status-approve/10', Icon: Check },
  invalid: { label: 'Invalid', className: 'text-status-changes border-status-changes/50 bg-status-changes/10', Icon: XCircle },
  unclear: { label: 'Unclear', className: 'text-status-suggestion border-status-suggestion/50 bg-status-suggestion/10', Icon: AlertTriangle },
}

const ACTION_LABEL: Record<VerifyResult['action'], string> = {
  keep: 'Keep as-is',
  revise: 'Revise',
  delete: 'Delete',
}

function StructuredResultCard({
  result,
  onApplyVerifyAction,
  applied = false,
}: {
  result: StructuredResult
  onApplyVerifyAction?: (result: VerifyResult) => void
  applied?: boolean
}) {
  return (
    <div className="w-full max-w-[90%] rounded-md border border-border bg-secondary text-secondary-foreground overflow-hidden">
      {result.kind === 'verify' ? (
        <VerifyResultCard result={result} onApply={onApplyVerifyAction} applied={applied} />
      ) : (
        <ExampleFixResultCard result={result} />
      )}
    </div>
  )
}

/**
 * True when the verdict's action changes the review. `keep` is a no-op, and `revise` without a
 * replacement has nothing to write, so neither offers a button — an "Apply" that does nothing is
 * worse than no button at all.
 */
function isApplicable(result: VerifyResult): boolean {
  if (result.action === 'delete') return true
  return result.action === 'revise' && !!result.replacementComment?.trim()
}

function VerifyResultCard({
  result,
  onApply,
  applied = false,
}: {
  result: VerifyResult
  onApply?: (result: VerifyResult) => void
  applied?: boolean
}) {
  const { label, className, Icon } = VERDICT_STYLE[result.verdict]
  const canApply = !!onApply && isApplicable(result)
  return (
    <div className="p-3 space-y-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className={cn('inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[11px] font-medium', className)}>
          <Icon className="w-3 h-3" />
          {label}
        </span>
        <span className="text-[11px] text-muted-foreground">Suggested action: {ACTION_LABEL[result.action]}</span>
      </div>
      <p className="whitespace-pre-wrap">{result.why}</p>
      <TextList title="Evidence checked" items={result.evidence} />
      {result.action === 'revise' && result.replacementComment && (
        <div>
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">Suggested replacement</p>
          <p className="whitespace-pre-wrap rounded bg-background/50 px-2 py-1.5 text-sm">{result.replacementComment}</p>
        </div>
      )}
      {canApply && (
        <div className="flex items-center gap-2 pt-1">
          <Button
            variant="outline"
            size="sm"
            className="text-xs"
            disabled={applied}
            onClick={() => onApply?.(result)}
          >
            {applied
              ? 'Applied'
              : result.action === 'delete'
                ? 'Delete this comment'
                : 'Replace comment text'}
          </Button>
          {!applied && (
            <span className="text-[11px] text-muted-foreground">Updates the draft review</span>
          )}
        </div>
      )}
    </div>
  )
}

function TextList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null
  return (
    <div>
      <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">{title}</p>
      <ul className="list-disc space-y-0.5 pl-4">
        {items.map((item, i) => (
          <li key={i}>{item}</li>
        ))}
      </ul>
    </div>
  )
}

function ExampleFixResultCard({ result }: { result: ExampleFixResult }) {
  return (
    <div className="p-3 space-y-2.5 text-sm">
      <TextList title="Approach" items={result.approach} />
      {result.examplePatch && (
        <div>
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">Example patch</p>
          <MarkdownContent className="[&_pre]:my-0 [&_pre]:bg-background/50">{result.examplePatch}</MarkdownContent>
        </div>
      )}
      <div>
        <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">Why</p>
        <p className="whitespace-pre-wrap">{result.why}</p>
      </div>
      <TextList title="Risks" items={result.risks} />
      <TextList title="Test updates" items={result.testUpdates} />
      <TextList title="Missing context" items={result.missingContext} />
    </div>
  )
}
