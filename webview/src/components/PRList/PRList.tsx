import { useEffect, useRef, useState } from 'react'
import { onHostMessage, sendToHost, type PR } from '../../bridge/types'
import './PRList.css'

interface Props {
  onSelect?: (pr: PR) => void
}

export function PRList({ onSelect }: Props) {
  const [prs, setPRs] = useState<PR[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)
  const [filter, setFilter] = useState('')
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      if (msg.type === 'prListLoaded') {
        setPRs(msg.prs)
        setLoading(false)
        setRefreshing(false)
      } else if (msg.type === 'prLoading') {
        setRefreshing(true)
      }
    })
    return cleanup
  }, [])

  const filtered = prs.filter(
    (pr) =>
      filter === '' ||
      pr.title.toLowerCase().includes(filter.toLowerCase()) ||
      pr.author.toLowerCase().includes(filter.toLowerCase()) ||
      `${pr.owner}/${pr.repo}`.toLowerCase().includes(filter.toLowerCase()) ||
      String(pr.number).includes(filter),
  )

  function handleSelect(pr: PR) {
    setSelected(pr.number)
    onSelect?.(pr)
    sendToHost({ type: 'selectPR', number: pr.number, owner: pr.owner, repo: pr.repo })
  }

  function handleRefresh() {
    setRefreshing(true)
    sendToHost({ type: 'refreshPRs' })
  }

  // Keyboard shortcut: / to focus search
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === '/' && document.activeElement !== searchRef.current) {
        e.preventDefault()
        searchRef.current?.focus()
      }
      if (e.key === 'Escape') {
        setFilter('')
        searchRef.current?.blur()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <div className="pr-list">
      <div className="pr-list__header">
        <div className="pr-list__title-row">
          <span className="pr-list__sigil">◈</span>
          <span className="pr-list__label">Pull Requests</span>
          <span className={`pr-list__badge${prs.length > 0 ? ' pr-list__badge--has-items' : ''}`}>
            {prs.length}
          </span>
        </div>

        <div className="pr-list__search-row">
          <i className="pr-list__search-icon">/</i>
          <input
            ref={searchRef}
            className="pr-list__search"
            placeholder="filter by title, author, repo…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            spellCheck={false}
          />
          <button
            className={`pr-list__refresh${refreshing ? ' pr-list__refresh--spinning' : ''}`}
            onClick={handleRefresh}
            title="Refresh"
            aria-label="Refresh pull requests"
          >
            ↻
          </button>
        </div>
      </div>

      <div className="pr-list__items">
        {loading && (
          <div className="pr-list__state">
            <span className="pr-list__cursor">█</span>
            loading…
          </div>
        )}

        {!loading && filtered.length === 0 && (
          <div className="pr-list__state">
            {filter ? `no results for "${filter}"` : 'no pull requests'}
          </div>
        )}

        {filtered.map((pr, i) => (
          <PRItem
            key={pr.number}
            pr={pr}
            selected={selected === pr.number}
            index={i}
            onClick={() => handleSelect(pr)}
          />
        ))}
      </div>
    </div>
  )
}

// ── PRItem ──────────────────────────────────────────────────────────────────

interface ItemProps {
  pr: PR
  selected: boolean
  index: number
  onClick: () => void
}

function PRItem({ pr, selected, index, onClick }: ItemProps) {
  const date = pr.createdAt?.substring(0, 10) ?? ''

  return (
    <button
      className={`pr-item${selected ? ' pr-item--selected' : ''}`}
      onClick={onClick}
      style={{ animationDelay: `${Math.min(index * 28, 400)}ms` }}
      aria-pressed={selected}
    >
      <div className="pr-item__gutter">
        <span className="pr-item__number">#{pr.number}</span>
      </div>

      <div className="pr-item__body">
        <div className="pr-item__top">
          <span className="pr-item__title">{pr.title}</span>
          {pr.hasDraft && <span className="pr-item__draft">DRAFT</span>}
        </div>

        <div className="pr-item__meta">
          <span className="pr-item__repo">
            {pr.owner}/{pr.repo}
          </span>
          <span className="pr-item__sep">·</span>
          <span className="pr-item__author">@{pr.author}</span>
          {date && (
            <>
              <span className="pr-item__sep">·</span>
              <span className="pr-item__date">{date}</span>
            </>
          )}
        </div>
      </div>
    </button>
  )
}
