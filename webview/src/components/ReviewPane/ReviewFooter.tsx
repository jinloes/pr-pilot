import { useEffect, useState } from 'react'
import {
  Check,
  CloudUpload,
  Loader2,
  MoreHorizontal,
  RotateCcw,
  Send,
  Trash2,
} from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useI18n } from '@/i18n/I18nProvider'
import type { ReviewQualityReport } from '@/lib/reviewQuality'
import { FALLBACK_REVIEW_BODY, type PublishedBodySections } from './publishBody'
import type { PaneState, Verdict } from './reviewState'

/** Matches the app's narrow layout breakpoint (`sm`). */
const NARROW_FOOTER_MAX_WIDTH = 640

interface ReviewFooterProps {
  state: PaneState
  saving: boolean
  autosaveDirty: boolean
  submitting: boolean
  deleting: boolean
  onSave: () => void
  onSubmit: (verdict: Verdict, comment?: string) => void
  onRegenerate: () => void
  onDelete: () => void
  onRunQualityCheck: () => void
  inlineCommentCount: number
  publishSections: PublishedBodySections
  commentsMovedToBody: boolean
  summary: string
  qualityReport: ReviewQualityReport | null
  diffUnavailable: boolean
}

function useNarrowFooter(): boolean {
  const [narrow, setNarrow] = useState(() => window.innerWidth < NARROW_FOOTER_MAX_WIDTH)
  useEffect(() => {
    const update = () => setNarrow(window.innerWidth < NARROW_FOOTER_MAX_WIDTH)
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])
  return narrow
}

function RegenerateDialogContent({ onRegenerate }: { onRegenerate: () => void }) {
  return (
    <AlertDialogContent>
      <AlertDialogHeader>
        <AlertDialogTitle>Regenerate review?</AlertDialogTitle>
        <AlertDialogDescription>
          PR Pilot will generate a new review. When it finishes, it replaces this draft on GitHub, including comments
          you edited or added. The current draft stays visible until then.
        </AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter>
        <AlertDialogCancel>Keep draft</AlertDialogCancel>
        <AlertDialogAction onClick={onRegenerate}>Regenerate</AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  )
}

function DeleteDraftDialogContent({ onDelete }: { onDelete: () => void }) {
  return (
    <AlertDialogContent>
      <AlertDialogHeader>
        <AlertDialogTitle>Delete draft review?</AlertDialogTitle>
        <AlertDialogDescription>
          This removes the pending review from GitHub permanently.
        </AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter>
        <AlertDialogCancel>Cancel</AlertDialogCancel>
        <AlertDialogAction
          onClick={onDelete}
          className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
        >
          Delete
        </AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  )
}

export function ReviewFooter({
  state,
  saving,
  autosaveDirty,
  submitting,
  deleting,
  onSave,
  onSubmit,
  onRegenerate,
  onDelete,
  onRunQualityCheck,
  inlineCommentCount,
  publishSections,
  commentsMovedToBody,
  summary,
  qualityReport,
  diffUnavailable,
}: ReviewFooterProps) {
  const t = useI18n()
  const narrow = useNarrowFooter()
  const [menuDialog, setMenuDialog] = useState<'regenerate' | 'delete' | null>(null)
  if (state.kind === 'generating') return null

  const importedFromGitHub = state.kind === 'draftPresent' && state.importedFromGitHub
  const submitProps = {
    onSubmit,
    submitting,
    inlineCommentCount,
    publishSections,
    commentsMovedToBody,
    importedFromGitHub,
    summary,
    qualityReport,
    diffUnavailable,
  }

  if (state.kind === 'draftPresent' || state.kind === 'reviewUnsaved') {
    const busy = saving || submitting || deleting
    const qualityRiskCount = (qualityReport?.issues.reduce(
      (count, issue) => count + issue.count,
      0,
    ) ?? 0) + (diffUnavailable ? 1 : 0)
    const qualityRiskLabel = qualityRiskCount > 0
      ? qualityRiskCount === 1
        ? t('review.oneRisk')
        : t('review.riskCount', { count: qualityRiskCount })
      : t('review.noRisks')
    const canDelete = state.kind === 'draftPresent'
    const submitButton = (
      <SubmitReviewButton
        {...submitProps}
        verdict={state.result.verdict}
        disabled={saving || deleting}
      />
    )

    if (narrow) {
      const narrowSubmitButton = (
        <SubmitReviewButton
          {...submitProps}
          verdict={state.result.verdict}
          disabled={saving || deleting}
          compact
        />
      )
      return (
        <div
          data-testid="review-footer"
          className="shrink-0 flex items-center gap-2 px-3 py-1.5 border-t border-border bg-card"
        >
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                className="relative h-8 w-8 shrink-0 p-0"
                aria-label="More review actions"
                disabled={busy}
              >
                <MoreHorizontal className="w-4 h-4" />
                {qualityRiskCount > 0 && (
                  <span
                    aria-hidden="true"
                    className="absolute -right-0.5 -top-0.5 min-w-4 rounded-full bg-status-issue px-1 text-[10px] font-semibold leading-4 text-background"
                  >
                    {qualityRiskCount}
                  </span>
                )}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-56">
              <DropdownMenuItem className="gap-2 text-xs" onSelect={() => setMenuDialog('regenerate')}>
                <RotateCcw className="w-3.5 h-3.5" />
                Regenerate
              </DropdownMenuItem>
              <DropdownMenuItem className="gap-2 text-xs" onSelect={onRunQualityCheck}>
                <Check className="w-3.5 h-3.5" />
                {`${t('review.quality')} · ${qualityRiskLabel}`}
              </DropdownMenuItem>
              {canDelete && <DropdownMenuSeparator />}
              {canDelete && (
                <DropdownMenuItem
                  className="gap-2 text-xs text-status-issue focus:text-status-issue"
                  disabled={deleting}
                  onSelect={() => setMenuDialog('delete')}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  Delete draft
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
          <AlertDialog
            open={menuDialog === 'regenerate'}
            onOpenChange={(open) => !open && setMenuDialog(null)}
          >
            <RegenerateDialogContent onRegenerate={onRegenerate} />
          </AlertDialog>
          <AlertDialog
            open={menuDialog === 'delete'}
            onOpenChange={(open) => !open && setMenuDialog(null)}
          >
            <DeleteDraftDialogContent onDelete={onDelete} />
          </AlertDialog>

          {/* Long labels truncate instead of overflowing onto the menu trigger; the save status gives up
              width first, and the full text stays in the accessible name and the tooltip. */}
          <div className="ml-auto flex min-w-0 items-center justify-end gap-2">
            {saving || autosaveDirty ? (
              <Button
                variant="secondary"
                size="sm"
                onClick={onSave}
                disabled={saving || submitting || deleting}
                className="min-w-0 shrink-4 gap-1.5 text-xs"
                title="Changes save to the GitHub draft automatically. Click to save right now."
              >
                {saving
                  ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  : <CloudUpload className="w-3.5 h-3.5" />}
                <span className="truncate">{saving ? t('review.saving') : t('review.saveNow')}</span>
              </Button>
            ) : (
              <span
                className="inline-flex min-w-0 shrink-4 items-center gap-1.5 px-1 text-xs text-muted-foreground"
                role="status"
                title={t('review.savedToGitHub')}
              >
                <Check className="h-3.5 w-3.5 shrink-0 text-status-approve" />
                <span className="truncate">Saved</span>
              </span>
            )}
            {narrowSubmitButton}
          </div>
        </div>
      )
    }

    return (
      <div
        data-testid="review-footer"
        className="shrink-0 flex flex-wrap items-center gap-2 px-4 py-2.5 border-t border-border bg-card"
      >
        <div className="flex flex-wrap items-center gap-1">
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="sm" disabled={busy} className="gap-1.5 text-xs">
                <RotateCcw className="w-3.5 h-3.5" />
                Regenerate
              </Button>
            </AlertDialogTrigger>
            <RegenerateDialogContent onRegenerate={onRegenerate} />
          </AlertDialog>

          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="outline" size="sm" disabled={busy} className="gap-1.5 text-xs" onClick={onRunQualityCheck}>
                <Check className="w-3.5 h-3.5" />
                <span>{t('review.quality')}</span>
                <span className={qualityRiskCount > 0 ? 'text-status-issue' : 'text-status-approve'}>
                  · {qualityRiskLabel}
                </span>
              </Button>
            </TooltipTrigger>
            <TooltipContent side="top" className="max-w-xs text-xs leading-relaxed">
              Checks for outdated anchors, high-risk low-evidence comments, and missing rationale, then offers one-click
              repairs.
            </TooltipContent>
          </Tooltip>
        </div>

        {canDelete && (
          <div>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={deleting}
                  className="gap-1.5 text-xs text-status-issue hover:text-status-issue"
                >
                  {deleting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                  Delete
                </Button>
              </AlertDialogTrigger>
              <DeleteDraftDialogContent onDelete={onDelete} />
            </AlertDialog>
          </div>
        )}

        <div className="hidden flex-1 sm:block" />

        <div className="ml-auto flex w-full shrink-0 items-center justify-end gap-2 sm:w-auto">
          {saving || autosaveDirty ? (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={onSave}
                  disabled={saving || submitting || deleting}
                  className="gap-1.5 text-xs"
                >
                  {saving
                    ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    : <CloudUpload className="w-3.5 h-3.5" />}
                  {saving ? t('review.saving') : t('review.saveNow')}
                </Button>
              </TooltipTrigger>
              <TooltipContent side="top" className="max-w-xs text-xs leading-relaxed">
                Changes save to the GitHub draft automatically. Click to save right now.
              </TooltipContent>
            </Tooltip>
          ) : (
            <span className="inline-flex items-center gap-1.5 px-2 text-xs text-muted-foreground" role="status">
              <Check className="h-3.5 w-3.5 text-status-approve" />
              {t('review.savedToGitHub')}
            </span>
          )}

          {submitButton}
        </div>
      </div>
    )
  }

  if (state.kind === 'saveError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <Button variant="secondary" size="sm" onClick={onSave} disabled={saving || submitting} className="gap-1.5 text-xs">
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CloudUpload className="w-3.5 h-3.5" />}
          {saving ? 'Saving…' : 'Retry Save'}
        </Button>
        <SubmitReviewButton
          {...submitProps}
          verdict={state.result ? state.result.verdict : 'APPROVE'}
          disabled={saving}
        />
      </div>
    )
  }

  if (state.kind === 'submitError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <SubmitReviewButton
          {...submitProps}
          verdict={state.result ? state.result.verdict : 'APPROVE'}
          disabled={false}
        />
      </div>
    )
  }

  return null
}

const VERDICT_OPTIONS: Verdict[] = ['COMMENT', 'APPROVE', 'REQUEST_CHANGES']

const VERDICT_LABELS: Record<Verdict, string> = {
  APPROVE: 'Approve',
  REQUEST_CHANGES: 'Request changes',
  COMMENT: 'Comment',
}

function PublishedAlsoRegion({
  publishSections,
  importedFromGitHub,
  commentsMovedToBody,
}: {
  publishSections: PublishedBodySections
  importedFromGitHub: boolean
  commentsMovedToBody: boolean
}) {
  const t = useI18n()
  const { generalNotes, unanchored } = publishSections
  return (
    <div className="flex flex-col gap-1">
      <h3 id="published-also-heading" className="text-xs font-semibold">{t('review.publishedAlso')}</h3>
      <div
        role="region"
        aria-labelledby="published-also-heading"
        data-testid="published-also"
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- Scrollable publish preview needs a keyboard focus target.
        tabIndex={0}
        className="max-h-32 overflow-y-auto rounded border border-border bg-muted/30 p-2 text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
      >
        {generalNotes.length > 0 && (
          <>
            <p className="font-medium">General Notes:</p>
            <ul className="mb-2 list-disc space-y-0.5 pl-4">
              {generalNotes.map((note, index) => <li key={`note-${index}`} className="break-words">{note}</li>)}
            </ul>
          </>
        )}
        {unanchored.length > 0 && (
          <>
            <p className="font-medium">Comments not attached inline (invalid diff positions):</p>
            <ul className="mb-2 list-disc space-y-0.5 pl-4">
              {unanchored.map((comment, index) => (
                <li key={`orphan-${index}`} className="break-words">
                  <span className="break-all font-mono">{comment.line > 0 ? `${comment.file}:${comment.line}` : comment.file}</span>
                  {' '}
                  {comment.body}
                </li>
              ))}
            </ul>
          </>
        )}
        {importedFromGitHub && <p>{t('review.publishedImported')}</p>}
        {commentsMovedToBody && <p>{t('review.publishedMoved')}</p>}
      </div>
    </div>
  )
}

function SubmitReviewButton({
  verdict,
  onSubmit,
  submitting,
  disabled,
  inlineCommentCount,
  publishSections,
  commentsMovedToBody,
  importedFromGitHub,
  summary,
  qualityReport,
  diffUnavailable,
  compact = false,
}: {
  verdict: Verdict
  onSubmit: (verdict: Verdict, comment?: string) => void
  submitting: boolean
  disabled: boolean
  /** Narrow footer: the label may truncate so the row never overflows onto neighbouring controls. */
  compact?: boolean
  inlineCommentCount: number
  publishSections: PublishedBodySections
  commentsMovedToBody: boolean
  importedFromGitHub: boolean
  summary: string
  qualityReport: ReviewQualityReport | null
  diffUnavailable: boolean
}) {
  const [open, setOpen] = useState(false)
  const [selectedVerdict, setSelectedVerdict] = useState<Verdict>(verdict)
  const [comment, setComment] = useState('')
  const [risksAcknowledged, setRisksAcknowledged] = useState(false)
  const t = useI18n()
  const qualityRiskCount = qualityReport?.issues.reduce((count, issue) => count + issue.count, 0) ?? 0
  const riskCount = qualityRiskCount + (diffUnavailable ? 1 : 0)
  const riskKey = qualityReport?.issues.map((issue) => `${issue.id}:${issue.count}`).join('|') ?? ''
  const showPublishedAlso = publishSections.generalNotes.length + publishSections.unanchored.length > 0
    || importedFromGitHub
    || commentsMovedToBody

  useEffect(() => setRisksAcknowledged(false), [riskKey, diffUnavailable, open])

  function openDialog() {
    setSelectedVerdict(verdict)
    setComment(summary.trim())
    setOpen(true)
  }

  function closeDialog() {
    setOpen(false)
    setSelectedVerdict(verdict)
    setComment('')
  }

  return (
    <>
      <Button
        variant="default"
        size="sm"
        className={compact ? 'min-w-0 gap-1.5 text-xs' : 'gap-1.5 text-xs'}
        onClick={openDialog}
        disabled={submitting || disabled}
      >
        {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
        {compact
          ? <span className="truncate">{submitting ? 'Submitting…' : 'Submit review…'}</span>
          : submitting ? 'Submitting…' : 'Submit review…'}
      </Button>
      <AlertDialog open={open} onOpenChange={(nextOpen) => !nextOpen && closeDialog()}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Publish review</AlertDialogTitle>
            <AlertDialogDescription>
              This will publish the pending GitHub review with {inlineCommentCount} inline
              comment{inlineCommentCount === 1 ? '' : 's'}.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="flex flex-col gap-1.5">
            <p id="publish-verdict-label" className="text-sm font-medium">Verdict</p>
            <div role="radiogroup" aria-labelledby="publish-verdict-label" className="flex flex-col gap-1">
              {VERDICT_OPTIONS.map((option) => (
                <label key={option} className="flex items-center gap-2 text-sm">
                  <input
                    type="radio"
                    name="publish-verdict"
                    value={option}
                    checked={selectedVerdict === option}
                    onChange={() => setSelectedVerdict(option)}
                  />
                  <span>{VERDICT_LABELS[option]}{option === verdict ? ' (suggested)' : ''}</span>
                </label>
              ))}
            </div>
          </div>
          {riskCount > 0 && (
            <div className="rounded border border-status-issue/50 bg-status-issue/10 p-3 text-xs">
              <p className="font-semibold">
                {riskCount} unresolved trust {riskCount === 1 ? 'risk' : 'risks'}
              </p>
              <ul className="mt-2 list-disc space-y-1 pl-5">
                {qualityReport?.issues.map((issue) => (
                  <li key={issue.id}>{issue.title}: {issue.count}. {issue.description}</li>
                ))}
                {diffUnavailable && (
                  <li>The diff could not be rendered. Review the raw diff before publishing.</li>
                )}
              </ul>
              <label className="mt-3 flex items-start gap-2">
                <input
                  type="checkbox"
                  checked={risksAcknowledged}
                  onChange={(event) => setRisksAcknowledged(event.target.checked)}
                />
                <span>{t('quality.acknowledge')}</span>
              </label>
            </div>
          )}
          <div className="flex flex-col gap-1">
            <label htmlFor="final-review-body" className="text-sm font-medium">{t('review.finalBody')}</label>
            <p id="final-review-body-help" className="text-xs text-muted-foreground">{t('review.finalBodyHelp')}</p>
            <textarea
              id="final-review-body"
              aria-describedby="final-review-body-help"
              className="min-h-[96px] max-h-48 resize-y w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:border-ring"
              value={comment}
              onChange={(event) => setComment(event.target.value)}
            />
            {!showPublishedAlso && comment.trim().length === 0 && (
              <p role="status" className="text-xs text-muted-foreground">
                {t('review.publishFallback', { text: FALLBACK_REVIEW_BODY[selectedVerdict] })}
              </p>
            )}
          </div>
          {showPublishedAlso && (
            <PublishedAlsoRegion
              publishSections={publishSections}
              importedFromGitHub={importedFromGitHub}
              commentsMovedToBody={commentsMovedToBody}
            />
          )}
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={submitting || disabled || (riskCount > 0 && !risksAcknowledged)}
              onClick={() => {
                const chosen = selectedVerdict
                const body = comment.trim()
                closeDialog()
                onSubmit(chosen, body)
              }}
            >
              Publish as {VERDICT_LABELS[selectedVerdict]}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
