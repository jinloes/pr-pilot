import { spawn, type ChildProcessWithoutNullStreams } from 'child_process';
import * as fs from 'fs';
import { encodeFrame, extractFrames } from './sidecarFraming';
import { REQUIRED_CAPABILITIES, parseInitializeResult } from './sidecarProtocol';
import type { SidecarSpawn } from './sidecarTypes';

const REQUEST_TIMEOUT_MS = 60_000;
const SIDECAR_PROTOCOL_VERSION = 1;
const MAX_RUNTIME_RECOVERY_ATTEMPTS = 3;

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (err: Error) => void;
}

interface NotificationHandlers {
    onStatus?: (message: string) => void;
    onChunk?: (kind: 'text' | 'thinking', text: string) => void;
    onChatChunk?: (text: string) => void;
}

interface SidecarRpcResponse {
    jsonrpc?: string;
    id?: number;
    result?: unknown;
    error?: { code?: number; message?: string };
}

class StickySidecarFailure extends Error {}

export class SidecarTransport {

    protected child: ChildProcessWithoutNullStreams | null = null;
    private startupFailure: Error | null = null;
    private runtimeFailure: Error | null = null;
    private runtimeRecoveryAttempts = 0;
    private recoveryPrompted = false;
    private readyPromise: Promise<void> | null = null;
    private disposed = false;
    private nextId = 1;
    private readonly pending = new Map<number, PendingRequest>();
    private readonly notificationHandlers = new Map<number, NotificationHandlers>();
    private buffer: Buffer = Buffer.alloc(0);
    private stderr = '';

    constructor(
        private readonly jarPath: string | null,
        private readonly javaBinary = 'java',
        private readonly spawnSidecar: SidecarSpawn = spawn,
        private readonly requestTimeoutMs = REQUEST_TIMEOUT_MS,
        private readonly onRecoveryExhausted?: (failure: Error) => void,
    ) {}

    async initialize(): Promise<void> {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        if (this.startupFailure) throw this.startupFailure;
        if (this.readyPromise) return this.readyPromise;
        if (this.runtimeFailure) {
            if (this.runtimeRecoveryAttempts >= MAX_RUNTIME_RECOVERY_ATTEMPTS) {
                const failure = new Error(
                    `${this.runtimeFailure.message} Automatic recovery failed after ${MAX_RUNTIME_RECOVERY_ATTEMPTS} attempts. Use Retry to start the sidecar again.`,
                );
                if (!this.recoveryPrompted) {
                    this.recoveryPrompted = true;
                    this.onRecoveryExhausted?.(failure);
                }
                throw failure;
            }
            this.runtimeRecoveryAttempts++;
            this.runtimeFailure = null;
            this.readyPromise = null;
            this.buffer = Buffer.alloc(0);
            this.stderr = '';
        }

        try {
            this.ensureStarted();
        } catch (err) {
            throw err instanceof Error ? err : new Error(String(err));
        }
        const initializingChild = this.child;
        const ready = this.requestRaw('initialize', {}).then((value) => {
            const result = parseInitializeResult(value);
            if (!result) {
                throw new StickySidecarFailure(
                    'PR Pilot Java sidecar returned an invalid initialization response. Reinstall the extension.',
                );
            }
            if (result.protocolVersion !== SIDECAR_PROTOCOL_VERSION) {
                throw new StickySidecarFailure(
                    `PR Pilot Java sidecar protocol mismatch (expected ${SIDECAR_PROTOCOL_VERSION}, got ${result.protocolVersion}). Reinstall the extension.`,
                );
            }
            const missing = REQUIRED_CAPABILITIES.filter((capability) => result.capabilities[capability] !== true);
            if (missing.length > 0) {
                throw new StickySidecarFailure(
                    `PR Pilot Java sidecar is missing required capabilities: ${missing.join(', ')}. Reinstall the extension.`,
                );
            }
            if (this.child !== initializingChild) {
                throw this.runtimeFailure ?? new Error('PR Pilot Java sidecar stopped during initialization.');
            }
            this.runtimeFailure = null;
            this.runtimeRecoveryAttempts = 0;
            this.recoveryPrompted = false;
        }).catch((err: unknown) => {
            const failure = err instanceof Error ? err : new Error(String(err));
            if (this.startupFailure) throw this.startupFailure;
            if (failure instanceof StickySidecarFailure) {
                this.markStartupFailure(failure);
                throw failure;
            }
            if (!this.runtimeFailure) this.markRuntimeFailure(failure, initializingChild);
            return this.initialize();
        });
        this.readyPromise = ready;
        return ready;
    }

    private ensureStarted(): void {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        if (this.startupFailure) throw this.startupFailure;
        if (this.child) return;
        if (!this.jarPath) {
            const failure = new StickySidecarFailure(
                'PR Pilot Java sidecar is missing. Reinstall the extension or run ./gradlew :sidecar:bootJar for local development.',
            );
            this.markStartupFailure(failure);
            throw failure;
        }
        if (!fs.existsSync(this.jarPath)) {
            const failure = new StickySidecarFailure(
                `PR Pilot Java sidecar was not found at ${this.jarPath}. Reinstall the extension.`,
            );
            this.markStartupFailure(failure);
            throw failure;
        }
        try {
            const child = this.spawnSidecar(
                this.javaBinary,
                ['-jar', this.jarPath],
                { stdio: ['pipe', 'pipe', 'pipe'] },
            );
            child.on('error', (err: NodeJS.ErrnoException) => {
                if (this.disposed || this.child !== child) return;
                const failure = err.code === 'ENOENT'
                    ? new StickySidecarFailure('Java was not found. Install Java 17 or newer and ensure java is on PATH.')
                    : new Error(`PR Pilot Java sidecar failed to start: ${err.message}`);
                if (failure instanceof StickySidecarFailure) this.markStartupFailure(failure, child);
                else this.markRuntimeFailure(failure, child);
            });
            child.on('exit', (code, signal) => {
                if (this.disposed || this.child !== child) return;
                const failure = this.processExitError(code, signal);
                if (failure instanceof StickySidecarFailure) this.markStartupFailure(failure, child);
                else this.markRuntimeFailure(failure, child);
            });
            child.stdout.on('data', (chunk: Buffer) => {
                if (this.child === child) this.onData(chunk, child);
            });
            child.stderr.on('data', (chunk: Buffer) => {
                if (this.child === child) {
                    this.stderr = (this.stderr + chunk.toString('utf8')).slice(-4_096);
                }
            });
            this.child = child;
        } catch (err) {
            const failure = (err as NodeJS.ErrnoException | undefined)?.code === 'ENOENT'
                ? new StickySidecarFailure(
                    'Java was not found. Install Java 17 or newer and ensure java is on PATH.',
                )
                : err instanceof Error
                    ? new Error(`PR Pilot Java sidecar failed to start: ${err.message}`)
                    : new Error('PR Pilot Java sidecar failed to start.');
            if (failure instanceof StickySidecarFailure) this.markStartupFailure(failure);
            else this.markRuntimeFailure(failure);
            throw failure;
        }
    }

    private onData(chunk: Buffer, child: ChildProcessWithoutNullStreams): void {
        try {
            this.buffer = extractFrames(
                Buffer.concat([this.buffer, chunk]),
                (body) => this.handleMessage(body),
            );
        } catch (err) {
            const failure = err instanceof Error ? err : new Error(String(err));
            this.markRuntimeFailure(failure, child);
        }
    }

    private handleMessage(body: string): void {
        let message: SidecarRpcResponse & { method?: string; params?: unknown };
        try {
            message = JSON.parse(body) as SidecarRpcResponse & { method?: string; params?: unknown };
        } catch {
            const failure = new Error('PR Pilot Java sidecar sent malformed JSON.');
            this.markRuntimeFailure(failure);
            return;
        }
        if (typeof message.id !== 'number') {
            // A JSON-RPC notification (no id) — route reviews/status, reviews/chunk, and
            // reviews/chatChunk to the handlers registered for their correlated request.
            if (typeof message.method === 'string') this.dispatchNotification(message.method, message.params);
            return;
        }
        const pending = this.pending.get(message.id);
        if (!pending) return;
        if (message.jsonrpc !== '2.0' || (message.error === undefined) === (message.result === undefined)) {
            this.markRuntimeFailure(
                new Error('PR Pilot Java sidecar returned a malformed JSON-RPC response.'),
            );
            return;
        }
        this.pending.delete(message.id);
        this.notificationHandlers.delete(message.id);
        if (message.error) {
            pending.reject(new Error(message.error.message ?? 'Sidecar error'));
        } else {
            pending.resolve(message.result);
        }
    }

    private dispatchNotification(method: string, params: unknown): void {
        const p = params as Record<string, unknown> | undefined;
        const requestId = typeof p?.requestId === 'number' ? p.requestId : undefined;
        if (requestId === undefined) return;
        const handlers = this.notificationHandlers.get(requestId);
        if (!handlers) return;
        if (method === 'reviews/status' && typeof p?.message === 'string') {
            handlers.onStatus?.(p.message);
        } else if (method === 'reviews/chunk' && typeof p?.kind === 'string' && typeof p?.text === 'string') {
            handlers.onChunk?.(p.kind === 'thinking' ? 'thinking' : 'text', p.text);
        } else if (method === 'reviews/chatChunk' && typeof p?.text === 'string') {
            handlers.onChatChunk?.(p.text);
        }
    }

    private markRuntimeFailure(
        failure: Error,
        expectedChild?: ChildProcessWithoutNullStreams | null,
    ): void {
        if (this.disposed) return;
        if (expectedChild && this.child !== expectedChild) return;
        this.runtimeFailure = failure;
        this.readyPromise = null;
        this.buffer = Buffer.alloc(0);
        this.failAllPending(failure);
        this.stopChild();
    }

    private markStartupFailure(
        failure: Error,
        expectedChild?: ChildProcessWithoutNullStreams | null,
    ): void {
        if (this.disposed) return;
        if (expectedChild && this.child !== expectedChild) return;
        this.startupFailure = failure;
        this.runtimeFailure = null;
        this.readyPromise = null;
        this.buffer = Buffer.alloc(0);
        this.failAllPending(failure);
        this.stopChild();
    }

    private failAllPending(err: Error): void {
        for (const pending of this.pending.values()) pending.reject(err);
        this.pending.clear();
        this.notificationHandlers.clear();
    }

    private failPending(id: number, err: Error): boolean {
        const pending = this.pending.get(id);
        if (!pending) return false;
        this.pending.delete(id);
        this.notificationHandlers.delete(id);
        pending.reject(err);
        return true;
    }

    /** Restarts the sidecar after a user-visible transport failure and revalidates its capabilities. */
    async restart(): Promise<void> {
        if (this.disposed) throw new Error('PR Pilot Java sidecar has been disposed. Reload VS Code.');
        this.failAllPending(new Error('PR Pilot Java sidecar is restarting.'));
        this.stopChild();
        this.startupFailure = null;
        this.runtimeFailure = null;
        this.runtimeRecoveryAttempts = 0;
        this.recoveryPrompted = false;
        this.readyPromise = null;
        this.buffer = Buffer.alloc(0);
        this.stderr = '';
        await this.initialize();
    }

    protected async request(method: string, params: unknown): Promise<unknown> {
        await this.initialize();
        return this.requestRaw(method, params);
    }

    protected requestRaw(
        method: string,
        params: unknown,
        options?: { timeoutMs?: number; notificationHandlers?: NotificationHandlers },
    ): Promise<unknown> {
        const child = this.child;
        if (!child) {
            return Promise.reject(
                this.startupFailure
                ?? this.runtimeFailure
                ?? new Error('PR Pilot Java sidecar is not running.'),
            );
        }
        const id = this.nextId++;
        const timeoutMs = options?.timeoutMs ?? this.requestTimeoutMs;
        if (options?.notificationHandlers) this.notificationHandlers.set(id, options.notificationHandlers);
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                const failure = new Error(
                    `PR Pilot Java sidecar request "${method}" timed out. Try the request again.`,
                );
                this.failPending(id, failure);
            }, timeoutMs);
            this.pending.set(id, {
                resolve: (value) => { clearTimeout(timer); this.notificationHandlers.delete(id); resolve(value); },
                reject: (err) => { clearTimeout(timer); this.notificationHandlers.delete(id); reject(err); },
            });
            child.stdin.write(encodeFrame(JSON.stringify({ jsonrpc: '2.0', id, method, params })), (err) => {
                if (err) {
                    const failure = err instanceof Error ? err : new Error(String(err));
                    this.markRuntimeFailure(failure, child);
                }
            });
        });
    }

    protected parseResult<T>(description: string, parser: (value: unknown) => T | null, value: unknown): T {
        const result = parser(value);
        if (!result) throw this.invalidResponse(description);
        return result;
    }

    protected invalidResponse(description: string): Error {
        return new Error(`PR Pilot Java sidecar returned an invalid ${description} response.`);
    }

    private processExitError(code: number | null, signal: NodeJS.Signals | null): Error {
        const diagnostics = this.stderr.trim();
        if (/UnsupportedClassVersionError|class file version/i.test(diagnostics)) {
            return new StickySidecarFailure(
                'PR Pilot requires Java 17 or newer. Update Java and reload VS Code.',
            );
        }
        if (/Unable to access jarfile|Invalid or corrupt jarfile/i.test(diagnostics)) {
            return new StickySidecarFailure(
                'PR Pilot Java sidecar could not be opened. Reinstall the extension.',
            );
        }
        const reason = signal ? `signal ${signal}` : `exit code ${code ?? 'unknown'}`;
        const diagnosticLines = diagnostics.split(/\r?\n/);
        const detail = diagnostics ? ` ${diagnosticLines[diagnosticLines.length - 1]}` : '';
        return new Error(`PR Pilot Java sidecar exited unexpectedly (${reason}).${detail}`);
    }

    private stopChild(): void {
        const child = this.child;
        this.child = null;
        if (child) {
            child.stdin.end();
            child.kill();
        }
    }

    dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.stopChild();
        this.failAllPending(new Error('PR Pilot Java sidecar has been disposed.'));
    }
}
