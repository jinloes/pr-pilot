import { useEffect, useId, useRef, useState } from 'react'
import {
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Square,
  XCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  formatActivityDuration,
  formatReviewActivityLabel,
  type ReviewActivity,
  type ReviewActivityOutcome,
} from './reviewActivity'

interface ReviewActivityLogProps {
  activity: ReviewActivity
  onCancel?: () => void
}

export function ReviewActivityLog({ activity, onCancel }: ReviewActivityLogProps) {
  const [manualExpansion, setManualExpansion] = useState<{
    runId: number
    outcome: ReviewActivityOutcome
    expanded: boolean
  } | null>(null)
  const [nowMs, setNowMs] = useState(() => Date.now())
  const listRef = useRef<HTMLDivElement>(null)
  const autoScrollRef = useRef(true)
  const contentId = useId()
  const expanded = manualExpansion?.runId === activity.runId
    && manualExpansion.outcome === activity.outcome
    ? manualExpansion.expanded
    : false

  useEffect(() => {
    if (activity.outcome !== 'running') return
    const intervalId = window.setInterval(() => setNowMs(Date.now()), 1000)
    return () => window.clearInterval(intervalId)
  }, [activity.outcome, activity.runId])

  useEffect(() => {
    autoScrollRef.current = true
  }, [activity.runId])

  useEffect(() => {
    const list = listRef.current
    if (expanded && list && autoScrollRef.current) list.scrollTop = list.scrollHeight
  }, [activity.entries.length, expanded])

  if (activity.outcome === 'idle' || activity.entries.length === 0) return null

  const elapsedMs = Math.max(
    0,
    (activity.endedAtMs ?? nowMs) - (activity.startedAtMs ?? nowMs),
  )
  const summary = activitySummary(activity.outcome, elapsedMs)
  const running = activity.outcome === 'running'
  const currentEntry = activity.entries[activity.entries.length - 1]
  const visibleEntries = running ? activity.entries.slice(0, -1) : activity.entries

  return (
    <section
      aria-label="Review generation activity"
      className="w-full overflow-hidden rounded-lg border border-border bg-card shadow-sm"
    >
      <div className="flex flex-col sm:flex-row">
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-2 px-3 py-2 text-left hover:bg-muted/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
          aria-expanded={expanded}
          aria-controls={contentId}
          onClick={() => {
            if (!expanded) autoScrollRef.current = true
            setManualExpansion({
              runId: activity.runId,
              outcome: activity.outcome,
              expanded: !expanded,
            })
          }}
        >
          <span className="sr-only">
            {`${expanded ? 'Hide' : 'Show'} details for ${running ? 'Generating review' : 'Review activity'}`}
          </span>
          {expanded ? (
            <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
          )}
          <span className="min-w-0 flex-1" aria-hidden="true">
            <span className="block text-sm font-medium">
              {running ? 'Generating review' : 'Review activity'}
            </span>
            <span className="block truncate text-xs text-muted-foreground">
              {running ? formatReviewActivityLabel(currentEntry?.message ?? '') : summary}
            </span>
          </span>
          {running && (
            <span className="shrink-0 text-xs tabular-nums text-muted-foreground" aria-hidden="true">
              {formatActivityDuration(elapsedMs)}
            </span>
          )}
        </button>
        {running && onCancel && (
          <div className="flex items-center justify-end px-3 pb-2 sm:pl-0 sm:pb-0">
            <Button variant="outline" size="sm" onClick={onCancel} className="gap-1.5">
              <Square className="h-3 w-3 fill-current" />
              Stop generation
            </Button>
          </div>
        )}
      </div>

      {running && (
        <div
          className="relative h-0.5 overflow-hidden bg-border"
          role="progressbar"
          aria-label="Review generation progress"
          aria-valuetext={formatReviewActivityLabel(currentEntry?.message ?? 'Generating review')}
        >
          <div
            className="review-progress-indicator absolute inset-y-0 w-2/5 rounded-full bg-primary"
          />
        </div>
      )}

      {expanded && (
        <div id={contentId} className="border-t border-border px-3 py-2">
          {visibleEntries.length > 0 ? (
            // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- Scrollable history needs a keyboard focus target.
            <div ref={listRef} tabIndex={0} role="region"
              aria-label="Review activity entries"
              className="max-h-48 space-y-2 overflow-y-auto rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              onScroll={(event) => {
                const list = event.currentTarget
                autoScrollRef.current = list.scrollHeight - list.scrollTop - list.clientHeight <= 4
              }}
            >
              <ol className="space-y-2">
                {visibleEntries.map((entry, index) => {
                  const isLast = !running && index === visibleEntries.length - 1
                  return (
                    <li key={`${entry.startedAtMs}-${index}`} className="flex items-start gap-2 text-xs">
                      <ActivityIcon outcome={isLast ? activity.outcome : 'completed'} />
                      <span className="min-w-0 flex-1 break-words">
                        {formatReviewActivityLabel(entry.message)}
                      </span>
                      <time className="shrink-0 tabular-nums text-muted-foreground">
                        +{formatActivityDuration(
                          entry.startedAtMs - (activity.startedAtMs ?? entry.startedAtMs),
                        )}
                      </time>
                    </li>
                  )
                })}
              </ol>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              Completed steps will appear here as the review progresses.
            </p>
          )}
          <p className="mt-2 border-t border-border pt-2 text-[11px] text-muted-foreground">
            Shows lifecycle and tool names only. Provider output, private reasoning, arguments, and file contents are not displayed.
          </p>
        </div>
      )}
    </section>
  )
}

function ActivityIcon({ outcome }: { outcome: ReviewActivityOutcome }) {
  if (outcome === 'failed') {
    return <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-status-changes" />
  }
  if (outcome === 'cancelled') {
    return <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
  }
  return <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-status-approve" />
}

function activitySummary(outcome: ReviewActivityOutcome, elapsedMs: number): string {
  const duration = formatActivityDuration(elapsedMs)
  if (outcome === 'completed') return `Completed in ${duration}`
  if (outcome === 'failed') return `Failed after ${duration}`
  if (outcome === 'cancelled') return `Cancelled after ${duration}`
  return `Running for ${duration}`
}
