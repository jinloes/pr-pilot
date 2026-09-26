import type { ReactNode } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  GitMerge,
  Loader2,
  RefreshCw,
  RotateCcw,
  Settings2,
} from 'lucide-react'
import type { LineComment, ReviewResult } from '../../bridge/types'
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
  focusedCommentIdx: number
  commentFocusRequestId: number
  onGenerate: () => void
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

function coverageSummary(coverage: DiffCoverage): string {
  const budget = formatBudget(coverage.budget)
  const consequence = 'Single-pass review input and the diff view omit'
  if (coverage.omitted === 0) {
    return `This PR's diff was too large to scan completely within the ${budget} review budget, so some changed `
      + `files may be missing. ${consequence} any such files, and chat uses a diff excerpt.`
  }
  const count = coverage.omitted
  const files = `${count} changed file${count === 1 ? '' : 's'}`
  const lead = coverage.scanComplete ? files : `At least ${files}`
  const scan = coverage.scanComplete ? '' : ' and was too large to scan completely'
  return `${lead} ${count === 1 ? 'was' : 'were'} left out of this PR's diff because it exceeds the ${budget} review `
    + `budget${scan}. ${consequence} ${count === 1 ? 'this file' : 'these files'}, and chat uses a diff excerpt.`
}

/** Shown whenever the single-pass review diff omits changed files, as declared by its trailer. */
function DiffCoverageBanner({ coverage }: { coverage: DiffCoverage }) {
  const unlisted = unlistedCount(coverage)
  return (
    <Alert className="mx-4 mt-3 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
      <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
      <AlertDescription className="text-xs text-status-suggestion">
        <p>{coverageSummary(coverage)}</p>
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
}: {
  result: ReviewResult | null
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
        <Alert className="mx-4 mt-3 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
          <AlertTriangle className="h-3.5 w-3.5 text-status-suggestion" />
          <AlertDescription className="text-xs text-status-suggestion">
            Draft generated against an older commit — new commits may have been pushed.
          </AlertDescription>
        </Alert>
      )}
      {importedFromGitHub && (
        <Alert className="mx-4 mt-3 mb-0 border-status-suggestion/40 bg-status-suggestion/5">
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
      )}
      {coverage && <DiffCoverageBanner coverage={coverage} />}
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
}: {
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
        />
      )}
    </div>
  )
}

export function PaneContent({
  state,
  focusedCommentIdx,
  commentFocusRequestId,
  onGenerate,
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
      const coverage = splitDiffCoverage(state.diff).coverage
      return (
        <>
          {coverage && <DiffCoverageBanner coverage={coverage} />}
          <div className="flex flex-col items-center justify-center gap-4 p-8">
            <p className="text-sm text-muted-foreground">No pending draft for this PR.</p>
            {state.providerReadiness && (
              <p
                className={cn('text-xs font-medium', state.providerReadiness.available ? 'text-status-approve' : 'text-status-issue')}
                role="status"
              >
                {state.providerReadiness.available
                  ? `${state.providerReadiness.provider === 'claude' ? 'Claude' : 'Copilot'} ready`
                  : state.providerReadiness.detail}
              </p>
            )}
            <Button
              data-testid="generate-review"
              onClick={onGenerate}
              className="gap-2"
              disabled={state.providerReadiness?.available === false}
            >
              Generate Review
            </Button>
            {state.providerReadiness?.available === false && (
              <Button variant="outline" size="sm" onClick={onOpenSettings}>Open Settings</Button>
            )}
          </div>
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
          onReanchor={onReanchor}
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
        />
      )

    case 'reviewUnsaved':
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
          inlineComments={inlineComments}
          orphanComments={orphanComments}
          onEditOrphan={onEditOrphan}
          onDeleteOrphan={onDeleteOrphan}
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
        />
      )
  }
}
