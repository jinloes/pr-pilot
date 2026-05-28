import React, { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Loader2, Send, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import { onHostMessage, sendToHost, type PR } from '../../bridge/types'

interface Message {
  role: 'user' | 'assistant'
  content: string
  isError?: boolean
}

interface Props {
  pr: PR
  selectedContext?: string
  onContextUsed?: () => void
  pendingMessage?: { q: string; ctx: string; id: number }
  onPendingMessageSent?: () => void
  onResizeStart?: (e: React.MouseEvent) => void
}

export function ChatPane({
  pr,
  selectedContext,
  onContextUsed,
  pendingMessage,
  onPendingMessageSent,
  onResizeStart,
}: Props) {
  const [messages, setMessages] = useState<Message[]>([])
  const [streaming, setStreaming] = useState('')
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setMessages([])
    setStreaming('')
    setInput('')
    setBusy(false)
  }, [pr.number, pr.owner, pr.repo])

  useEffect(() => {
    return onHostMessage((msg) => {
      switch (msg.type) {
        case 'chatChunk':
          setStreaming((s) => s + msg.chunk)
          break
        case 'chatResponse':
          setStreaming('')
          setMessages((prev) => [...prev, { role: 'assistant', content: msg.response }])
          setBusy(false)
          break
        case 'chatError':
          setStreaming('')
          setMessages((prev) => [
            ...prev,
            { role: 'assistant', content: msg.message, isError: true },
          ])
          setBusy(false)
          break
        default:
          break
      }
    })
  }, [])

  useEffect(() => {
    if (!pendingMessage || busy) return
    const { q, ctx } = pendingMessage
    setMessages((prev) => [...prev, { role: 'user', content: q }])
    setBusy(true)
    onPendingMessageSent?.()
    sendToHost({ type: 'askClaude', context: ctx, question: q })
  }, [pendingMessage?.id, busy]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages.length, busy])

  function handleClear() {
    setMessages([])
    setStreaming('')
    setBusy(false)
    sendToHost({ type: 'clearChat' })
  }

  function handleSend() {
    const q = input.trim()
    if (!q || busy) return
    const ctx = selectedContext ?? ''
    setMessages((prev) => [...prev, { role: 'user', content: q }])
    setInput('')
    setBusy(true)
    onContextUsed?.()
    sendToHost({ type: 'askClaude', context: ctx, question: q })
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const hasContent = messages.length > 0 || !!streaming || busy

  return (
    <div className="flex flex-col h-full border-t border-border bg-card">
      {/* Resize handle + header */}
      <div
        className={cn(
          'flex items-center justify-between px-3 py-1.5 border-b border-border shrink-0',
          onResizeStart && 'cursor-ns-resize select-none',
        )}
        onMouseDown={onResizeStart}
        title={onResizeStart ? 'Drag to resize' : undefined}
      >
        <span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          Chat
        </span>
        {hasContent && (
          <Button variant="ghost" size="sm" onClick={handleClear} className="h-6 px-2 text-xs">
            Clear
          </Button>
        )}
      </div>

      {/* Messages */}
      {hasContent && (
        <div className="flex-1 overflow-y-auto p-3 space-y-3 min-h-0">
          {messages.map((m, i) => (
            <div key={i} className={cn('flex flex-col gap-1', m.role === 'user' ? 'items-end' : 'items-start')}>
              <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">
                {m.role === 'user' ? 'you' : 'ai'}
              </span>
              <div
                className={cn(
                  'rounded-md px-3 py-2 text-sm max-w-[90%]',
                  m.role === 'user'
                    ? 'bg-primary text-primary-foreground'
                    : m.isError
                      ? 'bg-destructive/20 text-destructive border border-destructive/40'
                      : 'bg-secondary text-secondary-foreground',
                )}
              >
                {m.isError ? (
                  m.content
                ) : (
                  <div className={cn(
                    'prose prose-sm max-w-none [&_code]:font-mono [&_code]:text-xs [&_code]:px-1 [&_code]:rounded [&_p]:my-0.5 [&_ul]:my-1 [&_li]:my-0 [&_pre]:my-1 [&_pre]:p-2 [&_pre]:rounded [&_blockquote]:border-l-2 [&_blockquote]:pl-2 [&_blockquote]:italic',
                    m.role === 'user'
                      ? 'prose-invert [&_code]:bg-primary-foreground/20 [&_pre]:bg-primary-foreground/20 [&_blockquote]:border-primary-foreground/40 [&_a]:text-primary-foreground'
                      : 'prose-invert [&_code]:bg-background/50 [&_pre]:bg-background/50 [&_blockquote]:border-muted-foreground/40 [&_a]:text-primary',
                  )}>
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.content}</ReactMarkdown>
                  </div>
                )}
              </div>
            </div>
          ))}

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

          {streaming && (
            <div className="flex flex-col gap-1 items-start">
              <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">ai</span>
              <div className="bg-secondary text-secondary-foreground rounded-md px-3 py-2 text-sm max-w-[90%]">
                <div className="prose prose-sm prose-invert max-w-none [&_code]:font-mono [&_code]:text-xs [&_code]:bg-background/50 [&_code]:px-1 [&_code]:rounded [&_p]:my-0.5">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{streaming}</ReactMarkdown>
                </div>
                <span className="inline-block w-2 h-3.5 bg-primary animate-pulse ml-0.5 align-text-bottom" />
              </div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>
      )}

      {/* Selected context badge */}
      {selectedContext && (
        <div className="mx-3 mb-1 flex items-center gap-2 rounded border border-border bg-muted/50 px-2 py-1">
          <span className="flex-1 truncate text-xs text-muted-foreground">{selectedContext}</span>
          <Button variant="ghost" size="sm" onClick={onContextUsed} className="h-5 w-5 p-0 shrink-0">
            <X className="w-3 h-3" />
          </Button>
        </div>
      )}

      {/* Input */}
      <div className="flex gap-2 p-3 pt-2 shrink-0">
        <Textarea
          className="min-h-[60px] resize-none text-sm bg-background border-input focus-visible:ring-ring"
          placeholder="Ask about this PR…"
          title="Enter to send · Shift+Enter for newline"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={2}
          disabled={busy}
        />
        <Button
          size="sm"
          onClick={handleSend}
          disabled={busy || !input.trim()}
          className="self-end shrink-0"
          title="Send (Enter)"
          aria-label="Send"
        >
          {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
        </Button>
      </div>
      <p className="px-3 pb-2 text-[11px] text-muted-foreground/60">
        Includes PR body, diff, and review as context · right-click selected text to ask about it
      </p>
    </div>
  )
}
