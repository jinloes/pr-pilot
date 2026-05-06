import type { ReviewResult } from '../../bridge/types'
import './ReviewDisplay.css'

interface Props {
  result: ReviewResult
}

export function ReviewDisplay({ result }: Props) {
  return (
    <div className="rd">
      <div className="rd__summary">
        <VerdictBadge verdict={result.verdict} />
        <MarkdownSummary text={result.summary} />
      </div>
    </div>
  )
}

// ── Verdict badge ─────────────────────────────────────────────────────────────

const VERDICT_LABEL: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'Approve',
  REQUEST_CHANGES: 'Request Changes',
  COMMENT: 'Comment',
}

function VerdictBadge({ verdict }: { verdict: ReviewResult['verdict'] }) {
  return (
    <span className={`rd__verdict rd__verdict--${verdict.toLowerCase().replace('_', '-')}`}>
      {VERDICT_LABEL[verdict]}
    </span>
  )
}

// ── Markdown summary renderer ─────────────────────────────────────────────────
// Handles ## headings, - bullets, `inline code`, **bold** from Claude's output.

type InlineNode = string | JSX.Element

function renderInline(text: string): InlineNode[] {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`)/).map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={i}>{part.slice(2, -2)}</strong>
    }
    if (part.startsWith('`') && part.endsWith('`')) {
      return <code key={i} className="rd__md-code">{part.slice(1, -1)}</code>
    }
    return part
  })
}

function MarkdownSummary({ text }: { text: string }) {
  const elements: JSX.Element[] = []
  let bullets: string[] = []
  let key = 0

  function flushBullets() {
    if (bullets.length > 0) {
      elements.push(
        <ul key={key++} className="rd__md-list">
          {bullets.map((b, i) => (
            <li key={i} className="rd__md-li">
              {renderInline(b)}
            </li>
          ))}
        </ul>,
      )
      bullets = []
    }
  }

  for (const line of text.split('\n')) {
    const heading = line.match(/^#{1,3}\s+(.+)/)
    const bullet = line.match(/^[-*]\s+(.+)/)
    if (heading) {
      flushBullets()
      elements.push(
        <p key={key++} className="rd__md-heading">
          {heading[1]}
        </p>,
      )
    } else if (bullet) {
      bullets.push(bullet[1])
    } else if (line.trim() === '') {
      flushBullets()
    } else {
      flushBullets()
      elements.push(
        <p key={key++} className="rd__md-p">
          {renderInline(line)}
        </p>,
      )
    }
  }
  flushBullets()

  return <div className="rd__md">{elements}</div>
}
