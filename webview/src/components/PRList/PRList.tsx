import { useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircle2, Circle, Copy, ExternalLink, RefreshCw, Settings2, TriangleAlert } from 'lucide-react'
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
import { onHostMessage, sendToHost, type PR, type PRListStatus, type PRSearchScope } from '../../bridge/types'

interface Props {
  onSelect?: (pr: PR) => void
}

type StateFilter = 'open' | 'closed' | 'all'
type SetupReason = 'gh_not_installed' | 'gh_not_authenticated' | 'load_failed'

function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
}

export function PRList({ onSelect }: Props) {
  const [prs, setPRs] = useState<PR[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [repoFilter, setRepoFilter] = useState('all')
  const [stateFilter, setStateFilter] = useState<StateFilter>('open')
  const [searchScope, setSearchScope] = useState<PRSearchScope>('currentRepo')
  const [listStatus, setListStatus] = useState<PRListStatus | null>(null)
  const [setupRequired, setSetupRequired] = useState<{ reason: SetupReason; detail: string } | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      if (msg.type === 'prListLoaded') {
        setPRs(msg.prs)
        setRepoFilter(msg.defaultRepo ?? 'all')
        if (msg.listStatus) {
          setListStatus(msg.listStatus)
          setSearchScope(msg.listStatus.searchScope)
        }
        setLoading(false)
        setRefreshing(false)
        setSetupRequired(null)
      } else if (msg.type === 'prLoading') {
        setRefreshing(true)
      } else if (msg.type === 'setupRequired') {
        setSetupRequired({ reason: msg.reason, detail: msg.detail })
        setLoading(false)
        setRefreshing(false)
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
    setSelected(prKey(pr))
    onSelect?.(pr)
    sendToHost({ type: 'selectPR', number: pr.number, owner: pr.owner, repo: pr.repo })
  }

  function fetchWithFilters(
    s: StateFilter = stateFilter,
    scope: PRSearchScope = searchScope,
  ) {
    setRefreshing(true)
    sendToHost({ type: 'refreshPRs', state: s, searchScope: scope })
  }

  function handleStateFilter(val: string) {
    // ToggleGroup fires onValueChange('') when the active item is clicked again;
    // keep the current filter rather than leaving nothing selected.
    if (!val) return
    const s = val as StateFilter
    setStateFilter(s)
    fetchWithFilters(s, searchScope)
  }

  function handleSearchScope(scope: string) {
    const next = scope as PRSearchScope
    setSearchScope(next)
    fetchWithFilters(stateFilter, next)
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
      {/* Setup-required onboarding screen — shown instead of the full list */}
      {setupRequired && (
        <SetupScreen
          reason={setupRequired.reason}
          detail={setupRequired.detail}
          refreshing={refreshing}
          onRefresh={() => fetchWithFilters()}
        />
      )}

      {/* Normal list UI — hidden while setup screen is active */}
      {!setupRequired && (
        <>
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
              filtered.length > 0 ? 'text-primary border-primary/40' : 'text-muted-foreground',
            )}
            title={`${filtered.length} visible of ${prs.length} loaded`}
          >
            {filtered.length}{filtered.length !== prs.length ? `/${prs.length}` : ''}
          </Badge>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => sendToHost({ type: 'openSettings' })}
            className="ml-auto h-6 px-2 text-xs text-muted-foreground hover:text-foreground gap-1.5"
            title="Open PR Pilot settings"
            aria-label="Open PR Pilot settings"
          >
            <Settings2 className="w-3.5 h-3.5" />
            Settings
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => fetchWithFilters()}
            disabled={refreshing}
            className="h-6 px-2 text-xs text-muted-foreground hover:text-foreground gap-1.5"
            title="Refresh pull requests"
            aria-label="Refresh pull requests"
          >
            <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
            Refresh
          </Button>
        </div>

        {/* State filter + search scope */}
        <div className="flex items-center gap-2 flex-wrap">
          <ToggleGroup
            type="single"
            value={stateFilter}
            onValueChange={(val) => {
              // Prevent visual deselection when clicking the already-active filter
              if (val) handleStateFilter(val)
            }}
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

          <Select value={searchScope} onValueChange={handleSearchScope}>
            <SelectTrigger className="h-7 min-w-40 flex-1 text-xs border-border bg-background">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="currentRepo" className="text-xs">Current repo</SelectItem>
              <SelectItem value="reviewRequested" className="text-xs">Review requested</SelectItem>
              <SelectItem value="assigned" className="text-xs">Assigned to me</SelectItem>
              <SelectItem value="authored" className="text-xs">Authored by me</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {listStatus && (
          <div className="rounded border border-border bg-muted/25 px-2 py-1 text-[11px] text-muted-foreground leading-relaxed">
            <span>{scopeDescription(listStatus)}</span>
            {listStatus.limited && (
              <span className="text-status-suggestion"> · showing first {listStatus.resultLimit} results</span>
            )}
          </div>
        )}

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
            aria-label="Filter pull requests"
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
                    : `No pull requests for ${scopeLabel(searchScope).toLowerCase()}`}
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

          {filtered.map((pr) => (
            <PRItem
              key={prKey(pr)}
              pr={pr}
              selected={selected === prKey(pr)}
              onClick={() => handleSelect(pr)}
            />
          ))}
        </div>
      </ScrollArea>
      </>
      )}
    </div>
  )
}

// ── PRItem ──────────────────────────────────────────────────────────────────
interface ItemProps {
  pr: PR
  selected: boolean
  onClick: () => void
}

function PRItem({ pr, selected, onClick }: ItemProps) {
  const date = pr.createdAt?.substring(0, 10) ?? ''

  return (
    <button
      className={cn(
        'pr-item w-full flex items-stretch border-b border-border text-left transition-colors',
        'hover:bg-accent/50 focus-visible:outline-none focus-visible:bg-accent/50',
        selected && 'bg-accent/40 border-l-2 border-l-primary',
      )}
      onClick={onClick}
      aria-pressed={selected}
    >
      {/* Number gutter */}
      <div className="w-14 shrink-0 flex flex-col items-end justify-start px-3 py-2.5 border-r border-border gap-0.5">
        <span className={cn('text-xs font-mono font-medium', selected ? 'text-primary' : 'text-muted-foreground')}>
          #{pr.number}
        </span>
        {pr.hasDraft && (
          <span className="text-[8px] font-bold tracking-wider text-[hsl(var(--status-comment))] leading-none">
            DRAFT
          </span>
        )}
      </div>

      {/* Body */}
      <div className="flex-1 min-w-0 px-2.5 py-2.5 flex flex-col gap-0.5">
        <div className="flex items-baseline gap-1.5 min-w-0">
          <span className="text-sm text-foreground truncate flex-1 leading-snug">{pr.title}</span>
        </div>
        <span className="font-mono truncate text-[11px] text-muted-foreground/80">
          {pr.owner}/{pr.repo}
        </span>
        <div className="flex items-center gap-1 text-[11px]">
          <span className="text-[hsl(var(--status-approve))]">@{pr.author}</span>
          {date && (
            <>
              <span className="text-muted-foreground">·</span>
              <span className="text-muted-foreground/70">{date}</span>
            </>
          )}
        </div>
      </div>
    </button>
  )
}

// ── SetupScreen ──────────────────────────────────────────────────────────────

interface SetupScreenProps {
  reason: 'gh_not_installed' | 'gh_not_authenticated' | 'load_failed'
  detail: string
  refreshing: boolean
  onRefresh: () => void
}

function SetupScreen({ reason, detail, refreshing, onRefresh }: SetupScreenProps) {
  const title = reason === 'load_failed' ? 'Could not load pull requests' : 'GitHub not connected'
  const steps = setupSteps(reason)
  const [copyLabel, setCopyLabel] = useState<'copy' | 'copied' | 'failed'>('copy')

  async function handleCopyAuthCommand() {
    try {
      await navigator.clipboard.writeText('gh auth login')
      setCopyLabel('copied')
      window.setTimeout(() => setCopyLabel('copy'), 1200)
    } catch {
      setCopyLabel('failed')
      window.setTimeout(() => setCopyLabel('copy'), 1200)
    }
  }

  return (
    <div className="flex flex-col h-full items-center justify-center gap-5 px-6 text-center">
      <TriangleAlert className="w-10 h-10 text-status-suggestion shrink-0" />
      <div className="flex flex-col gap-2">
        <p className="text-sm font-semibold text-foreground">{title}</p>
        <p className="text-xs text-muted-foreground leading-relaxed">{detail}</p>
      </div>
      <div className="w-full max-w-72 rounded border border-border bg-card text-left">
        {steps.map((step) => (
          <div key={step.label} className="flex items-start gap-2 border-b border-border last:border-b-0 px-3 py-2">
            {step.done ? (
              <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 text-status-approve shrink-0" />
            ) : (
              <Circle className="mt-0.5 h-3.5 w-3.5 text-muted-foreground shrink-0" />
            )}
            <div className="min-w-0">
              <p className="text-xs text-foreground">{step.label}</p>
              <p className="text-[11px] text-muted-foreground leading-snug">{step.detail}</p>
            </div>
          </div>
        ))}
      </div>
      <div className="w-full max-w-72 rounded border border-border bg-muted/20 px-3 py-2.5 text-left">
        <p className="text-[11px] font-mono text-foreground">gh auth login</p>
        <p className="mt-1 text-[11px] text-muted-foreground">
          Run in a terminal if CLI auth is missing.
        </p>
        <Button
          variant="ghost"
          size="sm"
          className="mt-2 h-6 px-2 text-[11px] gap-1.5"
          onClick={() => {
            void handleCopyAuthCommand()
          }}
        >
          <Copy className="w-3 h-3" />
          {copyLabel === 'copied' ? 'Copied' : copyLabel === 'failed' ? 'Copy failed' : 'Copy command'}
        </Button>
      </div>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <Button
          variant="outline"
          size="sm"
          className="gap-1.5 text-xs"
          onClick={() => sendToHost({ type: 'openSettings' })}
        >
          <Settings2 className="w-3.5 h-3.5" />
          Open Settings
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="gap-1.5 text-xs"
          onClick={() => sendToHost({ type: 'openUrl', url: 'https://cli.github.com/manual/gh_auth_login' })}
        >
          <ExternalLink className="w-3.5 h-3.5" />
          Auth Guide
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="gap-1.5 text-xs"
          onClick={onRefresh}
          disabled={refreshing}
        >
          <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
          Refresh
        </Button>
      </div>
    </div>
  )
}

function scopeLabel(scope: PRSearchScope): string {
  switch (scope) {
    case 'currentRepo': return 'Current repo'
    case 'reviewRequested': return 'Review requested'
    case 'assigned': return 'Assigned to me'
    case 'authored': return 'Authored by me'
  }
}

function scopeDescription(status: PRListStatus): string {
  if (status.searchScope === 'currentRepo') {
    return status.currentRepo ? `Searching ${status.currentRepo}` : 'Current repo was not detected; showing authored PRs'
  }
  return `Searching ${scopeLabel(status.searchScope).toLowerCase()} PRs`
}

function setupSteps(reason: SetupReason): Array<{ label: string; detail: string; done: boolean }> {
  return [
    {
      label: 'Install GitHub CLI',
      detail: 'PR Pilot uses gh for GitHub authentication and PR access.',
      done: reason !== 'gh_not_installed',
    },
    {
      label: 'Authenticate GitHub',
      detail: 'Run gh auth login for github.com or your Enterprise host.',
      done: reason === 'load_failed',
    },
    {
      label: 'Load pull requests',
      detail: 'Refresh after setup; choose a search scope if the list is empty.',
      done: false,
    },
  ]
}
