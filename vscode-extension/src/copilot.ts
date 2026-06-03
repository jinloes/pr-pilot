import * as fs from 'fs';
import * as os from 'os';
import {
    CopilotClient,
    RuntimeConnection,
    approveAll,
    type CopilotSession,
} from '@github/copilot-sdk';
import type { ReviewResult, LineComment } from './github';
import type { ChatMessage, PR } from './claude';
import { buildPrompt, buildChatPrompt, buildFocusedChatPrompt } from './claude';

export type { ReviewResult, LineComment, ChatMessage, PR };
export { buildPrompt, buildChatPrompt, buildFocusedChatPrompt };

// ── Constants ─────────────────────────────────────────────────────────────────

/**
 * Sane default for PR review work: enough depth to catch real bugs and follow the strict JSON
 * schema, without burning the latency of `high`/`xhigh`/`max`.
 */
export const DEFAULT_REASONING_EFFORT = 'medium';
export const SDK_BOOT_TIMEOUT_MS = 60 * 1000;
const REQUEST_TIMEOUT_MS = 30 * 60 * 1000;

// ── Binary resolution ──────────────────────────────────────────────────────────

function findCopilotBinary(): string {
    const home = process.env.HOME || os.homedir();
    const candidates = [
        `${home}/.local/bin/copilot`,
        `${home}/.npm-global/bin/copilot`,
        '/usr/local/bin/copilot',
        '/opt/homebrew/bin/copilot',
        '/usr/bin/copilot',
    ];
    for (const p of candidates) {
        try { if (fs.statSync(p).isFile()) return p; } catch { /* not found */ }
    }
    return 'copilot';
}

type ReasoningEffort = 'low' | 'medium' | 'high' | 'xhigh';

export function normalizeReasoningEffort(effort: string): ReasoningEffort {
    switch (effort.trim().toLowerCase()) {
        case 'low':
        case 'medium':
        case 'high':
        case 'xhigh':
            return effort.trim().toLowerCase() as ReasoningEffort;
        case 'none':
            return 'low';
        case 'max':
            return 'xhigh';
        default:
            return DEFAULT_REASONING_EFFORT;
    }
}

// ── Best-effort review JSON extraction ────────────────────────────────────────

function parseReview(raw: string): ReviewResult {
    let json = raw.trim();
    if (json.startsWith('```')) {
        const newline = json.indexOf('\n');
        const closing = json.lastIndexOf('```');
        if (newline > 0 && closing > newline) json = json.substring(newline + 1, closing).trim();
    }
    const start = json.indexOf('{');
    const end = json.lastIndexOf('}');
    if (start >= 0 && end > start) json = json.substring(start, end + 1);
    return JSON.parse(json) as ReviewResult;
}

function stringOrUndef(v: unknown): string | undefined {
    return typeof v === 'string' ? v : undefined;
}

function asObj(v: unknown): Record<string, unknown> | undefined {
    return typeof v === 'object' && v !== null && !Array.isArray(v)
        ? (v as Record<string, unknown>)
        : undefined;
}

export async function withTimeout<T>(promise: Promise<T>, timeoutMs: number, operation: string): Promise<T> {
    const timeoutSeconds = Math.max(1, Math.ceil(timeoutMs / 1000));
    let timer: NodeJS.Timeout | undefined;
    const timeoutPromise = new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
            reject(new Error(`copilot ${operation} timed out after ${timeoutSeconds}s`));
        }, timeoutMs);
    });
    try {
        return await Promise.race([promise, timeoutPromise]);
    } finally {
        if (timer) {
            clearTimeout(timer);
        }
    }
}

// ── Runtime management ────────────────────────────────────────────────────────

interface ActiveRun {
    client: CopilotClient;
    session?: CopilotSession;
    cancelled: boolean;
}

let activeRun: ActiveRun | null = null;

export function cancelCurrentRequest(): void {
    const run = activeRun;
    activeRun = null;
    if (!run) return;
    run.cancelled = true;
    void run.session?.abort().catch(() => undefined);
    void run.client.forceStop().catch(() => undefined);
}

interface ProcessCallbacks {
    onChunk?: (chunk: string) => void;
    onTool?: (toolName: string) => void;
}

async function runSession(options: {
    prompt: string;
    model: string;
    effort: string;
    workingDir?: string;
    callbacks: ProcessCallbacks;
}): Promise<string> {
    const { prompt, model, effort, workingDir, callbacks } = options;
    const runtimeEnv = {
        ...process.env,
        HOME: process.env.HOME || os.homedir(),
        PATH: `/opt/homebrew/bin:/usr/local/bin:${process.env.PATH ?? ''}`,
    };
    const client = new CopilotClient({
        connection: RuntimeConnection.forStdio({ path: findCopilotBinary() }),
        workingDirectory: workingDir || os.homedir(),
        env: runtimeEnv,
        mode: 'copilot-cli',
    });
    const run: ActiveRun = { client, cancelled: false };
    activeRun = run;

    let session: CopilotSession | undefined;
    const unsubscribes: Array<() => void> = [];
    let finalMessage = '';
    let sessionError = '';
    const deltaBuffer: string[] = [];

    try {
        await withTimeout(client.start(), SDK_BOOT_TIMEOUT_MS, 'runtime startup');
        session = await withTimeout(client.createSession({
            model: model.trim() || undefined,
            reasoningEffort: normalizeReasoningEffort(effort),
            onPermissionRequest: approveAll,
            streaming: true,
        }), SDK_BOOT_TIMEOUT_MS, 'session creation');
        run.session = session;

        unsubscribes.push(session.on('assistant.message_delta', (event) => {
            const delta = event.data.deltaContent;
            if (delta && delta.length > 0) {
                deltaBuffer.push(delta);
                callbacks.onChunk?.(delta);
            }
        }));
        unsubscribes.push(session.on('assistant.message', (event) => {
            const content = event.data.content;
            if (content && content.trim().length > 0) {
                finalMessage = content;
            }
        }));
        unsubscribes.push(session.on('tool.execution_start', (event) => {
            const toolName = event.data.toolName;
            if (toolName && toolName.trim().length > 0) {
                callbacks.onTool?.(toolName);
            }
        }));
        unsubscribes.push(session.on('session.error', (event) => {
            const message = event.data.message;
            if (!sessionError && message && message.trim().length > 0) {
                sessionError = message;
            }
        }));

        const response = await session.sendAndWait(prompt, REQUEST_TIMEOUT_MS);
        if (!finalMessage && response?.data.content?.trim()) {
            finalMessage = response.data.content;
        }

        const raw = finalMessage.trim() ? finalMessage : deltaBuffer.join('');
        if (!raw.trim() && sessionError.trim()) {
            throw new Error(sessionError);
        }
        return raw;
    } catch (err) {
        if (run.cancelled) {
            throw new Error('copilot request cancelled');
        }
        if (err instanceof Error) {
            throw err;
        }
        throw new Error(String(err));
    } finally {
        unsubscribes.forEach((unsubscribe) => unsubscribe());
        if (activeRun === run) activeRun = null;
        await session?.disconnect().catch(() => undefined);
        if (run.cancelled) {
            await client.forceStop().catch(() => undefined);
        } else {
            await client.stop().catch(() => undefined);
        }
    }
}

// ── Review ─────────────────────────────────────────────────────────────────────

export async function reviewPR(options: {
    prompt: string;
    model: string;
    effort: string;
    workingDir?: string;
    onStatus: (status: string) => void;
    onChunk: (kind: 'text' | 'thinking', chunk: string) => void;
}): Promise<ReviewResult> {
    const { prompt, model, effort, workingDir, onStatus, onChunk } = options;
    onStatus('Generating review…');
    const raw = await runSession({
        prompt,
        model,
        effort,
        workingDir,
        callbacks: {
            onChunk: (chunk) => onChunk('text', chunk),
            onTool: (name) => onStatus(name),
        },
    });
    if (!raw.trim()) {
        throw new Error('copilot produced no output.');
    }
    onStatus('Parsing review…');
    try {
        return parseReview(raw);
    } catch (e) {
        // ES2020 target lacks the Error(message, {cause}) overload; attach cause manually so
        // `preserve-caught-error` is satisfied without bumping the language target.
        const wrapped: Error & { cause?: unknown } = new Error(
            `Failed to parse review JSON from copilot output: ${e instanceof Error ? e.message : String(e)}`,
        );
        wrapped.cause = e;
        throw wrapped;
    }
}

// ── Chat ───────────────────────────────────────────────────────────────────────

export async function chat(options: {
    prompt: string;
    effort: string;
    workingDir?: string;
    onChunk: (chunk: string) => void;
}): Promise<string> {
    const result = await runSession({
        prompt: options.prompt,
        model: '',
        effort: options.effort,
        workingDir: options.workingDir,
        callbacks: { onChunk: options.onChunk },
    });
    return result;
}
