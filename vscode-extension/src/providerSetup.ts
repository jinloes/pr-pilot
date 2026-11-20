import { normalizeGithubBaseUrl } from './settingsView';

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

export type ClaudeAuthProbeRunner = (
    complete: (error: Error | null, stdout: string, stderr: string) => void,
) => void;

export function classifyClaudeAuthProbe(
    error: Error | null,
    stdout: string,
    stderr: string,
): ProviderAuthenticationStatus {
    const output = `${stdout}\n${stderr}`.toLowerCase();
    if (output.includes('unknown command')
        || output.includes('unrecognized command')
        || output.includes('unsupported command')
        || output.includes('invalid command')
        || output.includes('unknown option')
        || output.includes('unrecognized option')
        || output.includes('unexpected argument')) {
        return 'unverified';
    }
    if (!error) return 'ready';

    const processError = error as Error & { code?: unknown; killed?: boolean; signal?: unknown };
    if (processError.killed || processError.signal || typeof processError.code !== 'number') {
        return 'unverified';
    }
    return output.includes('not logged in')
        || output.includes('not authenticated')
        || output.includes('signed out')
        || output.includes('logged out')
        ? 'unavailable'
        : 'unverified';
}

export async function probeClaudeAuthentication(
    run: ClaudeAuthProbeRunner,
): Promise<ProviderAuthenticationStatus> {
    return new Promise((resolve) => {
        try {
            run((error, stdout, stderr) => resolve(classifyClaudeAuthProbe(error, stdout, stderr)));
        } catch {
            resolve('unverified');
        }
    });
}
