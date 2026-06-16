export type SetupReason = 'gh_not_installed' | 'gh_not_authenticated' | 'load_failed';

/**
 * Classifies startup/refresh failures that should route users to the setup screen.
 * Returns "load_failed" when the error is not auth/install related.
 */
export function classifySetupAuthError(err: unknown): SetupReason {
    const errMsg = (err instanceof Error ? err.message : String(err)).toLowerCase();

    const notInstalled =
        errMsg.includes('enoent')
        || errMsg.includes('no such file')
        || errMsg.includes('error=2')
        || errMsg.includes('command not found');
    if (notInstalled) return 'gh_not_installed';

    const authRelated =
        errMsg.includes('gh auth')
        || errMsg.includes('auth token')
        || errMsg.includes('bad credentials')
        || errMsg.includes('unauthorized')
        || errMsg.includes('forbidden')
        || errMsg.includes(' 401')
        || errMsg.includes(' 403');

    return authRelated ? 'gh_not_authenticated' : 'load_failed';
}
