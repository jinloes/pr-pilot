import { cn } from '@/lib/utils'
import type { PR } from '../../bridge/types'
import { PRStatusBadges } from './PRStatusBadges'

interface Props {
  pr: PR
  selected: boolean
  spotlighted: boolean
  compactMetadata?: boolean
  onClick: () => void
}

function formatCreatedAt(createdAt?: string): string {
  if (!createdAt) return ''
  const date = new Date(createdAt)
  if (Number.isNaN(date.getTime())) return createdAt.slice(0, 10)
  const deltaMs = Date.now() - date.getTime()
  const deltaDays = Math.floor(deltaMs / (24 * 60 * 60 * 1000))
  if (deltaDays >= 0 && deltaDays < 7) {
    if (deltaDays === 0) return 'today'
    if (deltaDays === 1) return '1 day ago'
    return `${deltaDays} days ago`
  }
  return date.toLocaleDateString()
}

export function PRListItem({ pr, selected, spotlighted, compactMetadata = false, onClick }: Props) {
  const date = formatCreatedAt(pr.createdAt)

  return (
    <button
      className={cn(
        'pr-item block w-full min-w-0 border-b border-border px-3 py-2.5 text-left transition-colors',
        'hover:bg-accent/50 focus-visible:relative focus-visible:z-10 focus-visible:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring',
        selected && 'border-l-2 border-l-primary bg-accent/40 pl-2.5',
      )}
      onClick={onClick}
      aria-current={selected ? 'page' : undefined}
    >
      <span className="line-clamp-2 text-sm leading-snug text-foreground">{pr.title}</span>
      {compactMetadata ? (
        <span className="sr-only">{pr.owner}/{pr.repo}</span>
      ) : (
        <span className="mt-1 flex min-w-0 flex-wrap items-baseline gap-x-1.5 gap-y-0.5 font-mono text-xs text-muted-foreground">
          <span className="min-w-0 break-all">{pr.owner}/{pr.repo}</span>
          <span className="shrink-0">#{pr.number}</span>
        </span>
      )}
      <span className={cn(
        'flex min-w-0 flex-wrap items-baseline gap-x-1 gap-y-0.5 text-xs text-muted-foreground',
        compactMetadata ? 'mt-1' : 'mt-0.5',
      )}>
        {compactMetadata && (
          <>
            <span className="break-all font-mono">#{pr.number}</span>
            <span aria-hidden="true">·</span>
          </>
        )}
        <span className="min-w-0 break-all">@{pr.author}</span>
        {date && (
          <>
            <span aria-hidden="true">·</span>
            <span>{date}</span>
          </>
        )}
      </span>
      {(pr.isDraft
        || pr.hasReviewDraft
        || spotlighted
        || pr.reviewStatus === 'REVIEWED'
        || pr.reviewStatus === 'UPDATED_SINCE_REVIEW') && (
        <span className="mt-1.5 block">
          <PRStatusBadges pr={pr} spotlighted={spotlighted} />
        </span>
      )}
    </button>
  )
}
