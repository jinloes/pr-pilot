import { useCallback, useEffect, useRef, useState } from 'react'
import { PRList } from './components/PRList'
import { ReviewPane } from './components/ReviewPane'
import { type PR } from './bridge/types'

// Dev-mode fixture data — replaced by real bridge messages in production
const DEV_PRS: PR[] = [
  {
    number: 4821,
    title: 'Migrate auth middleware to JWT RS256 — remove legacy HMAC fallback',
    owner: 'acme',
    repo: 'platform',
    author: 'jsmith',
    createdAt: '2026-04-28T14:32:00Z',
    htmlUrl: '#',
    hasDraft: true,
  },
  {
    number: 4819,
    title: 'Add rate limiting to /api/v2/search endpoint',
    owner: 'acme',
    repo: 'platform',
    author: 'mchen',
    createdAt: '2026-04-27T09:11:00Z',
    htmlUrl: '#',
    hasDraft: false,
  },
  {
    number: 312,
    title: 'Switch CI pipeline from CircleCI to GitHub Actions',
    owner: 'acme',
    repo: 'infra',
    author: 'rlopez',
    createdAt: '2026-04-26T17:45:00Z',
    htmlUrl: '#',
    hasDraft: false,
  },
  {
    number: 4815,
    title: 'Refactor session store: extract Redis adapter behind interface',
    owner: 'acme',
    repo: 'platform',
    author: 'jsmith',
    createdAt: '2026-04-25T11:00:00Z',
    htmlUrl: '#',
    hasDraft: true,
  },
  {
    number: 88,
    title: 'Bump jackson-databind 2.17.1 → 2.18.0 (CVE-2024-38817)',
    owner: 'acme',
    repo: 'dependencies',
    author: 'bot',
    createdAt: '2026-04-24T08:00:00Z',
    htmlUrl: '#',
    hasDraft: false,
  },
]

const MIN_LEFT = 180
const MAX_LEFT = 560
const DEFAULT_LEFT = 320
const STORAGE_KEY = 'claude-reviews:divider-width'

function loadSavedWidth(): number {
  const saved = Number(localStorage.getItem(STORAGE_KEY))
  return saved >= MIN_LEFT && saved <= MAX_LEFT ? saved : DEFAULT_LEFT
}

function seedDevData() {
  const w = window as unknown as {
    cefQuery?: unknown
    __handleMessage?: (json: string) => void
  }
  if (w.cefQuery) return
  if (!w.__handleMessage) return
  w.__handleMessage(JSON.stringify({ type: 'prListLoaded', prs: DEV_PRS }))
}

export default function App() {
  const [selectedPR, setSelectedPR] = useState<PR | null>(null)
  const [leftWidth, setLeftWidth] = useState(loadSavedWidth)
  const dragging = useRef(false)
  const dragStartX = useRef(0)
  const dragStartW = useRef(0)
  // Tracks the latest width synchronously so handleMouseUp can persist without stale closure
  const currentWidthRef = useRef(leftWidth)

  useEffect(() => {
    const id = setTimeout(seedDevData, 100)
    return () => clearTimeout(id)
  }, [])

  const handleMouseMove = useCallback((e: MouseEvent) => {
    if (!dragging.current) return
    const delta = e.clientX - dragStartX.current
    const newWidth = Math.max(MIN_LEFT, Math.min(MAX_LEFT, dragStartW.current + delta))
    currentWidthRef.current = newWidth
    setLeftWidth(newWidth)
  }, [])

  const handleMouseUp = useCallback(() => {
    if (!dragging.current) return
    dragging.current = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    localStorage.setItem(STORAGE_KEY, String(currentWidthRef.current))
    document.removeEventListener('mousemove', handleMouseMove)
    document.removeEventListener('mouseup', handleMouseUp)
  }, [handleMouseMove])

  function handleDividerMouseDown(e: React.MouseEvent) {
    dragging.current = true
    dragStartX.current = e.clientX
    dragStartW.current = leftWidth
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
    e.preventDefault()
  }

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      {/* Left column — PR list */}
      <div
        style={{
          width: leftWidth,
          flexShrink: 0,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
      >
        <PRList onSelect={setSelectedPR} />
      </div>

      {/* Draggable divider */}
      <div
        style={{
          width: 5,
          flexShrink: 0,
          background: 'var(--border)',
          cursor: 'col-resize',
          position: 'relative',
        }}
        onMouseDown={handleDividerMouseDown}
        title="Drag to resize"
      >
        {/* Wider invisible hit area */}
        <div
          style={{
            position: 'absolute',
            inset: '0 -3px',
            cursor: 'col-resize',
          }}
          onMouseDown={handleDividerMouseDown}
        />
      </div>

      {/* Right column — review pane */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <ReviewPane pr={selectedPR} />
      </div>
    </div>
  )
}
