import type { ReviewResult } from '../../bridge/types'
import { MarkdownContent } from '../MarkdownContent/MarkdownContent'

interface Props {
  result: ReviewResult
}

export function ReviewDisplay({ result }: Props) {
  return (
    <div className="flex flex-col gap-2 px-4 py-2.5">
      <MarkdownContent className="text-foreground/90 [&_h1]:text-sm [&_h2]:text-sm [&_h3]:text-sm [&_strong]:text-foreground">
        {result.summary}
      </MarkdownContent>
    </div>
  )
}
