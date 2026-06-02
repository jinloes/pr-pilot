import * as fs from 'fs';
import * as path from 'path';
import { parse as parseYaml } from 'yaml';

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
    | 'provider_binary_missing'
    | 'request_timed_out'
    | 'invalid_review_format'
    | 'network_error'
    | 'generic_failure';

const TEMPLATES = loadTemplates();

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

export function toUserFacingError(err: unknown, context: ErrorContext): string {
    const msg = messageOf(err);

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
        return template('request_timed_out', { operation: context });
    }
    if (includesAny(msg, ['failed to parse review json', 'unexpected token', 'produced no output'])) {
        return template('invalid_review_format', {});
    }
    if (includesAny(msg, ['connection refused', 'unknownhost', 'enotfound', 'network'])) {
        return template('network_error', { operation: context });
    }

    return template('generic_failure', { operation: context });
}
