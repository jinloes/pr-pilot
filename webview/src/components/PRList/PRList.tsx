import { useEffect, useMemo, useRef, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { cn } from '@/lib/utils'
import { onHostMessage, sendToHost, type PR } from '../../bridge/types'

interface Props {
  onSelect?: (pr: PR) => void
}

type StateFilter = 'open' | 'closed' | 'all'

export function PRList({ onSelect }: Props) {
  const [prs, setPRs] = useState<PR[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)
  const [filter, setFilter] = useState('')
  const [repoFilter, setRepoFilter] = useState('all')
  const [stateFilter, setStateFilter] = useState<StateFilter>('open')
  const [assignedToMe, setAssignedToMe] = useState(false)
  const [reviewRequested, setReviewRequested] = useState(false)
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

  useEffect(() => {
    if (repoFilter !== 'all' && !repos.includes(repoFilter)) setRepoFilter('all')
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

  function fetchWithFilters(
    s: StateFilter = stateFilter,
    ami: boolean = assignedToMe,
    rr: boolean = reviewRequested,
  ) {
    setRefreshing(true)
    sendToHost({ type: 'refreshPRs', state: s, assignedToMe: ami, reviewRequested: rr })
  }

  function handleStateFilter(val: string) {
    if (!val) return
    const s = val as StateFilter
    setStateFilter(s)
    fetchWithFilters(s, assignedToMe, reviewRequested)
  }

  function handleAssignedToMe() {
    const next = !assignedToMe
    setAssignedToMe(next)
    fetchWithFilters(stateFilter, next, reviewRequested)
  }

  function handleReviewRequested() {
    const next = !reviewRequested
    setReviewRequested(next)
    fetchWithFilters(stateFilter, assignedToMe, next)
  }

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
    <div className="flex flex-col h-full bg-background border-r border-border">
      {/* Header */}
      <div className="shrink-0 px-3 pt-3 pb-2 space-y-2 border-b border-border">
        {/* Title row */}
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold tracking-widest uppercase text-muted-foreground">
            Pull Requests
          </span>
          <Badge
            variant="outline"
            className={cn(
              'text-[10px] px-1.5 py-0 font-mono',
              prs.length > 0 ? 'text-primary border-primary/40' : 'text-muted-foreground',
            )}
          >
            {prs.length}
          </Badge>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => fetchWithFilters()}
            disabled={refreshing}
            className="ml-auto h-6 px-2 text-xs text-muted-foreground hover:text-foreground gap-1.5"
            title="Refresh pull requests"
            aria-label="Refresh pull requests"
          >
            <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
            Refresh
          </Button>
        </div>

        {/* State filter + role filters */}
        <div className="flex items-center gap-2 flex-wrap">
          <ToggleGroup
            type="single"
            value={stateFilter}
            onValueChange={handleStateFilter}
            className="gap-1"
          >
            {(['open', 'closed', 'all'] as StateFilter[]).map((s) => (
              <ToggleGroupItem
                key={s}
                value={s}
                className="h-6 px-2 text-[11px] tracking-wide uppercase data-[state=on]:bg-primary/20 data-[state=on]:text-primary data-[state=on]:border-primary/40"
              >
                {s}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>

          <Separator orientation="vertical" className="h-4" />

          <button
            onClick={handleAssignedToMe}
            aria-pressed={assignedToMe}
            className={cn(
              'h-6 px-2 rounded text-[11px] tracking-wide uppercase border transition-colors',
              assignedToMe
                ? 'bg-primary/20 text-primary border-primary/40'
                : 'text-muted-foreground border-border hover:text-foreground hover:border-border/80',
            )}
          >
            assigned
          </button>
          <button
            onClick={handleReviewRequested}
            aria-pressed={reviewRequested}
            className={cn(
              'h-6 px-2 rounded text-[11px] tracking-wide uppercase border transition-colors',
              reviewRequested
                ? 'bg-primary/20 text-primary border-primary/40'
                : 'text-muted-foreground border-border hover:text-foreground hover:border-border/80',
            )}
          >
            review req
          </button>
        </div>

        {/* Repo filter */}
        {repos.length > 0 && (
          <Select value={repoFilter} onValueChange={setRepoFilter}>
            <SelectTrigger className="h-7 text-xs border-border bg-background">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all" className="text-xs">
                All repos ({prs.length})
              </SelectItem>
              {repos.map((r) => {
                const count = prs.filter((pr) => `${pr.owner}/${pr.repo}` === r).length
                return (
                  <SelectItem key={r} value={r} className="text-xs font-mono">
                    {r} ({count})
                  </SelectItem>
                )
              })}
            </SelectContent>
          </Select>
        )}

        {/* Search */}
        <div className="flex items-center gap-2 rounded border border-border bg-muted/30 px-2 focus-within:border-ring transition-colors">
          <span className="text-xs text-muted-foreground shrink-0 font-mono">/</span>
          <input
            ref={searchRef}
            className="flex-1 bg-transparent text-sm py-1.5 outline-none placeholder:text-muted-foreground placeholder:italic caret-primary"
            placeholder="filter by title, author, #number…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            spellCheck={false}
          />
        </div>
      </div>

      {/* PR list */}
      <ScrollArea className="flex-1">
        <div
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
            <div className="flex items-center gap-2 p-5 text-sm text-muted-foreground">
              <span className="font-mono animate-pulse text-primary">█</span>
              loading…
            </div>
          )}

          {!loading && filtered.length === 0 && (
            <div className="flex flex-col items-start gap-3 p-5">
              <p className="text-sm text-muted-foreground">
                {filter
                  ? `No results for "${filter}"`
                  : repoFilter !== 'all'
                    ? `No pull requests in ${repoFilter}`
                    : 'No pull requests'}
              </p>
              {!filter && (
                <Button
                  variant="outline"
                  size="sm"
                  className="gap-1.5 text-xs"
                  onClick={() => fetchWithFilters()}
                  disabled={refreshing}
                >
                  <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
                  Refresh
                </Button>
              )}
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
      </ScrollArea>
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
      className={cn(
        'pr-item w-full flex items-stretch border-b border-border text-left transition-colors',
        'hover:bg-accent/50 focus-visible:outline-none focus-visible:bg-accent/50',
        selected && 'bg-accent/40 border-l-2 border-l-primary',
      )}
      style={{ animationDelay: `${Math.min(index * 28, 140)}ms` }}
      onClick={onClick}
      aria-pressed={selected}
    >
      {/* Number gutter */}
      <div className="w-14 shrink-0 flex flex-col items-end justify-start px-3 py-2.5 border-r border-border gap-0.5">
        <span className={cn('text-xs font-mono font-medium', selected ? 'text-primary' : 'text-muted-foreground')}>
          #{pr.number}
        </span>
        {pr.hasDraft && (
          <span className="text-[8px] font-bold tracking-wider text-sky-400 leading-none">
            DRAFT
          </span>
        )}
      </div>

      {/* Body */}
      <div className="flex-1 min-w-0 px-2.5 py-2.5 flex flex-col gap-0.5">
        <div className="flex items-baseline gap-1.5 min-w-0">
          <span className="text-sm text-foreground truncate flex-1 leading-snug">{pr.title}</span>
        </div>
        <span className="font-mono truncate text-[11px] text-sky-400/80">
          {pr.owner}/{pr.repo}
        </span>
        <div className="flex items-center gap-1 text-[11px]">
          <span className="text-emerald-400">@{pr.author}</span>
          {date && (
            <>
              <span className="text-muted-foreground">·</span>
              <span className="text-slate-400">{date}</span>
            </>
          )}
        </div>
      </div>
    </button>
  )
}
