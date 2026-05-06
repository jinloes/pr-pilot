import { useEffect, useRef, useState } from 'react'
import { onHostMessage, sendToHost, type PR } from '../../bridge/types'
import './ChatPane.css'

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
}

export function ChatPane({ pr, selectedContext, onContextUsed, pendingMessage, onPendingMessageSent }: Props) {
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

  // Auto-send a message triggered externally (e.g. from right-click context menu).
  useEffect(() => {
    if (!pendingMessage || busy) return
    const { q, ctx } = pendingMessage
    setMessages((prev) => [...prev, { role: 'user', content: q }])
    setBusy(true)
    onPendingMessageSent?.()
    sendToHost({ type: 'askClaude', context: ctx, question: q })
  }, [pendingMessage?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages.length, streaming, busy])

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

  // Show message area whenever there's content or Claude is working.
  const hasContent = messages.length > 0 || !!streaming || busy

  return (
    <div className="chat-pane">
      <div className="chat-pane__header">
        <span className="chat-pane__title">◈ Ask Claude</span>
        {hasContent && (
          <button className="chat-pane__clear-btn" onClick={handleClear} title="Clear conversation">
            ✕ clear
          </button>
        )}
      </div>
      {hasContent && (
        <div className="chat-pane__messages">
          {messages.map((m, i) => (
            <div
              key={i}
              className={`chat-pane__bubble chat-pane__bubble--${m.role}${m.isError ? ' chat-pane__bubble--error' : ''}`}
            >
              <span className="chat-pane__bubble-label">
                {m.role === 'user' ? 'you' : 'claude'}
              </span>
              <p className="chat-pane__bubble-body">{m.content}</p>
            </div>
          ))}
          {busy && !streaming && (
            <div className="chat-pane__bubble chat-pane__bubble--assistant">
              <span className="chat-pane__bubble-label">claude</span>
              <p className="chat-pane__bubble-body chat-pane__bubble-body--waiting">
                <span className="chat-pane__dot" style={{ animationDelay: '0ms' }}>●</span>
                <span className="chat-pane__dot" style={{ animationDelay: '200ms' }}>●</span>
                <span className="chat-pane__dot" style={{ animationDelay: '400ms' }}>●</span>
              </p>
            </div>
          )}
          {streaming && (
            <div className="chat-pane__bubble chat-pane__bubble--assistant">
              <span className="chat-pane__bubble-label">claude</span>
              <p className="chat-pane__bubble-body">
                {streaming}
                <span className="chat-pane__cursor">█</span>
              </p>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>
      )}
      {selectedContext && (
        <div className="chat-pane__context-badge">
          <span className="chat-pane__context-text">◎ {selectedContext}</span>
          <button className="chat-pane__context-dismiss" onClick={onContextUsed} title="Clear selection">
            ✕
          </button>
        </div>
      )}
      <div className="chat-pane__input-row">
        <textarea
          className="chat-pane__input"
          placeholder="Ask about this PR…"
          title="Enter to send · Shift+Enter for newline"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={2}
          disabled={busy}
        />
        <button
          className="chat-pane__send-btn"
          onClick={handleSend}
          disabled={busy || !input.trim()}
          title="Send (Enter)"
        >
          {busy ? '…' : '↵'}
        </button>
      </div>
      <p className="chat-pane__hint">
        PR body · diff · review included as context · right-click any selection to ask what it does
      </p>
    </div>
  )
}
