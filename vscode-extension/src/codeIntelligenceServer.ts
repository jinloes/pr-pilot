import * as crypto from 'crypto';
import * as http from 'http';
import type { AddressInfo } from 'net';
import type { CodeIntelligenceTools } from './codeIntelligence';

/**
 * Minimal MCP server (Streamable HTTP transport, JSON responses only) that exposes
 * {@link CodeIntelligenceTools} to the Copilot review session the sidecar starts. It listens on
 * loopback only, lives for one review or chat, and requires a per-run bearer token, so another
 * local process or a web page cannot query the reviewer's checkout through it.
 */

export const SERVER_NAME = 'pr-pilot-code-intelligence';
export const MCP_PATH = '/mcp';
export const SUPPORTED_PROTOCOL_VERSIONS = ['2025-06-18', '2025-03-26', '2024-11-05'];
const MAX_BODY_BYTES = 1024 * 1024;

export interface CodeIntelligenceEndpoint {
    url: string;
    token: string;
}

export interface RunningCodeIntelligenceServer {
    endpoint: CodeIntelligenceEndpoint;
    close(): Promise<void>;
}

interface JsonRpcMessage {
    jsonrpc?: unknown;
    id?: unknown;
    method?: unknown;
    params?: unknown;
}

type JsonRpcResponse = Record<string, unknown>;

function rpcResult(id: unknown, result: unknown): JsonRpcResponse {
    return { jsonrpc: '2.0', id, result };
}

function rpcError(id: unknown, code: number, message: string): JsonRpcResponse {
    return { jsonrpc: '2.0', id, error: { code, message } };
}

function tokenMatches(header: string | undefined, token: string): boolean {
    const expected = Buffer.from(`Bearer ${token}`);
    const actual = Buffer.from(header ?? '');
    return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function negotiateProtocol(params: unknown): string {
    const requested = params !== null && typeof params === 'object'
        ? (params as Record<string, unknown>).protocolVersion
        : undefined;
    return typeof requested === 'string' && SUPPORTED_PROTOCOL_VERSIONS.includes(requested)
        ? requested
        : SUPPORTED_PROTOCOL_VERSIONS[0];
}

/** Handles one JSON-RPC message; returns undefined for notifications and client responses. */
export async function handleMessage(
    tools: CodeIntelligenceTools,
    message: JsonRpcMessage,
): Promise<JsonRpcResponse | undefined> {
    if (message === null || typeof message !== 'object' || Array.isArray(message)) {
        return rpcError(null, -32600, 'Invalid Request');
    }
    const isRequest = message.id !== undefined && message.id !== null;
    if (typeof message.method !== 'string') {
        const isClientResponse = 'result' in message || 'error' in message;
        return isRequest && !isClientResponse ? rpcError(message.id, -32600, 'Invalid Request') : undefined;
    }
    if (!isRequest) return undefined;
    const id = message.id;
    switch (message.method) {
        case 'initialize':
            return rpcResult(id, {
                protocolVersion: negotiateProtocol(message.params),
                capabilities: { tools: { listChanged: false } },
                serverInfo: { name: SERVER_NAME, version: '1.0.0' },
                instructions:
                    'Read-only code intelligence over the reviewer\'s local checkout. Results may come'
                    + ' from a different commit than the pull request under review.',
            });
        case 'ping':
            return rpcResult(id, {});
        case 'tools/list':
            return rpcResult(id, { tools: tools.list() });
        case 'tools/call': {
            const params = message.params !== null && typeof message.params === 'object'
                ? message.params as Record<string, unknown>
                : {};
            const name = typeof params.name === 'string' ? params.name : '';
            const pending = tools.call(name, params.arguments ?? {});
            if (!pending) return rpcError(id, -32602, `Unknown tool: ${name.slice(0, 100)}`);
            return rpcResult(id, await pending);
        }
        default:
            return rpcError(id, -32601, 'Method not found');
    }
}

function readBody(req: http.IncomingMessage): Promise<string | undefined> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        let size = 0;
        req.on('data', (chunk: Buffer) => {
            size += chunk.length;
            if (size > MAX_BODY_BYTES) {
                resolve(undefined);
                req.destroy();
                return;
            }
            chunks.push(chunk);
        });
        req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
        req.on('error', reject);
    });
}

function send(res: http.ServerResponse, status: number, body?: unknown, headers: http.OutgoingHttpHeaders = {}): void {
    if (body === undefined) {
        res.writeHead(status, headers).end();
        return;
    }
    res.writeHead(status, { ...headers, 'Content-Type': 'application/json' }).end(JSON.stringify(body));
}

async function handleRequest(
    tools: CodeIntelligenceTools,
    token: string,
    req: http.IncomingMessage,
    res: http.ServerResponse,
): Promise<void> {
    // Browsers always send Origin; the Copilot CLI does not. Refusing it blocks DNS rebinding and
    // cross-site requests even before the token check.
    if (req.headers.origin !== undefined) return send(res, 403);
    if (!tokenMatches(req.headers.authorization, token)) return send(res, 401);
    if ((req.url ?? '').split('?')[0] !== MCP_PATH) return send(res, 404);
    if (req.method !== 'POST') return send(res, 405, undefined, { Allow: 'POST' });

    const body = await readBody(req);
    if (body === undefined) return send(res, 413);
    let parsed: unknown;
    try {
        parsed = JSON.parse(body);
    } catch {
        return send(res, 400, rpcError(null, -32700, 'Parse error'));
    }
    if (Array.isArray(parsed)) {
        const responses = (await Promise.all(parsed.map((m) => handleMessage(tools, m as JsonRpcMessage))))
            .filter((r): r is JsonRpcResponse => r !== undefined);
        return responses.length ? send(res, 200, responses) : send(res, 202);
    }
    const response = await handleMessage(tools, parsed as JsonRpcMessage);
    return response ? send(res, 200, response) : send(res, 202);
}

/** Starts the server on an ephemeral loopback port with a fresh token. */
export function startCodeIntelligenceServer(tools: CodeIntelligenceTools): Promise<RunningCodeIntelligenceServer> {
    const token = crypto.randomBytes(32).toString('base64url');
    const server = http.createServer((req, res) => {
        handleRequest(tools, token, req, res).catch(() => {
            if (!res.headersSent) send(res, 500);
            else res.end();
        });
    });
    return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', () => {
            server.off('error', reject);
            const { port } = server.address() as AddressInfo;
            resolve({
                endpoint: { url: `http://127.0.0.1:${port}${MCP_PATH}`, token },
                close: () => new Promise<void>((done) => {
                    server.close(() => done());
                    server.closeAllConnections();
                }),
            });
        });
    });
}

/**
 * Runs `run` with a code-intelligence server over `root` when `enabled`, and always closes it
 * afterwards. A server that fails to start degrades to running without one.
 */
export async function withCodeIntelligenceServer<T>(
    enabled: boolean,
    root: string | null | undefined,
    createTools: (root: string) => CodeIntelligenceTools,
    run: (endpoint: CodeIntelligenceEndpoint | undefined) => Promise<T>,
    start: (tools: CodeIntelligenceTools) => Promise<RunningCodeIntelligenceServer> = startCodeIntelligenceServer,
): Promise<T> {
    if (!enabled || !root) return run(undefined);
    let server: RunningCodeIntelligenceServer;
    try {
        server = await start(createTools(root));
    } catch (err) {
        console.warn('[PR Pilot] Code intelligence server failed to start; continuing without it.', err);
        return run(undefined);
    }
    try {
        return await run(server.endpoint);
    } finally {
        await server.close();
    }
}
