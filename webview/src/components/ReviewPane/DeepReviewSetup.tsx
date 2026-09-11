import { useState } from 'react'
import type { DeepReviewPreparedMessage, RetainedDeepReview } from '../../bridge/types'
import { Button } from '@/components/ui/button'

interface Props {
  setup: DeepReviewPreparedMessage | null
  retained: RetainedDeepReview[]
  busy: boolean
  error: string
  onContinue: (server: string) => void
  onOrdinary: () => void
  onCancel: () => void
  onList: () => void
  onCleanup: (id: string) => void
}

/** No automatic IDE launch, synchronization, fallback or worktree removal. */
export function DeepReviewSetup({ setup, retained, busy, error, onContinue, onOrdinary, onCancel, onList, onCleanup }: Props) {
  const [selected, setSelected] = useState('')
  const [confirmed, setConfirmed] = useState<string | null>(null)
  const server = setup?.servers.includes(selected) ? selected : setup?.servers[0] ?? ''
  return <section aria-label="IntelliJ-assisted review setup" className="max-h-[50vh] shrink-0 overflow-y-auto space-y-2 border-b border-border p-3 text-xs">
    {setup && <>
      <h3 className="font-semibold">IntelliJ-assisted review — {busy ? 'checking authority' : 'paused'}</h3>
      <p role="status">{setup.message}</p>
      <p className="break-all">Exact worktree: <code>{setup.worktree}</code></p>
      <p className="break-all">Pinned head: <code>{setup.head}</code></p>
      <p>Open this worktree in IntelliJ, enable MCP, finish Gradle import and save all files.
        A newly armed tracker requires one explicit manual Gradle sync. No provider runs while paused.
        The worktree is retained after success, failure, cancellation or closing this panel.</p>
      <p>Trusting or importing a pull request can execute its build logic. Inspect it first.
        A separate IDE instance is not a sandbox. PR Pilot never opens, trusts, imports or saves it for you.</p>
      {busy && <p role="status">Streamed output is provisional until final authority validation succeeds.</p>}
      <label className="block">Configured MCP server
        <select className="ml-2 rounded border border-border bg-background" value={server}
          onChange={e => setSelected(e.target.value)} disabled={busy}>
          {setup.servers.map(name => <option key={name} value={name}>{name}</option>)}
        </select>
      </label>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" disabled={busy || !server} onClick={() => onContinue(server)}>Continue / Retry</Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={onOrdinary}>Use ordinary review instead</Button>
        <Button size="sm" variant="outline" onClick={onCancel}>Cancel IntelliJ-assisted review</Button>
      </div>
    </>}
    <details>
      <summary className="cursor-pointer">Retained IntelliJ review worktrees</summary>
      <p>Close each worktree project in every IDE before removal. Active leases and dirty trees cannot be removed.</p>
      <Button size="sm" variant="outline" onClick={onList}>Refresh retained worktrees</Button>
      {error && <p role="alert">{error}</p>}
      <ul>{retained.map(tree => <li key={tree.id} className="my-2 space-y-1">
        <code className="block break-all">{tree.worktree}</code>
        <label className="block"><input type="checkbox" checked={confirmed === tree.id}
          onChange={e => setConfirmed(e.target.checked ? tree.id : null)} /> I closed this exact project in every IDE</label>
        <Button size="sm" variant="outline" disabled={confirmed !== tree.id}
          onClick={() => { setConfirmed(null); onCleanup(tree.id) }}>Remove retained worktree</Button>
      </li>)}</ul>
    </details>
  </section>
}
