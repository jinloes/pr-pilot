import type { Provider, ProviderReadiness } from './extensionTypes';
import type { SidecarProviderSetupResult } from './sidecarTypes';
import { normalizeGithubBaseUrl } from './settingsView';
import { providerNotInstalledMessage } from './userFacingError';

export type ProviderAuthenticationStatus = 'ready' | 'unavailable' | 'unverified';

const MAX_DNS_HOSTNAME_LENGTH = 253;
const SAFE_HOSTNAME_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/i;

export function buildGhAuthLoginCommand(rawBaseUrl: string): string {
    try {
        const normalized = normalizeGithubBaseUrl(rawBaseUrl);
        const hostname = new URL(normalized).hostname.toLowerCase();
        if (
            hostname
            && hostname.length <= MAX_DNS_HOSTNAME_LENGTH
            && hostname !== 'github.com'
            && SAFE_HOSTNAME_PATTERN.test(hostname)
        ) {
            return `gh auth login --hostname ${hostname}`;
        }
    } catch {
        // Invalid base URLs fall back to the default GitHub CLI login command.
    }
    return 'gh auth login';
}

/**
 * Formats a sidecar `reviews/probeProviderSetup` result into the host `ProviderReadiness` shape
 * sent to the webview.
 */
export function toProviderReadiness(
    provider: Provider,
    probe: SidecarProviderSetupResult,
): ProviderReadiness {
    if (!probe.available) {
        return {
            provider,
            available: false,
            binaryStatus: 'missing',
            authenticationStatus: 'unavailable',
            authCommand: probe.authCommand,
            detail: providerNotInstalledMessage(provider),
        };
    }
    if (provider === 'copilot') {
        return {
            provider,
            available: true,
            binaryStatus: probe.binaryStatus,
            authenticationStatus: probe.authenticationStatus,
            authCommand: probe.authCommand,
            detail: 'Provider CLI found. Authentication cannot be verified without starting a provider session.',
        };
    }
    const detail = probe.authenticationStatus === 'ready'
        ? 'Provider CLI and authentication are ready.'
        : probe.authenticationStatus === 'unavailable'
            ? `Claude authentication is unavailable. Run '${probe.authCommand}' and check again.`
            : `Claude authentication could not be verified non-interactively; run '${probe.authCommand}' if sign-in is required.`;
    return {
        provider,
        available: true,
        binaryStatus: probe.binaryStatus,
        authenticationStatus: probe.authenticationStatus,
        authCommand: probe.authCommand,
        detail,
    };
}
