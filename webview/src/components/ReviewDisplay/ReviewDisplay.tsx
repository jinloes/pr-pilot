import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import type { ReviewResult } from '../../bridge/types'
import { MarkdownContent } from '../MarkdownContent/MarkdownContent'

interface Props {
  result: ReviewResult
}

const VERDICT_LABEL: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'Suggested: Approve',
  REQUEST_CHANGES: 'Suggested: Request changes',
  COMMENT: 'Suggested: Comment',
}

const VERDICT_CLASS: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'text-status-approve border-status-approve/50 bg-status-approve/10 hover:bg-status-approve/10',
  REQUEST_CHANGES: 'text-status-changes border-status-changes/50 bg-status-changes/10 hover:bg-status-changes/10',
  COMMENT: 'text-status-comment border-status-comment/50 bg-status-comment/10 hover:bg-status-comment/10',
}

export function ReviewDisplay({ result }: Props) {
  return (
    <div className="flex flex-col gap-3 p-4">
      <Badge
        variant="outline"
        className={cn('w-fit text-xs font-semibold tracking-wide', VERDICT_CLASS[result.verdict])}
      >
        {VERDICT_LABEL[result.verdict]}
      </Badge>
      <MarkdownContent className="text-foreground/90 [&_h1]:text-sm [&_h2]:text-sm [&_h3]:text-sm [&_strong]:text-foreground">
        {result.summary}
      </MarkdownContent>
    </div>
  )
}
