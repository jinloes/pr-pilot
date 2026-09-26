import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useI18n } from '@/i18n/I18nProvider'
import { cn } from '@/lib/utils'
import type { ChunkRecommendation, DiffPreflight } from './useReviewController'

interface ReviewOverridesProps {
  summaryLabel?: string
  focusAreas: string
  customInstructions: string
  chunkedMode: boolean
  preflight: DiffPreflight | null
  recommendation: ChunkRecommendation
  /** Changed files chunked review adds over the standard review diff. */
  coverageGain?: number
  onFocusAreasChange: (value: string) => void
  onCustomInstructionsChange: (value: string) => void
  onChunkedModeChange: (value: boolean) => void
  /** `owner/repo` of the selected PR; the remembered-instructions editor is hidden without it. */
  repository?: string
  /** Instructions the host currently remembers for {@link repository}. */
  repositoryInstructions?: string
  repositoryInstructionsDraft?: string
  repositoryInstructionsSaving?: boolean
  repositoryInstructionsError?: string
  repositoryInstructionsSaved?: boolean
  onRepositoryInstructionsDraftChange?: (value: string) => void
  onSaveRepositoryInstructions?: () => void
  intellijAssisted?: boolean
  onIntellijAssistedChange?: (value: boolean) => void
  onShowRetained?: () => void
}

export function ReviewOverrides({
  summaryLabel = 'Review instructions (optional)',
  focusAreas,
  customInstructions,
  chunkedMode,
  preflight,
  recommendation,
  coverageGain = 0,
  onFocusAreasChange,
  onCustomInstructionsChange,
  onChunkedModeChange,
  repository,
  repositoryInstructions = '',
  repositoryInstructionsDraft = '',
  repositoryInstructionsSaving = false,
  repositoryInstructionsError = '',
  repositoryInstructionsSaved = false,
  onRepositoryInstructionsDraftChange,
  onSaveRepositoryInstructions,
  intellijAssisted = false,
  onIntellijAssistedChange,
  onShowRetained,
}: ReviewOverridesProps) {
  const overrideCount = Number(focusAreas.trim().length > 0) + Number(customInstructions.trim().length > 0)
  const hasOverrides = overrideCount > 0
  const t = useI18n()
  const showRepositoryInstructions = Boolean(repository && onRepositoryInstructionsDraftChange && onSaveRepositoryInstructions)
  const rememberedTrimmed = repositoryInstructions.trim()
  const draftTrimmed = repositoryInstructionsDraft.trim()
  const repositoryDirty = draftTrimmed !== rememberedTrimmed
  const forgetting = repositoryDirty && draftTrimmed.length === 0
  return (
    <div className="px-4 pt-3">
      <details
        data-testid="review-overrides-disclosure"
        className="rounded border border-border bg-muted/20 px-3 py-2.5"
      >
        <summary className="flex cursor-pointer list-none items-center justify-between gap-2 text-xs font-medium text-foreground">
          <span>{summaryLabel}</span>
          <span className="flex flex-wrap items-center justify-end gap-1">
            {showRepositoryInstructions && rememberedTrimmed.length > 0 && (
              <Badge variant="outline" className="px-1.5 py-0 text-[10px] font-normal">
                {t('review.repositoryInstructionsActive')}
              </Badge>
            )}
            {hasOverrides && (
              <Badge variant="outline" className="px-1.5 py-0 text-[10px] font-normal">
                {overrideCount} {overrideCount === 1 ? 'override' : 'overrides'} applied
              </Badge>
            )}
          </span>
        </summary>
        <div className="mt-3">
          <div className="flex items-center justify-between gap-2">
            <p className="text-xs font-medium text-foreground">Per-review instructions</p>
            {hasOverrides && (
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-[11px]"
                onClick={() => {
                  onFocusAreasChange('')
                  onCustomInstructionsChange('')
                }}
              >
                Clear
              </Button>
            )}
          </div>
          <p className="mt-1 text-[11px] text-muted-foreground">
            Leave blank to use defaults from Settings.
          </p>
          <p className="mt-1 text-[11px] text-muted-foreground" role="note">
            {t('review.guidanceStatus')}
          </p>
          <label htmlFor="review-focus-areas" className="mt-2 block text-xs font-medium text-foreground">
            {t('review.focusAreas')}
          </label>
          <input
            id="review-focus-areas"
            className="mt-2 w-full rounded border border-border bg-background px-2 py-1 text-xs outline-none focus:ring-1 focus:ring-ring"
            placeholder="Focus areas (e.g. security, performance, tests)"
            value={focusAreas}
            onChange={(event) => onFocusAreasChange(event.target.value)}
          />
          {showRepositoryInstructions && repository && (
            <div className="mt-3 border-t border-border/60 pt-3" data-testid="repository-instructions">
              <label htmlFor="review-repository-instructions" className="block text-xs font-medium text-foreground">
                {t('review.repositoryInstructions', { repo: repository })}
              </label>
              <p id="review-repository-instructions-help" className="mt-1 text-[11px] text-muted-foreground">
                {t('review.repositoryInstructionsHelp', { repo: repository })}
              </p>
              <textarea
                id="review-repository-instructions"
                aria-describedby="review-repository-instructions-help review-repository-instructions-status"
                className="mt-2 w-full rounded border border-border bg-background px-2 py-1 text-xs outline-none focus:ring-1 focus:ring-ring resize-y"
                rows={2}
                placeholder="Conventions every review of this repository should follow"
                value={repositoryInstructionsDraft}
                onChange={(event) => onRepositoryInstructionsDraftChange?.(event.target.value)}
              />
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 px-2 text-[11px]"
                  disabled={!repositoryDirty || repositoryInstructionsSaving}
                  onClick={() => onSaveRepositoryInstructions?.()}
                >
                  {repositoryInstructionsSaving
                    ? t('review.saving')
                    : forgetting
                      ? t('review.repositoryInstructionsForget')
                      : t('review.repositoryInstructionsRemember')}
                </Button>
                <span
                  id="review-repository-instructions-status"
                  role="status"
                  aria-live="polite"
                  className={cn('text-[11px]', repositoryInstructionsError ? 'text-status-issue' : 'text-muted-foreground')}
                >
                  {repositoryInstructionsError
                    ? repositoryInstructionsError
                    : repositoryInstructionsSaved
                      ? rememberedTrimmed
                        ? t('review.repositoryInstructionsSaved', { repo: repository })
                        : t('review.repositoryInstructionsForgotten', { repo: repository })
                      : ''}
                </span>
              </div>
            </div>
          )}
          <details className="mt-2 rounded border border-border/70 p-2">
            <summary className="cursor-pointer text-xs font-medium text-foreground">{t('review.advanced')}</summary>
            <label htmlFor="review-custom-instructions" className="mt-2 block text-xs font-medium text-foreground">
              {t('review.customInstructions')}
            </label>
            <textarea
              id="review-custom-instructions"
              className="mt-2 w-full rounded border border-border bg-background px-2 py-1 text-xs outline-none focus:ring-1 focus:ring-ring resize-y"
              rows={2}
              placeholder="Custom instructions for this review only"
              value={customInstructions}
              onChange={(event) => onCustomInstructionsChange(event.target.value)}
            />
            {onIntellijAssistedChange && <label className="mt-2 block text-xs">
              <input type="checkbox" checked={intellijAssisted}
                onChange={e => onIntellijAssistedChange(e.target.checked)} /> IntelliJ-assisted review (requires manual worktree import)
            </label>}
            {onShowRetained && <Button size="sm" variant="outline" onClick={onShowRetained}>
              Retained IntelliJ review worktrees
            </Button>}
            <label className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
              <input
                type="checkbox"
                checked={chunkedMode}
                onChange={(event) => onChunkedModeChange(event.target.checked)}
              />
              Use chunked review mode as an advanced fallback
            </label>
            <div className="mt-1 pl-6 text-[11px] text-muted-foreground">
              {preflight
                ? `PR size: ${preflight.fileCount} file${preflight.fileCount === 1 ? '' : 's'}, ${preflight.changedLines} changed lines.`
                : 'PR size: loading diff metadata…'}
            </div>
            <div className="mt-1 pl-6 text-[11px]">
              <span className={cn('font-medium', coverageGain > 0 ? 'text-status-suggestion' : 'text-status-approve')}>
                {coverageGain > 0
                  ? 'Recommended for this PR: chunked review.'
                  : 'Recommended: standard review.'}
              </span>
              <span className="text-muted-foreground"> {recommendation.reason}</span>
            </div>
            <p className="mt-1 pl-6 text-[11px] text-muted-foreground">
              Chunked review works in file batches, so it can miss cross-file interactions. Use it when a standard review
              would leave files out.
            </p>
          </details>
        </div>
      </details>
    </div>
  )
}
