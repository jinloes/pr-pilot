import type { ReactNode } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  GitMerge,
  History,
  Loader2,
  RefreshCw,
  RotateCcw,
  Settings2,
} from 'lucide-react'
import type { IncrementalFallbackReason, LineComment, ReviewResult, ReviewScope } from '../../bridge/types'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { formatBudget, splitDiffCoverage, unlistedCount, type DiffCoverage } from '@/lib/diffCoverage'
import { cn } from '@/lib/utils'
import { DiffViewer } from '../DiffViewer'
import { ReviewDisplay } from '../ReviewDisplay'
import { OrphanCommentsSection } from './OrphanComments'
import type { PaneState } from './reviewState'

export interface EditCommentHandlers {
  onEditComment: (index: number, body: string) => void
  onDeleteComment: (index: number) => void
  onAddComment: (comment: LineComment) => void
}

interface PaneContentProps {
  state: PaneState
  /** Next-generation options rendered inside the no-draft generation card. */
  generationOptions?: ReactNode
  /** Changed files chunked review would add over the standard review diff. */
  coverageGain: number
  chunkedMode: boolean
  onChunkedModeChange: (value: boolean) => void
  focusedCommentIdx: number
  commentFocusRequestId: number
  onGenerate: () => void
  /** Present only when the user's last review is behind the PR head. */
  onGenerateIncremental?: () => void
  onVerifyComment?: (comment: LineComment) => void
  onSuggestFixComment?: (comment: LineComment) => void
  onFocusComment: (index: number) => void
  editCommentHandlers: EditCommentHandlers
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
  onReloadDraft: () => void
  onRetryDelete: () => void
  onKeepDraft: () => void
  onReanchor: () => void
  onOpenSettings: () => void
  onOpenAuthGuide: () => void
}

function formatElapsed(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return minutes > 0 ? `${minutes}:${String(remainder).padStart(2, '0')}` : `${remainder}s`
}

function formatGenerationSummary(elapsedSec?: number): string | null {
  if (elapsedSec == null || elapsedSec < 0) return null
  return `Generated in ${formatElapsed(elapsedSec)}`
}

function plural(count: number, singular: string, pluralForm: string): string {
  return count === 1 ? singular : pluralForm
}

function coverageLead(coverage: DiffCoverage): string {
  const budget = formatBudget(coverage.budget)
  const count = coverage.omitted
  const reason = `because the pull request's diff is larger than ${budget}.`
  if (count === 0) return `Some changed files may not be included in this review ${reason}`
  const files = `${count} changed ${plural(count, 'file', 'files')} ${plural(count, "isn't", "aren't")}`
  return `${coverage.scanComplete ? files : `At least ${files}`} included in this review ${reason}`
}

function coverageConsequence(diffShown: boolean): string {
  return diffShown
    ? "They won't appear in the diff below, the generated review, or chat."
    : "They won't be part of the generated review or chat."
}

function coverageRemedy(gain: number, chunkedMode: boolean): string {
  if (gain <= 0) return 'Chunked review would not add these files. Consider splitting the pull request.'
  return chunkedMode
    ? `Chunked review is on. The next review will include ${gain} more ${plural(gain, 'file', 'files')}.`
    : `Chunked review can include ${gain} of them.`
}

const FALLBACK_REASON_TEXT: Record<IncrementalFallbackReason, string> = {
  no_prior_review: "you haven't submitted a review on this pull request",
  up_to_date: 'no commits were pushed since your last review',
  baseline_not_in_history: "the commit you last reviewed is no longer in this pull request's history",
  baseline_unavailable: "GitHub couldn't compare against the commit you last reviewed",
  empty_incremental_diff: 'the changes since your last review have no diff',
  incremental_diff_too_large: 'the changes since your last review are too large to compare',
}

export function reviewScopeText(scope: ReviewScope): string {
  return scope.kind === 'incremental'
    ? `Reviewed only changes since your last review (${scope.baselineSha.slice(0, 7)}).`
    : `Couldn't review only new changes (${FALLBACK_REASON_TEXT[scope.fallbackReason]}). Reviewed the full pull request instead.`
}

function ReviewScopeBanner({ scope }: { scope: ReviewScope }) {
  return (
    <div className="px-4 pt-3">
      <Alert
        data-testid="review-scope-banner"
        className={cn(
          'mt-0 mb-0',
          scope.kind === 'full' && 'border-status-suggestion/40 bg-status-suggestion/5',
        )}
      >
        <History className={cn('h-3.5 w-3.5', scope.kind === 'full' && 'text-status-suggestion')} />
        <AlertDescription className={cn('text-xs', scope.kind === 'full' && 'text-status-suggestion')}>
          {reviewScopeText(scope)}
        </AlertDescription>
      </Alert>
    </div>
  )
}

interface CoverageRemedyProps {
  coverageGain: number
  chunkedMode: boolean
  onChunkedModeChange: (value: boolean) => void
  /** True when a review already exists, so turning chunked mode on only affects the next run. */
  nextRegeneration: boolean
}

/** Shown whenever the standard review diff omits changed files, as declared by its trailer. */
function DiffCoverageBanner({
  coverage,
  diffShown,
  coverageGain,
  chunkedMode,
  onChunkedModeChange,
  nextRegeneration,
}: { coverage: DiffCoverage; diffShown: boolean } & CoverageRemedyProps) {
  const unlisted = unlistedCount(coverage)
  return (
    <div className="px-4 pt-3">
      <Alert className="mt-0 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
        <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
        <AlertDescription className="text-xs text-status-suggestion">
          <p>{`${coverageLead(coverage)} ${coverageConsequence(diffShown)} ${coverageRemedy(coverageGain, chunkedMode)}`}</p>
          {coverageGain > 0 && !chunkedMode && (
            <Button
              variant="outline"
              size="sm"
              className="mt-2 h-auto min-h-7 whitespace-normal px-2 py-1 text-left text-[11px]"
              onClick={() => onChunkedModeChange(true)}
            >
              {nextRegeneration ? 'Use chunked review for the next regeneration' : 'Include them with chunked review'}
            </Button>
          )}
          {coverage.paths.length > 0 && (
            <details className="mt-1">
              <summary className="cursor-pointer">Show omitted files</summary>
              <ul className="mt-1 list-disc space-y-0.5 pl-4">
                {coverage.paths.map((path, index) => (
                  <li key={`${index}:${path}`} className="break-all font-mono text-[11px]">{path}</li>
                ))}
                {unlisted > 0 && <li className="list-none">{`+${unlisted} more not listed`}</li>}
              </ul>
            </details>
          )}
          {coverage.paths.length === 0 && coverage.omitted > 0 && (
            <p className="mt-1">{`${coverage.omitted} omitted file${coverage.omitted === 1 ? ' is' : 's are'} not listed.`}</p>
          )}
        </AlertDescription>
      </Alert>
    </div>
  )
}

function ReviewAndDiff({
  result,
  diff,
  generationElapsedSec,
  focusedCommentIdx,
  commentFocusRequestId,
  editCommentHandlers,
  onVerifyComment,
  onSuggestFixComment,
  onFocusComment,
  staleCommits,
  importedFromGitHub,
  onReanchor,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
  readOnly = false,
  generationMessage,
  coverageGain,
  chunkedMode,
  onChunkedModeChange,
  nextRegeneration,
  reviewScope,
}: CoverageRemedyProps & {
  result: ReviewResult | null
  reviewScope?: ReviewScope
  diff?: string
  generationElapsedSec?: number
  focusedCommentIdx: number
  commentFocusRequestId: number
  editCommentHandlers: EditCommentHandlers
  onVerifyComment?: (comment: LineComment) => void
  onSuggestFixComment?: (comment: LineComment) => void
  onFocusComment: (index: number) => void
  staleCommits?: boolean
  importedFromGitHub?: boolean
  onReanchor?: () => void
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
  readOnly?: boolean
  generationMessage?: string
}) {
  const generationSummary = formatGenerationSummary(generationElapsedSec)
  // The diff view renders only the kept files; the coverage trailer is metadata, not diff text.
  const { body: displayDiff, coverage } = splitDiffCoverage(diff)
  return (
    <>
      {generationMessage && (
        <div className="mx-4 mt-3 rounded-md border border-primary/30 bg-primary/5 px-3 py-2 text-xs text-muted-foreground">
          {generationMessage}
        </div>
      )}
      {staleCommits && (
        <div className="px-4 pt-3">
          <Alert className="mt-0 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
            <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
            <AlertDescription className="text-xs text-status-suggestion">
              Draft generated against an older commit — new commits may have been pushed.
            </AlertDescription>
          </Alert>
        </div>
      )}
      {importedFromGitHub && (
        <div className="px-4 pt-3">
          <Alert className="mt-0 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
            <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
            <AlertDescription className="text-xs text-status-suggestion">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span>
                  Draft was reconstructed from GitHub comments — hidden PR Pilot metadata was missing, so review details
                  may be incomplete.
                </span>
                {onReanchor && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-6 shrink-0 gap-1.5 px-2 text-[11px]"
                    onClick={onReanchor}
                  >
                    <RefreshCw className="h-3 w-3" />
                    Re-anchor from current diff
                  </Button>
                )}
              </div>
            </AlertDescription>
          </Alert>
        </div>
      )}
      {reviewScope && <ReviewScopeBanner scope={reviewScope} />}
      {coverage && (
        <DiffCoverageBanner
          coverage={coverage}
          diffShown={Boolean(displayDiff)}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration={nextRegeneration}
        />
      )}
      {generationSummary && (
        <p className="px-4 pt-3 text-xs text-muted-foreground">{generationSummary}</p>
      )}
      {result && (
        <div className="px-4 pt-3">
          <ReviewDisplay result={result} />
        </div>
      )}
      {result && orphanComments.length > 0 && (
        <div className="px-4 pt-3">
          <OrphanCommentsSection
            orphans={orphanComments}
            onEdit={onEditOrphan}
            onDelete={onDeleteOrphan}
            readOnly={readOnly}
          />
        </div>
      )}
      {displayDiff && (
        <div key="review-diff" className="px-4 pb-4">
          <DiffViewer
            diff={displayDiff}
            comments={inlineComments}
            orphanComments={orphanComments}
            focusedCommentIdx={focusedCommentIdx}
            commentFocusRequestId={commentFocusRequestId}
            onFocusComment={onFocusComment}
            onEditComment={editCommentHandlers.onEditComment}
            onDeleteComment={editCommentHandlers.onDeleteComment}
            onAddComment={editCommentHandlers.onAddComment}
            onVerifyComment={onVerifyComment}
            onSuggestFixComment={onSuggestFixComment}
            readOnly={readOnly}
          />
        </div>
      )}
    </>
  )
}

function ErrorWithReview({
  message,
  result,
  diff,
  focusedCommentIdx,
  commentFocusRequestId,
  editCommentHandlers,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
  onFocusComment,
  readOnly = false,
  actions,
  coverageGain,
  chunkedMode,
  onChunkedModeChange,
  nextRegeneration,
}: CoverageRemedyProps & {
  message: string
  result: ReviewResult | null
  diff: string
  focusedCommentIdx: number
  commentFocusRequestId: number
  editCommentHandlers: EditCommentHandlers
  inlineComments: LineComment[]
  orphanComments: LineComment[]
  onEditOrphan: (orphan: LineComment, body: string) => void
  onDeleteOrphan: (orphan: LineComment) => void
  onFocusComment: (index: number) => void
  readOnly?: boolean
  actions?: ReactNode
}) {
  return (
    <div className="flex flex-col">
      <div className="px-4 pb-3 pt-3">
        <Alert variant="destructive">
          <AlertDescription>{message}</AlertDescription>
        </Alert>
        {actions && <div className="mt-3">{actions}</div>}
      </div>
      {(result || diff) && (
        <ReviewAndDiff
          result={result}
          diff={diff || undefined}
          focusedCommentIdx={focusedCommentIdx}
          commentFocusRequestId={commentFocusRequestId}
          editCommentHandlers={editCommentHandlers}
          onFocusComment={onFocusComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
          readOnly={readOnly}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration={nextRegeneration}
        />
      )}
    </div>
  )
}

function GenerationCard({
  state,
  onGenerate,
  onGenerateIncremental,
  onOpenSettings,
  generationOptions,
}: {
  state: Extract<PaneState, { kind: 'noDraft' }>
  onGenerate: () => void
  onGenerateIncremental?: () => void
  onOpenSettings: () => void
  generationOptions?: ReactNode
}) {
  const readiness = state.providerReadiness
  const unavailable = readiness?.available === false
  return (
    <div className="px-4 pt-3">
      <div data-testid="generation-card" className="rounded-md border border-border bg-card">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-3 py-3">
          <Button
            data-testid="generate-review"
            onClick={onGenerate}
            className="gap-2"
            disabled={unavailable}
          >
            Generate Review
          </Button>
          {onGenerateIncremental && (
            <Button
              data-testid="generate-incremental-review"
              variant="outline"
              onClick={onGenerateIncremental}
              className="gap-2"
              disabled={unavailable}
            >
              <History className="h-3.5 w-3.5" />
              Review changes since last review
            </Button>
          )}
          <div className="min-w-0 flex-1 basis-[180px]">
            <p className="text-sm text-muted-foreground">No pending draft for this PR.</p>
            {readiness && (
              <p
                className={cn('text-xs font-medium', readiness.available ? 'text-status-approve' : 'text-status-issue')}
                role="status"
              >
                {readiness.available
                  ? `${readiness.provider === 'claude' ? 'Claude' : 'Copilot'} ready`
                  : readiness.detail}
              </p>
            )}
          </div>
          {unavailable && (
            <Button variant="outline" size="sm" onClick={onOpenSettings}>Open Settings</Button>
          )}
        </div>
        {generationOptions && <div className="[&>div]:px-3 [&>div]:pb-3 [&>div]:pt-0">{generationOptions}</div>}
      </div>
    </div>
  )
}

export function PaneContent({
  state,
  generationOptions,
  coverageGain,
  chunkedMode,
  onChunkedModeChange,
  focusedCommentIdx,
  commentFocusRequestId,
  onGenerate,
  onGenerateIncremental,
  onVerifyComment,
  onSuggestFixComment,
  onFocusComment,
  editCommentHandlers,
  inlineComments,
  orphanComments,
  onEditOrphan,
  onDeleteOrphan,
  onReloadDraft,
  onRetryDelete,
  onKeepDraft,
  onReanchor,
  onOpenSettings,
  onOpenAuthGuide,
}: PaneContentProps) {
  switch (state.kind) {
    case 'idle':
      return null

    case 'draftLoading':
      return (
        <div className="flex items-center gap-2.5 px-4 pt-3 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 text-primary animate-spin shrink-0" />
          Checking for draft…
        </div>
      )

    case 'noDraft': {
      const coverageProps = { coverageGain, chunkedMode, onChunkedModeChange, nextRegeneration: false }
      return (
        <>
          <GenerationCard
            state={state}
            onGenerate={onGenerate}
            onGenerateIncremental={onGenerateIncremental}
            onOpenSettings={onOpenSettings}
            generationOptions={generationOptions}
          />
          {state.diff?.trim() && (
            <ReviewAndDiff
              result={null}
              diff={state.diff}
              focusedCommentIdx={focusedCommentIdx}
              commentFocusRequestId={commentFocusRequestId}
              editCommentHandlers={editCommentHandlers}
              onFocusComment={onFocusComment}
              inlineComments={[]}
              orphanComments={[]}
              onEditOrphan={onEditOrphan}
              onDeleteOrphan={onDeleteOrphan}
              readOnly
              {...coverageProps}
            />
          )}
        </>
      )
    }

    case 'authError':
      return (
        <div className="p-4 flex flex-col gap-3">
          <Alert variant="destructive">
            <AlertDescription>{state.message}</AlertDescription>
          </Alert>
          <p className="text-xs text-muted-foreground">
            Check GitHub CLI authentication and host settings, then retry.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" className="gap-1.5" onClick={onReloadDraft}>
              <RefreshCw className="w-3.5 h-3.5" />
              Retry
            </Button>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={onOpenSettings}>
              <Settings2 className="w-3.5 h-3.5" />
              Open Settings
            </Button>
            <Button variant="outline" size="sm" className="gap-1.5" onClick={onOpenAuthGuide}>
              <ExternalLink className="w-3.5 h-3.5" />
              Auth Guide
            </Button>
          </div>
        </div>
      )

    case 'generating':
      return (
        <ReviewAndDiff
          result={state.result}
          diff={state.diff}
          generationElapsedSec={state.generationElapsedSec}
          focusedCommentIdx={focusedCommentIdx}
          commentFocusRequestId={commentFocusRequestId}
          editCommentHandlers={editCommentHandlers}
          onVerifyComment={onVerifyComment}
          onSuggestFixComment={onSuggestFixComment}
          onFocusComment={onFocusComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
          readOnly
          generationMessage={state.replacingDraft
            ? 'Regenerating — current draft remains until the new review is ready. Editing and review actions are paused.'
            : 'Keep inspecting the changed files while PR Pilot generates the review. Editing and review actions are paused.'}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration
        />
      )

    case 'draftPresent':
      return (
        <ReviewAndDiff
          result={state.result}
          diff={state.diff}
          generationElapsedSec={state.generationElapsedSec}
          focusedCommentIdx={focusedCommentIdx}
          commentFocusRequestId={commentFocusRequestId}
          editCommentHandlers={editCommentHandlers}
          onFocusComment={onFocusComment}
          onVerifyComment={onVerifyComment}
          onSuggestFixComment={onSuggestFixComment}
          staleCommits={state.staleCommits}
          importedFromGitHub={state.importedFromGitHub}
          reviewScope={state.reviewScope}
          onReanchor={onReanchor}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration
        />
      )

    case 'reviewUnsaved':
      return (
        <ReviewAndDiff
          result={state.result}
          diff={state.diff}
          reviewScope={state.reviewScope}
          generationElapsedSec={state.generationElapsedSec}
          focusedCommentIdx={focusedCommentIdx}
          commentFocusRequestId={commentFocusRequestId}
          editCommentHandlers={editCommentHandlers}
          onFocusComment={onFocusComment}
          onVerifyComment={onVerifyComment}
          onSuggestFixComment={onSuggestFixComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration
        />
      )

    case 'merged':
      return (
        <div className="flex flex-col items-center justify-center gap-2 p-8">
          <GitMerge className="w-9 h-9 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">This pull request has been merged.</p>
          {state.status && <p className="text-xs text-muted-foreground">{state.status}</p>}
        </div>
      )

    case 'submitted':
      return (
        <div className="flex flex-col items-center justify-center gap-3 p-8">
          <CheckCircle2 className="w-9 h-9 text-emerald-500" />
          <p className="text-sm text-muted-foreground">Review submitted.</p>
          <Button variant="outline" onClick={onGenerate} className="gap-2">
            <RotateCcw className="w-3.5 h-3.5" />
            Generate New Review
          </Button>
        </div>
      )

    case 'error':
      if (state.result || state.diff) {
        return (
          <ErrorWithReview
            message={state.message}
            result={state.result ?? null}
            diff={state.diff ?? ''}
            focusedCommentIdx={focusedCommentIdx}
            commentFocusRequestId={commentFocusRequestId}
            editCommentHandlers={editCommentHandlers}
            onFocusComment={onFocusComment}
            inlineComments={inlineComments}
            orphanComments={orphanComments}
            onEditOrphan={onEditOrphan}
            onDeleteOrphan={onDeleteOrphan}
            readOnly
            actions={(
              <Button variant="outline" size="sm" onClick={onGenerate} className="w-fit gap-1.5">
                <RotateCcw className="w-3.5 h-3.5" />
                Try Again
              </Button>
            )}
            coverageGain={coverageGain}
            chunkedMode={chunkedMode}
            onChunkedModeChange={onChunkedModeChange}
            nextRegeneration={false}
          />
        )
      }
      return (
        <div className="p-4 flex flex-col gap-3">
          <Alert variant="destructive">
            <AlertDescription>{state.message}</AlertDescription>
          </Alert>
          <Button variant="outline" size="sm" onClick={onGenerate} className="w-fit gap-1.5">
            <RotateCcw className="w-3.5 h-3.5" />
            Try Again
          </Button>
        </div>
      )

    case 'saveError':
      return (
        <ErrorWithReview
          message={state.message}
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          commentFocusRequestId={commentFocusRequestId}
          editCommentHandlers={editCommentHandlers}
          onFocusComment={onFocusComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration
        />
      )

    case 'submitError':
      return (
        <ErrorWithReview
          message={state.message}
          result={state.result}
          diff={state.diff}
          focusedCommentIdx={focusedCommentIdx}
          commentFocusRequestId={commentFocusRequestId}
          editCommentHandlers={editCommentHandlers}
          onFocusComment={onFocusComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration
        />
      )

    case 'deleteError':
      return (
        <ErrorWithReview
          message={state.message}
          result={state.draft.result}
          diff={state.draft.diff ?? ''}
          focusedCommentIdx={focusedCommentIdx}
          commentFocusRequestId={commentFocusRequestId}
          editCommentHandlers={editCommentHandlers}
          onFocusComment={onFocusComment}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
          actions={(
            <div className="flex flex-wrap gap-2">
              <Button variant="destructive" size="sm" onClick={onRetryDelete}>Retry delete</Button>
              <Button variant="outline" size="sm" onClick={onReloadDraft}>Reload draft</Button>
              <Button variant="ghost" size="sm" onClick={onKeepDraft}>Keep draft</Button>
            </div>
          )}
          coverageGain={coverageGain}
          chunkedMode={chunkedMode}
          onChunkedModeChange={onChunkedModeChange}
          nextRegeneration
        />
      )
  }
}
