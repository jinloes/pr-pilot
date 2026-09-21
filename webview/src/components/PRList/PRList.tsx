import { useEffect, useMemo, useRef, useState } from 'react'
import { RefreshCw, Settings2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { shouldFocusPrFilter } from '@/lib/keyboard'
import { useI18n } from '@/i18n/I18nProvider'
import { onHostMessage, sendToHost, type PR, type PRListStatus, type PRSearchScope, type ProviderReadiness } from '../../bridge/types'
import { PRListControls, type StateFilter } from './PRListControls'
import { PRListItem } from './PRListItem'
import { PRListNotices } from './PRListNotices'
import { ReadinessCoach } from './ReadinessCoach'
import { scopeLabel } from './prListLabels'

interface Props {
  onSelect?: (pr: PR) => boolean | void
  selectedPr?: PR | null
}

const FIRST_SUCCESS_KEY = 'pr-pilot:first-success-coach-shown'

function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
}

function mergeActivatedPr(existing: PR, incoming: PR): PR {
  return {
    ...existing,
    ...incoming,
    reviewStatus: incoming.reviewStatus === 'UNAVAILABLE' && existing.reviewStatus !== 'UNAVAILABLE'
      ? existing.reviewStatus
      : incoming.reviewStatus,
  }
}

export function PRList({ onSelect, selectedPr }: Props) {
  const t = useI18n()
  const [prs, setPRs] = useState<PR[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [repoFilter, setRepoFilter] = useState('all')
  const [stateFilter, setStateFilter] = useState<StateFilter>('open')
  const [searchScope, setSearchScope] = useState<PRSearchScope>('currentRepo')
  const [listStatus, setListStatus] = useState<PRListStatus | null>(null)
  const [providerReadiness, setProviderReadiness] = useState<ProviderReadiness | null>(null)
  const [coachVisible, setCoachVisible] = useState(false)
  const [coachRecoveredSetup, setCoachRecoveredSetup] = useState(false)
  const [scopeHelpVisible, setScopeHelpVisible] = useState(false)
  const [spotlightedKey, setSpotlightedKey] = useState<string | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const sawSetupScreenRef = useRef(false)
  const spotlightedKeyRef = useRef<string | null>(null)

  useEffect(() => {
    setSelected(selectedPr ? prKey(selectedPr) : null)
  }, [selectedPr])

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      if (msg.type === 'prListLoaded') {
        setPRs((previous) => {
          const pinnedKey = spotlightedKeyRef.current
          if (!pinnedKey) return msg.prs
          const pinned = previous.find((pr) => prKey(pr) === pinnedKey)
          if (!pinned) return msg.prs
          const refreshedIndex = msg.prs.findIndex((pr) => prKey(pr) === pinnedKey)
          if (refreshedIndex < 0) return [pinned, ...msg.prs]
          const refreshed = msg.prs.slice()
          refreshed[refreshedIndex] = mergeActivatedPr(pinned, refreshed[refreshedIndex])
          return refreshed
        })
        setRepoFilter(msg.defaultRepo ?? 'all')
        setListStatus(msg.listStatus ?? null)
        if (msg.listStatus) {
          setSearchScope(msg.listStatus.searchScope)
        }
        setProviderReadiness(msg.providerReadiness ?? null)
        setLoading(false)
        setRefreshing(false)
        const shouldCoach = sawSetupScreenRef.current || !localStorage.getItem(FIRST_SUCCESS_KEY)
        if (shouldCoach) {
          setCoachVisible(true)
          setCoachRecoveredSetup(sawSetupScreenRef.current)
          localStorage.setItem(FIRST_SUCCESS_KEY, '1')
        }
        sawSetupScreenRef.current = false
      } else if (msg.type === 'prLoading') {
        setRefreshing(true)
      } else if (msg.type === 'setupRequired') {
        sawSetupScreenRef.current = true
        setLoading(false)
        setRefreshing(false)
      } else if (msg.type === 'prDraftStatusUpdated') {
        setPRs((prev) =>
          prev.map((pr) =>
            pr.number === msg.number && pr.owner === msg.owner && pr.repo === msg.repo
              ? { ...pr, hasReviewDraft: msg.hasReviewDraft }
              : pr,
          ),
        )
      } else if (msg.type === 'activatePR') {
        const key = prKey(msg.pr)
        const nextSpotlightedKey = msg.source === 'notification' ? key : null
        setFilter('')
        setRepoFilter('all')
        setStateFilter('open')
        spotlightedKeyRef.current = nextSpotlightedKey
        setSpotlightedKey(nextSpotlightedKey)
        setPRs((prev) => {
          const index = prev.findIndex((candidate) => prKey(candidate) === key)
          if (index < 0) return [msg.pr, ...prev]
          const next = prev.slice()
          next[index] = mergeActivatedPr(next[index], msg.pr)
          return next
        })
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

  // While a refresh is pending, keep row identities until its context arrives with the results.
  const displayedRepo = !loading && !refreshing
    && searchScope === 'currentRepo' && listStatus?.searchScope === 'currentRepo'
    && listStatus.currentRepo?.trim()
    ? listStatus.currentRepo
    : undefined

  const query = filter.trim().toLowerCase()
  const numberQuery = /^#[0-9]+$/.test(query) ? query.slice(1) : query
  const filtered = prs.filter((pr) => {
    const repoKey = `${pr.owner}/${pr.repo}`
    if (repoFilter !== 'all' && repoKey !== repoFilter) return false
    if (query === '') return true
    return (
      pr.title.toLowerCase().includes(query) ||
      pr.author.toLowerCase().includes(query) ||
      repoKey.toLowerCase().includes(query) ||
      String(pr.number).includes(numberQuery)
    )
  })
  const spotlightedPr = spotlightedKey
    ? prs.find((pr) => prKey(pr) === spotlightedKey)
    : undefined

  function handleSelect(pr: PR) {
    const accepted = onSelect?.(pr)
    if (accepted === false) return
    setSelected(prKey(pr))
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
      if (shouldFocusPrFilter(e) && document.activeElement !== searchRef.current) {
        e.preventDefault()
        searchRef.current?.focus()
      }
      if (e.key === 'Escape' && document.activeElement === searchRef.current) {
        setFilter('')
        searchRef.current?.blur()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <TooltipProvider delayDuration={400}>
      <nav className="flex min-h-0 flex-1 flex-col bg-background border-r border-border" aria-label={t('app.prList')}>
      {coachVisible && (
        <ReadinessCoach
          providerReadiness={providerReadiness}
          recoveredSetup={coachRecoveredSetup}
          onDismiss={() => setCoachVisible(false)}
        />
      )}

      {/* Header */}
      <div className="shrink-0 px-3 pt-3 pb-2 space-y-2 border-b border-border">
        {/* Title row */}
        <div
          className="pr-list-toolbar flex min-w-0 items-center gap-2"
          data-testid="pr-list-toolbar"
        >
          <h1 className="min-w-0 truncate text-xs font-semibold tracking-widest uppercase text-muted-foreground">
            Pull Requests
          </h1>
          <Badge
            variant="outline"
            className={cn(
              'shrink-0 text-[10px] px-1.5 py-0 font-mono',
              filtered.length > 0 ? 'text-primary border-primary/40' : 'text-muted-foreground',
            )}
            title={`${filtered.length} visible of ${prs.length} loaded`}
          >
            {filtered.length}{filtered.length !== prs.length ? `/${prs.length}` : ''}
          </Badge>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => sendToHost({ type: 'openSettings' })}
                className="pr-list-toolbar-action ml-auto h-6 min-w-6 gap-1.5 px-2 text-xs text-muted-foreground hover:text-foreground"
                aria-label="Open PR Pilot settings"
              >
                <Settings2 className="w-3.5 h-3.5" />
                <span className="pr-list-toolbar-label">Settings</span>
              </Button>
            </TooltipTrigger>
            <TooltipContent>Open PR Pilot settings</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => fetchWithFilters()}
                disabled={refreshing}
                className="pr-list-toolbar-action h-6 min-w-6 gap-1.5 px-2 text-xs text-muted-foreground hover:text-foreground"
                aria-label="Refresh pull requests"
              >
                <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
                <span className="pr-list-toolbar-label">Refresh</span>
              </Button>
            </TooltipTrigger>
            <TooltipContent>Refresh pull requests</TooltipContent>
          </Tooltip>
        </div>

        {displayedRepo && (
          <p data-testid="pr-list-repository" className="min-w-0 break-all font-mono text-xs text-muted-foreground">
            {displayedRepo}
          </p>
        )}

        <PRListControls
          prs={prs}
          repos={repos}
          stateFilter={stateFilter}
          searchScope={searchScope}
          repoFilter={repoFilter}
          filter={filter}
          listStatus={listStatus}
          scopeHelpVisible={scopeHelpVisible}
          searchRef={searchRef}
          onStateFilter={(value) => {
            if (value) handleStateFilter(value)
          }}
          onSearchScope={handleSearchScope}
          onRepoFilter={setRepoFilter}
          onFilter={setFilter}
          onToggleScopeHelp={() => setScopeHelpVisible((value) => !value)}
        />

        <PRListNotices
          status={listStatus}
          notificationPinned={spotlightedKey !== null}
          notificationReviewStatusUnavailable={spotlightedPr?.reviewStatus === 'UNAVAILABLE'}
        />
      </div>

      {/* PR list */}
      <ScrollArea className="flex-1">
        <div>
          <div aria-live="polite" aria-atomic="true" className="sr-only">
            {loading ? 'Loading pull requests' : refreshing ? 'Refreshing pull requests' : `${filtered.length} pull requests shown`}
          </div>
          {loading && (
            <div className="flex items-center gap-2 p-5 text-sm text-muted-foreground">
              <span className="font-mono animate-pulse text-primary">█</span>
              loading…
            </div>
          )}

          {!loading && filtered.length === 0 && (
            <div className="flex flex-col items-start gap-3 p-5">
              <p className="text-sm text-muted-foreground">
                {query
                  ? `No results for "${filter}"`
                  : repoFilter !== 'all'
                    ? `No pull requests in ${repoFilter}`
                    : `No pull requests for ${scopeLabel(searchScope).toLowerCase()}`}
              </p>
              {!query && (
                <div className="flex flex-wrap items-center gap-2">
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
                  {stateFilter !== 'all' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => {
                        setStateFilter('all')
                        fetchWithFilters('all', searchScope)
                      }}
                    >
                      Show all states
                    </Button>
                  )}
                  {searchScope !== 'authored' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => handleSearchScope('authored')}
                    >
                      Show authored PRs
                    </Button>
                  )}
                  {searchScope !== 'reviewRequested' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => handleSearchScope('reviewRequested')}
                    >
                      Show review requests
                    </Button>
                  )}
                  {searchScope !== 'assigned' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => handleSearchScope('assigned')}
                    >
                      Show assigned PRs
                    </Button>
                  )}
                  <Button
                    variant="outline"
                    size="sm"
                    className="gap-1.5 text-xs"
                    onClick={() => sendToHost({ type: 'openSettings' })}
                  >
                    <Settings2 className="w-3.5 h-3.5" />
                    Settings
                  </Button>
                </div>
              )}
            </div>
          )}

          <ul
            aria-label="Pull request results"
          >
            {filtered.map((pr) => (
              <li key={prKey(pr)}>
                <PRListItem
                  pr={pr}
                  compactMetadata={displayedRepo === `${pr.owner}/${pr.repo}`}
                  selected={selected === prKey(pr)}
                  spotlighted={spotlightedKey === prKey(pr)}
                  onClick={() => handleSelect(pr)}
                />
              </li>
            ))}
          </ul>
        </div>
      </ScrollArea>
      </nav>
    </TooltipProvider>
  )
}
