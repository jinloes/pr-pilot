import { CheckCircle2, Info, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useI18n } from '@/i18n/I18nProvider'
import { cn } from '@/lib/utils'
import type { ProviderReadiness } from '../../bridge/types'

interface Props {
  providerReadiness: ProviderReadiness | null
  recoveredSetup: boolean
  onDismiss: () => void
}

export function ReadinessCoach({ providerReadiness, recoveredSetup, onDismiss }: Props) {
  const t = useI18n()
  const unverified = providerReadiness?.authenticationStatus === 'unverified'
  const Icon = unverified ? Info : CheckCircle2
  const readiness = unverified
    ? t(providerReadiness.provider === 'copilot' ? 'readiness.copilotUnverified' : 'readiness.claudeUnverified')
    : recoveredSetup
      ? 'GitHub and the review provider are ready.'
      : 'PR Pilot is ready.'

  return (
    <div className={cn(
      'shrink-0 border-b border-border px-3 py-1.5',
      unverified ? 'bg-muted' : 'bg-status-approve/5',
    )} role="status">
      <div className="flex items-start gap-2">
        <Icon className={cn(
          'mt-0.5 h-4 w-4 shrink-0',
          unverified ? 'text-muted-foreground' : 'text-status-approve',
        )} aria-hidden="true" />
        <p className={cn(
          'min-w-0 flex-1 break-words text-xs leading-5',
          unverified ? 'text-muted-foreground' : 'text-foreground',
        )}>
          <span className="font-semibold">{readiness}</span>{' '}
          Choose a pull request to start.
        </p>
        <Button
          variant="ghost"
          size="sm"
          className="h-6 w-6 shrink-0 p-0 text-muted-foreground"
          onClick={onDismiss}
          aria-label="Dismiss readiness message"
        >
          <X className="h-3.5 w-3.5" aria-hidden="true" />
        </Button>
      </div>
    </div>
  )
}
