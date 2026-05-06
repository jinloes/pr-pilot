import { useEffect, useMemo, useRef, useState } from 'react'
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
  const [repoFilter, setRepoFilter] = useState('all')
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      if (msg.type === 'prListLoaded') {
        setPRs(msg.prs)
        setRepoFilter(msg.defaultRepo ?? 'all')
        setLoading(false)
        setRefreshing(false)
      } else if (msg.type === 'prLoading') {
        setRefreshing(true)
      } else if (msg.type === 'prDraftStatusUpdated') {
        setPRs((prev) =>
          prev.map((pr) =>
            pr.number === msg.number && pr.owner === msg.owner && pr.repo === msg.repo
              ? { ...pr, hasDraft: msg.hasDraft }
              : pr,
          ),
        )
      }
    })
    return cleanup
  }, [])

  const repos = useMemo(() => {
    const seen = new Set<string>()
    const list: string[] = []
    for (const pr of prs) {
      const key = `${pr.owner}/${pr.repo}`
      if (!seen.has(key)) {
        seen.add(key)
        list.push(key)
      }
    }
    return list.sort()
  }, [prs])

  // Reset repo filter when it no longer appears in the new list
  useEffect(() => {
    if (repoFilter !== 'all' && !repos.includes(repoFilter)) {
      setRepoFilter('all')
    }
  }, [repos, repoFilter])

  const filtered = prs.filter((pr) => {
    const repoKey = `${pr.owner}/${pr.repo}`
    if (repoFilter !== 'all' && repoKey !== repoFilter) return false
    if (filter === '') return true
    const q = filter.toLowerCase()
    return (
      pr.title.toLowerCase().includes(q) ||
      pr.author.toLowerCase().includes(q) ||
      repoKey.toLowerCase().includes(q) ||
      String(pr.number).includes(q)
    )
  })

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
          <button
            className={`pr-list__refresh-btn${refreshing ? ' pr-list__refresh-btn--spinning' : ''}`}
            onClick={handleRefresh}
            title="Refresh pull requests"
            aria-label="Refresh pull requests"
          >
            <span className="pr-list__refresh-icon">↻</span>
            <span className="pr-list__refresh-text">Refresh</span>
          </button>
        </div>

        {repos.length > 0 && (
          <div className="pr-list__repo-row">
            <span className="pr-list__repo-label">repo</span>
            <select
              className="pr-list__repo-select"
              value={repoFilter}
              onChange={(e) => setRepoFilter(e.target.value)}
            >
              <option value="all">all repos ({prs.length})</option>
              {repos.map((r) => {
                const count = prs.filter((pr) => `${pr.owner}/${pr.repo}` === r).length
                return (
                  <option key={r} value={r}>
                    {r} ({count})
                  </option>
                )
              })}
            </select>
          </div>
        )}

        <div className="pr-list__search-row">
          <i className="pr-list__search-icon">/</i>
          <input
            ref={searchRef}
            className="pr-list__search"
            placeholder="filter by title, author, #number…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            spellCheck={false}
          />
        </div>
      </div>

      <div
        className="pr-list__items"
        onKeyDown={(e) => {
          if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
          e.preventDefault()
          const buttons = Array.from(
            (e.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('.pr-item'),
          )
          const activeIdx = buttons.indexOf(document.activeElement as HTMLButtonElement)
          const nextIdx =
            e.key === 'ArrowDown'
              ? Math.min(activeIdx + 1, buttons.length - 1)
              : Math.max(activeIdx - 1, 0)
          buttons[nextIdx]?.focus()
        }}
      >
        {loading && (
          <div className="pr-list__state">
            <span className="pr-list__cursor">█</span>
            loading…
          </div>
        )}

        {!loading && filtered.length === 0 && (
          <div className="pr-list__state">
            {filter
              ? `no results for "${filter}"`
              : repoFilter !== 'all'
                ? `no pull requests in ${repoFilter}`
                : 'no pull requests'}
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
      style={{ animationDelay: `${Math.min(index * 28, 140)}ms` }}
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
