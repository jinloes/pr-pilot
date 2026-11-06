import * as fs from 'fs';
import * as path from 'path';
import { parse as parseYaml } from 'yaml';
import { log } from './log';
import type { SidecarPrDiffResult } from './sidecar';

type ErrorContext =
    | 'load pull requests'
    | 'load PR details'
    | 'generate review'
    | 'save draft review'
    | 'submit draft review'
    | 'delete draft review'
    | 'answer chat question';

type TemplateKey =
    | 'github_auth_failed'
    | 'github_not_found_or_inaccessible'
    | 'github_diff_too_large'
    | 'provider_binary_missing'
    | 'provider_not_installed'
    | 'request_timed_out'
    | 'git_timed_out'
    | 'provider_start_timed_out'
    | 'step_timed_out'
    | 'invalid_review_format'
    | 'network_error'
    | 'tls_certificate_untrusted'
    | 'generic_failure';

/** Appended where the summary hides the cause, so the user can find the raw error. */
export const OUTPUT_PANEL_HINT = ' Details are in the PR Pilot output panel.';

const TEMPLATES = loadTemplates();

export class GitHubOperationError extends Error {
    constructor(
        readonly status: SidecarPrDiffResult['status'] | 'invalid_response',
        message: string,
    ) {
        super(message);
        this.name = 'GitHubOperationError';
    }
}

function messageOf(err: unknown): string {
    return (err instanceof Error ? err.message : String(err)).trim().toLowerCase();
}

function includesAny(source: string, needles: string[]): boolean {
    return needles.some((needle) => source.includes(needle));
}

function findTemplatePath(): string | null {
    let current = __dirname;
    for (let i = 0; i < 6; i++) {
        const candidate = path.join(current, 'shared', 'user-facing-errors.yaml');
        if (fs.existsSync(candidate)) return candidate;
        const parent = path.dirname(current);
        if (parent === current) break;
        current = parent;
    }
    return null;
}

function loadTemplates(): Record<TemplateKey, string> {
    const templatePath = findTemplatePath();
    if (!templatePath) {
        throw new Error(
            'user-facing-errors.yaml not found. Expected it under a "shared/" directory relative to the extension.',
        );
    }
    const parsed = parseYaml(fs.readFileSync(templatePath, 'utf8')) as {
        templates?: Partial<Record<TemplateKey, string>>;
    };
    if (!parsed.templates) {
        throw new Error(`${templatePath} is missing the 'templates' map`);
    }
    return parsed.templates as Record<TemplateKey, string>;
}

function template(key: TemplateKey, vars: Record<string, string>): string {
    let rendered = TEMPLATES[key] ?? key;
    for (const [name, value] of Object.entries(vars)) {
        rendered = rendered.split(`{${name}}`).join(value);
    }
    return rendered;
}

/**
 * Proactive copy for when the configured provider CLI is not installed/resolvable, surfaced by the
 * pre-generate preflight before any spawn is attempted. Wording is kept in sync with the IntelliJ
 * host (`UserFacingErrors.java`).
 */
export function providerNotInstalledMessage(provider: 'claude' | 'copilot'): string {
    return provider === 'copilot'
        ? template('provider_not_installed', { binary: 'copilot', provider_cli: 'GitHub Copilot CLI' })
        : template('provider_not_installed', { binary: 'claude', provider_cli: 'Claude Code CLI' });
}

/**
 * Names the step behind a timeout: a git command, Copilot runtime or session startup, or one
 * sidecar request. Anything else is the provider's own run, which keeps the generic advice.
 * Mirrors UserFacingErrors.timedOut (IntelliJ), minus the sidecar case IntelliJ cannot hit.
 */
function timeoutMessage(msg: string, raw: string, context: ErrorContext): string {
    const git = /\bgit ([a-z-]+) timed out/.exec(msg);
    if (git) return template('git_timed_out', { command: git[1], operation: context });
    if (/\bcopilot (runtime startup|session creation) timed out/.test(msg)) {
        return template('provider_start_timed_out', { operation: context });
    }
    const step = /sidecar request "([^"]+)" timed out/i.exec(raw);
    if (step) return template('step_timed_out', { step: step[1], operation: context });
    return template('request_timed_out', { operation: context });
}

export function toUserFacingError(err: unknown, context: ErrorContext): string {
    log(`[error] Couldn't ${context}: ${err instanceof Error ? err.message : String(err)}`);
    const message = mapError(err, context);
    return message.includes('timed out') || message === template('generic_failure', { operation: context })
        ? message + OUTPUT_PANEL_HINT
        : message;
}

function mapError(err: unknown, context: ErrorContext): string {
    const msg = messageOf(err);

    if (err instanceof GitHubOperationError && err.status === 'not_found_or_inaccessible') {
        return template('github_not_found_or_inaccessible', {});
    }
    if (err instanceof GitHubOperationError && err.status === 'diff_too_large') {
        return template('github_diff_too_large', {});
    }
    if (includesAny(msg, ['no github token configured', 'gh auth', 'authentication', 'unauthorized', 'forbidden'])) {
        return template('github_auth_failed', { auth_command: 'gh auth login' });
    }
    if (includesAny(msg, ['no such file', 'error=2'])) {
        if (msg.includes('copilot')) {
            return template('provider_binary_missing', {
                binary: 'copilot',
                provider_cli: 'GitHub Copilot CLI',
            });
        }
        if (msg.includes('claude')) {
            return template('provider_binary_missing', {
                binary: 'claude',
                provider_cli: 'Claude Code CLI',
            });
        }
    }
    if (includesAny(msg, ['timed out', 'timeout'])) {
        return timeoutMessage(msg, err instanceof Error ? err.message : String(err), context);
    }
    if (includesAny(msg, ['failed to parse review json', 'unexpected token', 'produced no output'])) {
        return template('invalid_review_format', {});
    }
    if (includesAny(msg, ['tls certificate', 'pkix path'])) {
        return template('tls_certificate_untrusted', { operation: context });
    }
    if (includesAny(msg, ['connection refused', 'unknownhost', 'enotfound', 'network', 'unable to reach'])) {
        return template('network_error', { operation: context });
    }

    return template('generic_failure', { operation: context });
}
