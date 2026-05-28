import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { spawn, ChildProcess } from 'child_process';
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

// ── JSONL event parser ────────────────────────────────────────────────────────

interface CopilotEvent {
    text?: string;
    tool?: string;
    /**
     * If true, `text` is the consolidated content of a finalized message and the caller should
     * clear any previously-accumulated text. Used to discard intermediate-turn commentary so only
     * the last turn's content (the review JSON) survives into parseReview.
     */
    replacesText?: boolean;
}

/**
 * Permissive JSONL event parser — same shape coverage as `CopilotService.parseCopilotEvent`:
 *   A. simple `{type, text|name}` events
 *   B. Claude streaming (`content_block_delta`, `content_block_start`)
 *   C. Claude full message (`{type:"assistant", message:{content:[...]}}`)
 *   D. OpenAI-style `{choices:[{delta:{content,tool_calls}}]}`
 * Returns an empty object for unknown shapes so the caller can count and warn without crashing.
 */
export function parseCopilotEvent(line: string): CopilotEvent {
    let obj: Record<string, unknown>;
    try {
        const parsed: unknown = JSON.parse(line);
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return {};
        obj = parsed as Record<string, unknown>;
    } catch {
        return {};
    }

    const type = stringOrUndef(obj['type']);

    // Shape A
    if (type === 'text') return { text: stringOrUndef(obj['text']) };
    if (type === 'tool_use' || type === 'tool_call' || type === 'tool') {
        return {
            tool:
                stringOrUndef(obj['name']) ??
                stringOrUndef(obj['tool']) ??
                stringOrUndef(asObj(obj['function'])?.['name']),
        };
    }
    if (type === 'response') {
        return {
            text: stringOrUndef(obj['response']) ?? stringOrUndef(obj['text']),
        };
    }

    // Shape E: Copilot CLI's actual JSONL schema (confirmed against v1.0.54 output). Mirrors
    // CopilotService.parseCopilotEvent — keep these in sync. See the Kotlin comment for the full
    // rationale on why we use deltas for streaming + assistant.message for the final buffer.
    if (type === 'assistant.message_delta') {
        return { text: stringOrUndef(asObj(obj['data'])?.['deltaContent']) };
    }
    if (type === 'assistant.message') {
        const content = stringOrUndef(asObj(obj['data'])?.['content']);
        if (content) return { text: content, replacesText: true };
    }
    if (type === 'tool.execution_start') {
        return { tool: stringOrUndef(asObj(obj['data'])?.['toolName']) };
    }

    // Shape B
    if (type === 'content_block_delta') {
        const delta = asObj(obj['delta']);
        const deltaType = stringOrUndef(delta?.['type']);
        if (deltaType === 'text_delta' || deltaType === 'input_text_delta') {
            return { text: stringOrUndef(delta?.['text']) };
        }
    }
    if (type === 'content_block_start') {
        const block = asObj(obj['content_block']);
        const blockType = stringOrUndef(block?.['type']);
        if (blockType === 'text') return { text: stringOrUndef(block?.['text']) };
        if (blockType === 'tool_use') return { tool: stringOrUndef(block?.['name']) };
    }

    // Shape C
    if (type === 'assistant') {
        const content = asObj(obj['message'])?.['content'];
        if (Array.isArray(content)) {
            let text = '';
            let tool: string | undefined;
            for (const block of content) {
                if (typeof block !== 'object' || block === null) continue;
                const blockObj = block as Record<string, unknown>;
                const blockType = stringOrUndef(blockObj['type']);
                if (blockType === 'text') {
                    const t = stringOrUndef(blockObj['text']);
                    if (t) text += t;
                } else if (blockType === 'tool_use' && tool === undefined) {
                    tool = stringOrUndef(blockObj['name']);
                }
            }
            if (text.length > 0 || tool !== undefined) return { text: text || undefined, tool };
        }
    }

    // Shape D
    const choices = obj['choices'];
    if (Array.isArray(choices) && choices.length > 0) {
        const first = choices[0] as Record<string, unknown>;
        const delta = asObj(first['delta']) ?? asObj(first['message']);
        if (delta) {
            const text = stringOrUndef(delta['content']);
            const toolCalls = delta['tool_calls'];
            let tool: string | undefined;
            if (Array.isArray(toolCalls) && toolCalls.length > 0) {
                const fn = asObj((toolCalls[0] as Record<string, unknown>)['function']);
                tool = stringOrUndef(fn?.['name']);
            }
            if (text || tool) return { text, tool };
        }
    }

    return {};
}

function stringOrUndef(v: unknown): string | undefined {
    return typeof v === 'string' ? v : undefined;
}

/**
 * Joins exit code, stderr, and sampled unparseable stdout into a single error string. Copilot
 * writes user-facing errors (policy block, auth failure, unknown flag) as plain text on stdout
 * in JSON-output mode, so we have to dig them out of the sample buffer.
 */
export function buildExitErrorMessage(
    exitCode: number,
    stderr: string,
    unparseableSample: string[],
): string {
    const parts = [`copilot exited ${exitCode}`];
    if (stderr.trim()) parts.push(stderr.trim());
    if (unparseableSample.length > 0) parts.push(unparseableSample.join('\n').trim());
    return parts.join(': ');
}

/**
 * Builds a diagnostic detail string for the "produced no output" error path. Includes the first
 * unparseable lines (if any) and always points at the stdout file so the user has a concrete
 * artifact to share when reporting the issue.
 */
export function buildNoOutputDetail(unparseableSample: string[], stdoutFile: string): string {
    const parts: string[] = [];
    if (unparseableSample.length === 0) {
        parts.push(
            'The CLI exited cleanly but emitted no events we recognized — the review prompt may have been rejected or the model failed silently.',
        );
    } else {
        parts.push(
            `The CLI emitted ${unparseableSample.length} unrecognized stdout line(s). First lines:\n${unparseableSample.join('\n')}`,
        );
    }
    parts.push(`Full stdout at ${stdoutFile}`);
    return parts.join(' ');
}

function asObj(v: unknown): Record<string, unknown> | undefined {
    return typeof v === 'object' && v !== null && !Array.isArray(v)
        ? (v as Record<string, unknown>)
        : undefined;
}

// ── Process management ─────────────────────────────────────────────────────────

let activeProcess: ChildProcess | null = null;

export function cancelCurrentRequest(): void {
    const p = activeProcess;
    activeProcess = null;
    p?.kill('SIGKILL');
}

interface ProcessCallbacks {
    onStatus?: (status: string) => void;
    onChunk?: (text: string) => void;
    onTool?: (name: string) => void;
}

interface RunResult {
    text: string;
    unparseableSample: string[];
    stdoutFile: string;
}

function runProcess(options: {
    prompt: string;
    model: string;
    effort: string;
    workingDir?: string;
    callbacks: ProcessCallbacks;
}): Promise<RunResult> {
    return new Promise((resolve, reject) => {
        const { prompt, model, effort, workingDir, callbacks } = options;
        const resolvedEffort = effort && effort.trim().length > 0 ? effort : DEFAULT_REASONING_EFFORT;
        const args = [
            '-p', prompt,
            '--allow-all-tools',
            '--no-color',
            '--output-format', 'json',
            '--stream', 'on',
            '--reasoning-effort', resolvedEffort,
        ];
        if (model) { args.push('--model', model); }

        const stdoutFile = path.join(os.tmpdir(), `copilot-stdout-${Date.now()}.ndjson`);
        const teeStream = fs.createWriteStream(stdoutFile, { encoding: 'utf8' });

        const proc = spawn(findCopilotBinary(), args, {
            cwd: workingDir || os.homedir(),
            env: {
                ...process.env,
                HOME: process.env.HOME || os.homedir(),
                PATH: `/opt/homebrew/bin:/usr/local/bin:${process.env.PATH ?? ''}`,
            },
        });
        activeProcess = proc;

        let stdoutBuf = '';
        const textBuffer: string[] = [];
        let stderrBuf = '';
        const unparseableSample: string[] = [];
        const MAX_UNPARSEABLE_SAMPLES = 10;
        const MAX_UNPARSEABLE_LINE_LEN = 500;

        proc.stdout.on('data', (chunk: Buffer) => {
            const text = chunk.toString('utf8');
            teeStream.write(text);
            stdoutBuf += text;
            const lines = stdoutBuf.split('\n');
            stdoutBuf = lines.pop() ?? '';
            for (const line of lines) {
                if (!line.trim()) continue;
                const event = parseCopilotEvent(line);
                if (!event.text && !event.tool) {
                    if (unparseableSample.length < MAX_UNPARSEABLE_SAMPLES) {
                        unparseableSample.push(line.slice(0, MAX_UNPARSEABLE_LINE_LEN));
                    }
                    continue;
                }
                if (event.text) {
                    if (event.replacesText) {
                        // Consolidated turn content — discard accumulated deltas so the buffer
                        // ends each turn with just that turn's content. We don't re-stream to
                        // the UI; the deltas already covered it.
                        textBuffer.length = 0;
                        textBuffer.push(event.text);
                    } else {
                        textBuffer.push(event.text);
                        callbacks.onChunk?.(event.text);
                    }
                }
                if (event.tool) {
                    callbacks.onTool?.(event.tool);
                }
            }
        });

        proc.stderr.on('data', (chunk: Buffer) => { stderrBuf += chunk.toString('utf8'); });

        proc.on('close', (code) => {
            if (activeProcess === proc) activeProcess = null;
            teeStream.end();
            if (code !== 0 && code !== null) {
                reject(new Error(
                    `${buildExitErrorMessage(code, stderrBuf, unparseableSample)}. Full stdout at ${stdoutFile}`,
                ));
                return;
            }
            if (textBuffer.length === 0 && unparseableSample.length > 0) {
                console.warn(
                    `[pr-pilot] copilot emitted ${unparseableSample.length} unrecognized stdout lines but no text events — schema may have changed. Full stdout at ${stdoutFile}. First lines:\n${unparseableSample.join('\n')}`,
                );
            }
            resolve({ text: textBuffer.join(''), unparseableSample, stdoutFile });
        });

        proc.on('error', (err) => {
            if (activeProcess === proc) activeProcess = null;
            teeStream.end();
            reject(err);
        });
    });
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
    const result = await runProcess({
        prompt,
        model,
        effort,
        workingDir,
        callbacks: {
            onChunk: (chunk) => onChunk('text', chunk),
            onTool: (name) => onStatus(name),
        },
    });
    if (!result.text.trim()) {
        throw new Error(
            `copilot produced no output. ${buildNoOutputDetail(result.unparseableSample, result.stdoutFile)}`,
        );
    }
    onStatus('Parsing review…');
    try {
        return parseReview(result.text);
    } catch (e) {
        // ES2020 target lacks the Error(message, {cause}) overload; attach cause manually so
        // `preserve-caught-error` is satisfied without bumping the language target.
        const wrapped: Error & { cause?: unknown } = new Error(
            `Failed to parse review JSON from copilot output: ${e instanceof Error ? e.message : String(e)}. Full stdout at ${result.stdoutFile}`,
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
    const result = await runProcess({
        prompt: options.prompt,
        model: '',
        effort: options.effort,
        workingDir: options.workingDir,
        callbacks: { onChunk: options.onChunk },
    });
    return result.text;
}
