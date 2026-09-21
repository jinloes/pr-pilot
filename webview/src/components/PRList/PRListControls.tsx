import type { RefObject } from 'react'
import { Info } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import type { PR, PRListStatus, PRSearchScope } from '../../bridge/types'
import { scopeLabel } from './prListLabels'

export type StateFilter = 'open' | 'closed' | 'all'

interface Props {
  prs: PR[]
  repos: string[]
  stateFilter: StateFilter
  searchScope: PRSearchScope
  repoFilter: string
  filter: string
  listStatus: PRListStatus | null
  scopeHelpVisible: boolean
  searchRef: RefObject<HTMLInputElement | null>
  onStateFilter: (value: string) => void
  onSearchScope: (value: string) => void
  onRepoFilter: (value: string) => void
  onFilter: (value: string) => void
  onToggleScopeHelp: () => void
}

export function PRListControls({
  prs,
  repos,
  stateFilter,
  searchScope,
  repoFilter,
  filter,
  listStatus,
  scopeHelpVisible,
  searchRef,
  onStateFilter,
  onSearchScope,
  onRepoFilter,
  onFilter,
  onToggleScopeHelp,
}: Props) {
  return (
    <>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <ToggleGroup
          type="single"
          value={stateFilter}
          onValueChange={onStateFilter}
          className="min-w-0 max-w-full flex-wrap justify-start gap-1"
          aria-label="Pull request state"
        >
          {(['open', 'closed', 'all'] as StateFilter[]).map((state) => (
            <ToggleGroupItem
              key={state}
              value={state}
              className="h-6 px-2 text-[11px] uppercase tracking-wide data-[state=on]:border-primary/40 data-[state=on]:bg-primary/20 data-[state=on]:text-foreground"
            >
              {state}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>

        <div className="flex min-w-32 flex-1 items-center gap-1">
          <Select value={searchScope} onValueChange={onSearchScope}>
            <SelectTrigger
              className="h-7 min-w-0 flex-1 border-border bg-background text-xs"
              aria-label="Pull request scope"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="currentRepo" className="text-xs">Current repo</SelectItem>
              <SelectItem value="reviewRequested" className="text-xs">Review requested</SelectItem>
              <SelectItem value="assigned" className="text-xs">Assigned to me</SelectItem>
              <SelectItem value="authored" className="text-xs">Authored by me</SelectItem>
            </SelectContent>
          </Select>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 shrink-0 gap-1 px-1.5 text-xs text-muted-foreground"
            onClick={onToggleScopeHelp}
            aria-expanded={scopeHelpVisible}
            aria-controls="pr-scope-help"
          >
            <Info className="h-3 w-3" aria-hidden="true" />
            Why?
          </Button>
        </div>
      </div>

      {listStatus && scopeHelpVisible && (
        <ScopeHelpCard
          status={listStatus}
          repoFilter={repoFilter}
          stateFilter={stateFilter}
          filter={filter}
        />
      )}

      {repos.length > 1 && (
        <Select value={repoFilter} onValueChange={onRepoFilter}>
          <SelectTrigger className="h-7 text-xs border-border bg-background" aria-label="Repository">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all" className="text-xs">
              All repos ({prs.length})
            </SelectItem>
            {repos.map((repo) => {
              const count = prs.filter((pr) => `${pr.owner}/${pr.repo}` === repo).length
              return (
                <SelectItem key={repo} value={repo} className="text-xs font-mono">
                  {repo} ({count})
                </SelectItem>
              )
            })}
          </SelectContent>
        </Select>
      )}

      <div className="flex items-center gap-2 rounded border border-border bg-muted/30 px-2 transition-colors focus-within:border-ring">
        <span className="shrink-0 font-mono text-xs text-muted-foreground" aria-hidden="true">/</span>
        <input
          ref={searchRef}
          className="min-w-0 flex-1 bg-transparent py-1.5 text-sm outline-none caret-primary placeholder:italic placeholder:text-muted-foreground"
          placeholder="filter by title, author, #number…"
          aria-label="Filter pull requests"
          value={filter}
          onChange={(event) => onFilter(event.target.value)}
          spellCheck={false}
        />
      </div>
    </>
  )
}

function ScopeHelpCard({
  status,
  repoFilter,
  stateFilter,
  filter,
}: {
  status: PRListStatus
  repoFilter: string
  stateFilter: StateFilter
  filter: string
}) {
  const bullets = visibilityBullets(status, repoFilter, stateFilter, filter)
  return (
    <div
      id="pr-scope-help"
      className="rounded border border-border bg-card px-3 py-2 text-xs leading-relaxed text-muted-foreground"
    >
      <p className="font-semibold text-foreground">Why you may or may not see a pull request</p>
      <ul className="mt-1 list-disc space-y-1 pl-4">
        {bullets.map((bullet) => <li key={bullet}>{bullet}</li>)}
      </ul>
    </div>
  )
}

function visibilityBullets(
  status: PRListStatus,
  repoFilter: string,
  stateFilter: StateFilter,
  filter: string,
): string[] {
  const bullets = [
    status.searchScope === 'currentRepo'
      ? status.currentRepo
        ? `Only pull requests from ${status.currentRepo} are loaded in this scope.`
        : 'The current repository could not be detected, so the list falls back to pull requests you authored.'
      : `This scope only loads ${scopeLabel(status.searchScope).toLowerCase()} pull requests.`,
  ]
  if (stateFilter !== 'all') bullets.push(`State filter is set to ${stateFilter}.`)
  if (repoFilter !== 'all') bullets.push(`Repository filter is narrowing results to ${repoFilter}.`)
  if (filter.trim()) bullets.push(`Text filter is matching "${filter.trim()}".`)
  if (status.limited) bullets.push(`Only the first ${status.resultLimit} matching pull requests are shown.`)
  bullets.push('Pull requests opened from notifications can be pinned outside the current scope.')
  return bullets
}
