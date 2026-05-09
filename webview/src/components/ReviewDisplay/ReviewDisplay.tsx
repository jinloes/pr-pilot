import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import type { ReviewResult } from '../../bridge/types'

interface Props {
  result: ReviewResult
}

const VERDICT_LABEL: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'Approve',
  REQUEST_CHANGES: 'Request Changes',
  COMMENT: 'Comment',
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
      <div className="prose prose-sm prose-invert max-w-none text-sm text-foreground/90 [&_code]:font-mono [&_code]:text-xs [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:rounded [&_ul]:my-1 [&_li]:my-0.5 [&_h1]:text-sm [&_h2]:text-sm [&_h3]:text-sm [&_h1]:font-semibold [&_h2]:font-semibold [&_h3]:font-semibold [&_p]:my-1 [&_a]:text-primary [&_strong]:text-foreground">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{result.summary}</ReactMarkdown>
      </div>
    </div>
  )
}
