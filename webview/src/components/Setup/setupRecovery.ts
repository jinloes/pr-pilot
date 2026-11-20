import type { ProviderReadiness } from '../../bridge/types'

export type SetupReason =
  | 'gh_not_installed'
  | 'gh_not_authenticated'
  | 'provider_not_installed'
  | 'provider_not_authenticated'
  | 'load_failed'
  | 'draft_index_unavailable'

export interface SetupRecoveryAction {
  kind: 'install' | 'authenticate' | 'retry' | 'check'
  label: string
  command?: string
  guideLabel?: string
  guideUrl?: string
  canRunInHost?: boolean
}

const GH_INSTALL_URL = 'https://cli.github.com/'
const GH_AUTH_URL = 'https://cli.github.com/manual/gh_auth_login'
const CLAUDE_INSTALL_URL = 'https://code.claude.com/docs/en/setup'
const CLAUDE_AUTH_URL = 'https://code.claude.com/docs/en/authentication'
const COPILOT_INSTALL_URL = 'https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/install-copilot-cli'
const COPILOT_AUTH_URL = 'https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/authenticate-copilot-cli'

export function setupRecoveryAction(
  reason: SetupReason,
  provider?: ProviderReadiness,
  ghAuthCommand?: string,
): SetupRecoveryAction {
  switch (reason) {
    case 'gh_not_installed':
      return {
        kind: 'install',
        label: 'Install GitHub CLI',
        guideLabel: 'Open GitHub CLI install guide',
        guideUrl: GH_INSTALL_URL,
      }
    case 'gh_not_authenticated':
      return {
        kind: 'authenticate',
        label: 'Authenticate GitHub CLI',
        command: ghAuthCommand || 'gh auth login',
        guideLabel: 'Open GitHub CLI authentication guide',
        guideUrl: GH_AUTH_URL,
        canRunInHost: true,
      }
    case 'provider_not_installed': {
      const copilot = provider?.provider === 'copilot'
      return {
        kind: 'install',
        label: copilot ? 'Install GitHub Copilot CLI' : 'Install Claude Code CLI',
        guideLabel: copilot ? 'Open Copilot CLI install guide' : 'Open Claude Code install guide',
        guideUrl: copilot ? COPILOT_INSTALL_URL : CLAUDE_INSTALL_URL,
      }
    }
    case 'provider_not_authenticated': {
      const copilot = provider?.provider === 'copilot'
      const command = provider?.authCommand?.trim()
      return {
        kind: 'authenticate',
        label: copilot ? 'Authenticate GitHub Copilot CLI' : 'Authenticate Claude Code CLI',
        command: command || undefined,
        guideLabel: copilot ? 'Open Copilot CLI authentication guide' : 'Open Claude Code authentication guide',
        guideUrl: copilot ? COPILOT_AUTH_URL : CLAUDE_AUTH_URL,
      }
    }
    case 'load_failed':
      return { kind: 'retry', label: 'Retry' }
    case 'draft_index_unavailable':
      return { kind: 'check', label: 'Check status' }
  }
}

export function setupSteps(
  reason: SetupReason,
  provider?: ProviderReadiness,
): Array<{ label: string; detail: string; done: boolean }> {
  if (reason === 'draft_index_unavailable') {
    return [
      {
        label: 'Repair the preserved draft index',
        detail: 'Repair ~/.pr-pilot/pending-prs.json or use the IntelliJ notification action to quarantine it.',
        done: false,
      },
      {
        label: 'Refresh PR Pilot',
        detail: 'After repairing or quarantining the file, check status to reload pull requests safely.',
        done: false,
      },
    ]
  }
  return [
    {
      label: 'Install GitHub CLI',
      detail: 'PR Pilot uses gh for GitHub authentication and PR access.',
      done: reason !== 'gh_not_installed',
    },
    {
      label: 'Authenticate GitHub',
      detail: 'Run gh auth login for github.com or your Enterprise host.',
      done: reason === 'load_failed'
        || reason === 'provider_not_installed'
        || reason === 'provider_not_authenticated',
    },
    {
      label: `Install ${provider?.provider === 'copilot' ? 'GitHub Copilot CLI' : 'Claude Code CLI'}`,
      detail: provider?.detail ?? 'PR Pilot checks the selected provider before the first review.',
      done: provider?.binaryStatus === 'ready' || (provider?.available ?? false),
    },
    {
      label: 'Verify provider authentication',
      detail: provider?.authenticationStatus === 'unverified'
        ? `Authentication cannot be checked non-interactively. Run ${provider.authCommand ?? 'the provider sign-in command'} manually.`
        : provider?.authenticationStatus === 'ready'
          ? 'Provider authentication is ready.'
          : 'Authenticate the selected provider, then check status again.',
      done: provider?.authenticationStatus === 'ready',
    },
    {
      label: 'Load pull requests',
      detail: 'Refresh after setup; choose a search scope if the list is empty.',
      done: reason === 'provider_not_installed' || reason === 'provider_not_authenticated',
    },
  ]
}
