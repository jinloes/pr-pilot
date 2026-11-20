import { useEffect, useRef, useState } from 'react'
import { CheckCircle2, Circle, Copy, ExternalLink, RefreshCw, Settings2, Terminal, TriangleAlert } from 'lucide-react'
import { sendToHost, type ProviderReadiness } from '../../bridge/types'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { LiveStatus } from '../a11y/LiveStatus'
import { setupRecoveryAction, setupSteps, type SetupReason } from './setupRecovery'

interface SetupScreenProps {
  reason: SetupReason
  detail: string
  providerReadiness?: ProviderReadiness
  ghAuthCommand?: string
  refreshing: boolean
  onRefresh: () => void
}

export function SetupScreen({
  reason,
  detail,
  providerReadiness,
  ghAuthCommand,
  refreshing,
  onRefresh,
}: SetupScreenProps) {
  const draftIndexUnavailable = reason === 'draft_index_unavailable'
  const title = draftIndexUnavailable
    ? 'Draft index needs attention'
    : reason === 'load_failed'
      ? 'Could not load pull requests'
      : reason === 'provider_not_installed' || reason === 'provider_not_authenticated'
        ? 'Review provider not ready'
        : 'GitHub not connected'
  const host = typeof (window as { acquireVsCodeApi?: unknown }).acquireVsCodeApi === 'function'
    ? 'VS Code'
    : 'IntelliJ'
  const steps = setupSteps(reason, providerReadiness)
  const recovery = setupRecoveryAction(reason, providerReadiness, ghAuthCommand)
  const guideUrl = recovery.guideUrl
  const [copyLabel, setCopyLabel] = useState<'copy' | 'copied' | 'failed'>('copy')
  const [lastCheckedAt, setLastCheckedAt] = useState<number | null>(null)
  const [liveAnnouncement, setLiveAnnouncement] = useState('')
  const prevRefreshingRef = useRef(refreshing)

  const authCommand = recovery.command
  const canRunInHost = host === 'VS Code' && recovery.canRunInHost === true

  useEffect(() => {
    if (prevRefreshingRef.current && !refreshing) {
      const completedAt = Date.now()
      setLastCheckedAt(completedAt)
      setLiveAnnouncement(`Status checked at ${new Date(completedAt).toLocaleTimeString()}.`)
    }
    prevRefreshingRef.current = refreshing
  }, [refreshing])

  async function handleCopyAuthCommand() {
    if (!authCommand) return
    try {
      await navigator.clipboard.writeText(authCommand)
      setCopyLabel('copied')
      setLiveAnnouncement('Authentication command copied to clipboard.')
      window.setTimeout(() => setCopyLabel('copy'), 1200)
    } catch {
      setCopyLabel('failed')
      setLiveAnnouncement('Failed to copy authentication command.')
      window.setTimeout(() => setCopyLabel('copy'), 1200)
    }
  }

  function runAuthFlow() {
    if (canRunInHost) {
      sendToHost({ type: 'runAuthLogin' })
      return
    }
    void handleCopyAuthCommand()
  }

  function checkStatus() {
    const checkedAt = Date.now()
    setLastCheckedAt(checkedAt)
    onRefresh()
  }

  return (
    <div className="flex min-h-full w-full">
      <div className="my-auto flex w-full flex-col items-center gap-5 px-6 py-6 text-center">
        <TriangleAlert className="w-10 h-10 text-status-suggestion shrink-0" />
        <div className="flex flex-col gap-2">
          <h1 className="text-sm font-semibold text-foreground">{title}</h1>
          <p className="break-words text-xs text-muted-foreground leading-relaxed">{detail}</p>
          <p className="text-[11px] text-muted-foreground">Detected host: {host}</p>
        </div>
        <ol
          aria-label="Setup checklist"
          className="m-0 w-full max-w-72 list-none rounded border border-border bg-card p-0 text-left"
        >
          {steps.map((step) => (
            <li key={step.label} className="flex items-start gap-2 border-b border-border last:border-b-0 px-3 py-2">
              {step.done ? (
                <CheckCircle2 aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 text-status-approve shrink-0" />
              ) : (
                <Circle aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 text-muted-foreground shrink-0" />
              )}
              <div className="min-w-0">
                <p className="text-xs text-foreground">
                  <span className="sr-only">{step.done ? 'Completed: ' : 'Incomplete: '}</span>
                  {step.label}
                </p>
                <p className="text-[11px] text-muted-foreground leading-snug">{step.detail}</p>
              </div>
            </li>
          ))}
        </ol>
        {authCommand && (
          <div className="w-full max-w-72 rounded border border-border bg-muted/20 px-3 py-2.5 text-left space-y-2">
            <p className="text-[11px] font-semibold text-foreground">{recovery.label}</p>
            <p className="break-all text-[11px] font-mono text-foreground">{authCommand}</p>
            <p className="mt-1 text-[11px] text-muted-foreground">
              {canRunInHost
                ? 'Open the integrated terminal and run GitHub CLI auth automatically.'
                : 'Copy the command, run it in your terminal, then re-check status.'}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              {canRunInHost && (
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 px-2 text-[11px] gap-1.5"
                  onClick={runAuthFlow}
                >
                  <Terminal className="w-3 h-3" />
                  Run gh auth login
                </Button>
              )}
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-[11px] gap-1.5"
                onClick={() => void handleCopyAuthCommand()}
              >
                <Copy className="w-3 h-3" />
                {copyLabel === 'copied'
                  ? 'Copied'
                  : copyLabel === 'failed'
                    ? 'Copy failed'
                    : 'Copy auth command'}
              </Button>
            </div>
          </div>
        )}
        {lastCheckedAt !== null && (
          <p className="text-[11px] text-muted-foreground">
            Last checked at {new Date(lastCheckedAt).toLocaleTimeString()}
          </p>
        )}
        <LiveStatus message={liveAnnouncement} />
        <div className="flex flex-wrap items-center justify-center gap-2">
          <Button
            variant="outline"
            size="sm"
            className="gap-1.5 text-xs"
            onClick={() => sendToHost({ type: 'openSettings' })}
          >
            <Settings2 className="w-3.5 h-3.5" />
            Open Settings
          </Button>
          {guideUrl && (
            <Button
              variant="outline"
              size="sm"
              className="h-auto min-h-8 max-w-full gap-1.5 whitespace-normal py-1.5 text-xs"
              onClick={() => sendToHost({ type: 'openUrl', url: guideUrl })}
            >
              <ExternalLink className="w-3.5 h-3.5" />
              {recovery.guideLabel}
            </Button>
          )}
          <Button variant="outline" size="sm" className="gap-1.5 text-xs" onClick={checkStatus} disabled={refreshing}>
            <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
            {recovery.kind === 'retry' ? recovery.label : 'Check status'}
          </Button>
        </div>
      </div>
    </div>
  )
}
