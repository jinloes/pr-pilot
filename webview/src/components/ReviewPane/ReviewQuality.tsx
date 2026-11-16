import { Button } from '@/components/ui/button'
import type { ReviewQualityAction, ReviewQualityReport } from '@/lib/reviewQuality'

export function ReviewQualityCheckCard({
  report,
  onApplyRepair,
  onCollapse,
}: {
  report: ReviewQualityReport
  onApplyRepair: (action: ReviewQualityAction) => void
  onCollapse: () => void
}) {
  const riskCount = report.issues.reduce((count, issue) => count + issue.count, 0)

  return (
    <div className="rounded border border-border bg-muted/20 px-3 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs font-semibold">Review Quality Check</span>
        <span className="text-xs text-status-issue">
          {riskCount} unresolved {riskCount === 1 ? 'risk' : 'risks'}
        </span>
        <Button variant="ghost" size="sm" className="ml-auto h-6 px-2 text-[11px]" onClick={onCollapse}>
          Hide
        </Button>
      </div>
      {report.issues.length === 0 ? (
        <p className="mt-1 text-xs text-status-approve">No major trust issues detected in this draft.</p>
      ) : (
        <ul className="mt-2 space-y-1">
          {report.issues.map((issue) => (
            <li key={issue.id} className="text-xs text-muted-foreground">
              <span className="text-foreground">{issue.title}</span> · {issue.count} · {issue.description}
            </li>
          ))}
        </ul>
      )}
      {report.suggestions.length > 0 && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          {report.suggestions.includes('removeUnanchored') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('removeUnanchored')}>
              Remove unanchored comments
            </Button>
          )}
          {report.suggestions.includes('dropMissingRationale') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('dropMissingRationale')}>
              Drop comments without rationale
            </Button>
          )}
          {report.suggestions.includes('downgradeHighRisk') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('downgradeHighRisk')}>
              Downgrade low-evidence issues
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
