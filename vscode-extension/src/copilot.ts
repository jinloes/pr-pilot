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
import { parseReview } from './review';

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

function buildRuntimeEnv(): NodeJS.ProcessEnv {
    return {
        ...process.env,
        HOME: process.env.HOME || os.homedir(),
        PATH: `/opt/homebrew/bin:/usr/local/bin:${process.env.PATH ?? ''}`,
    };
}

interface ActiveRun {
    client: CopilotClient;
    session?: CopilotSession;
    cancelled: boolean;
}

let activeRuns = new Set<ActiveRun>();

export function cancelCurrentRequest(): void {
    const runs = activeRuns;
    activeRuns = new Set<ActiveRun>();
    for (const run of runs) {
        run.cancelled = true;
        void run.session?.abort().catch(() => undefined);
        void run.client.forceStop().catch(() => undefined);
    }
}

// ── Model discovery ───────────────────────────────────────────────────────────

/**
 * Cached list of Copilot model IDs. `null` means "not probed yet"; an empty array means a probe
 * ran but found nothing (binary missing, policy-blocked account, schema drift) — callers fall back
 * to their own hardcoded suggestions. Mirrors CopilotModelDiscovery's AtomicReference semantics.
 */
let modelCache: string[] | null = null;

/** Drops the cached model list so the next {@link listModels} call re-probes. */
export function invalidateModelCache(): void {
    modelCache = null;
}

interface ModelInfoLike {
    id?: unknown;
    policy?: { state?: unknown } | null;
}

/**
 * Extracts usable model IDs from `client.listModels()` output: drops policy-`disabled` models and
 * blank IDs, then de-dupes while preserving the SDK's ordering. Pure (no I/O) so it can be tested
 * without spawning the CLI — mirrors the role of CopilotModelDiscovery.parseModelsFromHelp.
 */
export function filterModelIds(models: ModelInfoLike[]): string[] {
    const ids = models
        .filter((m) => m.policy?.state !== 'disabled')
        .map((m) => m.id)
        .filter((id): id is string => typeof id === 'string' && id.trim().length > 0);
    return Array.from(new Set(ids));
}

/**
 * Returns the Copilot model IDs available to the current account, querying the SDK's
 * `client.listModels()` once and caching the result. Only models whose policy is not `disabled`
 * are returned. On any failure returns an empty array (and caches it) so the caller falls back to
 * its own suggestion list rather than blocking on a broken probe every call.
 *
 * Mirrors CopilotModelDiscovery.listModels (IntelliJ), but uses the SDK directly instead of
 * shelling out to `copilot help config`.
 */
export async function listModels(forceRefresh = false): Promise<string[]> {
    if (!forceRefresh && modelCache !== null) return modelCache;

    const client = new CopilotClient({
        connection: RuntimeConnection.forStdio({ path: findCopilotBinary() }),
        workingDirectory: os.homedir(),
        env: buildRuntimeEnv(),
        mode: 'copilot-cli',
    });
    try {
        await withTimeout(client.start(), SDK_BOOT_TIMEOUT_MS, 'runtime startup');
        const models = await withTimeout(client.listModels(), SDK_BOOT_TIMEOUT_MS, 'model discovery');
        modelCache = filterModelIds(models);
        return modelCache;
    } catch (err) {
        console.warn('[pr-pilot] Failed to probe copilot models:',
            err instanceof Error ? err.message : String(err));
        modelCache = [];
        return modelCache;
    } finally {
        await client.stop().catch(() => undefined);
    }
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
    inheritMcp?: boolean;
    configDir?: string;
    callbacks: ProcessCallbacks;
}): Promise<string> {
    const { prompt, model, effort, workingDir, inheritMcp, configDir, callbacks } = options;
    const runtimeEnv = buildRuntimeEnv();
    const client = new CopilotClient({
        connection: RuntimeConnection.forStdio({ path: findCopilotBinary() }),
        workingDirectory: workingDir || os.homedir(),
        env: runtimeEnv,
        mode: 'copilot-cli',
    });
    const run: ActiveRun = { client, cancelled: false };
    activeRuns.add(run);

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
            // When true, the SDK discovers MCP servers from the Copilot CLI config
            // (~/.copilot/mcp-config.json) and any repo-local .mcp.json, so the review/chat
            // session inherits the same tools (captain, workiq, github-mcp, …) as the CLI.
            enableConfigDiscovery: inheritMcp ?? true,
            ...(configDir && configDir.trim() ? { configDir: configDir.trim() } : {}),
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
        // ES2020 target lacks the Error(message, {cause}) overload; attach cause manually so
        // `preserve-caught-error` is satisfied without bumping the language target.
        if (run.cancelled) {
            const cancelled: Error & { cause?: unknown } = new Error('copilot request cancelled');
            cancelled.cause = err;
            throw cancelled;
        }
        if (err instanceof Error) {
            throw err;
        }
        const wrapped: Error & { cause?: unknown } = new Error(String(err));
        wrapped.cause = err;
        throw wrapped;
    } finally {
        unsubscribes.forEach((unsubscribe) => unsubscribe());
        activeRuns.delete(run);
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
    inheritMcp?: boolean;
    configDir?: string;
    onStatus: (status: string) => void;
    onChunk: (kind: 'text' | 'thinking', chunk: string) => void;
}): Promise<ReviewResult> {
    const { prompt, model, effort, workingDir, inheritMcp, configDir, onStatus, onChunk } = options;
    onStatus('Generating review…');
    const raw = await runSession({
        prompt,
        model,
        effort,
        workingDir,
        inheritMcp,
        configDir,
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
    inheritMcp?: boolean;
    configDir?: string;
    onChunk: (chunk: string) => void;
}): Promise<string> {
    const result = await runSession({
        prompt: options.prompt,
        model: '',
        effort: options.effort,
        workingDir: options.workingDir,
        inheritMcp: options.inheritMcp,
        configDir: options.configDir,
        callbacks: { onChunk: options.onChunk },
    });
    return result;
}
