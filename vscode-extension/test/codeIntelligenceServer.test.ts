import test from 'node:test';
import assert from 'node:assert/strict';
import * as http from 'http';
import type { CodeIntelligenceTools } from '../src/codeIntelligence';
import {
    MCP_PATH,
    SERVER_NAME,
    handleMessage,
    startCodeIntelligenceServer,
    withCodeIntelligenceServer,
    type RunningCodeIntelligenceServer,
} from '../src/codeIntelligenceServer';

function fakeTools(): CodeIntelligenceTools & { calls: Array<{ name: string; args: unknown }> } {
    const calls: Array<{ name: string; args: unknown }> = [];
    return {
        calls,
        list: () => [{ name: 'find_references', description: 'd', inputSchema: { type: 'object' } }],
        call(name, args) {
            if (name !== 'find_references') return undefined;
            calls.push({ name, args });
            return Promise.resolve({ content: [{ type: 'text', text: 'ok' }], isError: false });
        },
    };
}

interface Reply {
    status: number;
    body: string;
    headers: http.IncomingHttpHeaders;
}

function request(
    server: RunningCodeIntelligenceServer,
    options: { method?: string; path?: string; body?: string; headers?: Record<string, string>; token?: string | null },
): Promise<Reply> {
    const url = new URL(server.endpoint.url);
    const headers: Record<string, string> = { 'Content-Type': 'application/json', ...options.headers };
    const token = options.token === undefined ? server.endpoint.token : options.token;
    if (token !== null) headers.Authorization = `Bearer ${token}`;
    return new Promise((resolve, reject) => {
        const req = http.request({
            host: url.hostname,
            port: url.port,
            path: options.path ?? url.pathname,
            method: options.method ?? 'POST',
            headers,
        }, (res) => {
            const chunks: Buffer[] = [];
            res.on('data', (c: Buffer) => chunks.push(c));
            res.on('end', () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8'), headers: res.headers }));
        });
        req.on('error', reject);
        if (options.body !== undefined) req.write(options.body);
        req.end();
    });
}

async function withServer(run: (server: RunningCodeIntelligenceServer, tools: ReturnType<typeof fakeTools>) => Promise<void>): Promise<void> {
    const tools = fakeTools();
    const server = await startCodeIntelligenceServer(tools);
    try {
        await run(server, tools);
    } finally {
        await server.close();
    }
}

// ── handleMessage ────────────────────────────────────────────────────────────

test('initialize echoes a supported protocol version and falls back to the latest otherwise', async () => {
    const tools = fakeTools();
    const known = await handleMessage(tools, { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26' } });
    const unknown = await handleMessage(tools, { jsonrpc: '2.0', id: 2, method: 'initialize', params: { protocolVersion: '1999-01-01' } });
    const result = known?.result as Record<string, unknown>;
    assert.equal(result.protocolVersion, '2025-03-26');
    assert.deepEqual(result.capabilities, { tools: { listChanged: false } });
    assert.deepEqual(result.serverInfo, { name: SERVER_NAME, version: '1.0.0' });
    assert.equal((unknown?.result as Record<string, unknown>).protocolVersion, '2025-06-18');
});

test('notifications and client responses get no reply; malformed requests get JSON-RPC errors', async () => {
    const tools = fakeTools();
    assert.equal(await handleMessage(tools, { jsonrpc: '2.0', method: 'notifications/initialized' }), undefined);
    assert.equal(await handleMessage(tools, { jsonrpc: '2.0', id: 3, result: {} } as never), undefined);
    assert.deepEqual(await handleMessage(tools, { jsonrpc: '2.0', id: 4, method: 'resources/list' }), {
        jsonrpc: '2.0', id: 4, error: { code: -32601, message: 'Method not found' },
    });
    assert.deepEqual(await handleMessage(tools, [] as never), {
        jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Invalid Request' },
    });
});

test('tools/call dispatches known tools and rejects unknown ones with invalid params', async () => {
    const tools = fakeTools();
    const ok = await handleMessage(tools, { jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'find_references', arguments: { symbol: 's' } } });
    const unknown = await handleMessage(tools, { jsonrpc: '2.0', id: 6, method: 'tools/call', params: { name: 'run_shell' } });
    assert.deepEqual(ok?.result, { content: [{ type: 'text', text: 'ok' }], isError: false });
    assert.deepEqual(tools.calls, [{ name: 'find_references', args: { symbol: 's' } }]);
    assert.deepEqual(unknown?.error, { code: -32602, message: 'Unknown tool: run_shell' });
});

// ── startCodeIntelligenceServer ──────────────────────────────────────────────

test('the server listens on loopback with a fresh base64url token per start', async () => {
    const a = await startCodeIntelligenceServer(fakeTools());
    const b = await startCodeIntelligenceServer(fakeTools());
    try {
        assert.match(a.endpoint.url, new RegExp(`^http://127\\.0\\.0\\.1:\\d+${MCP_PATH}$`));
        assert.match(a.endpoint.token, /^[A-Za-z0-9_-]{43}$/);
        assert.notEqual(a.endpoint.token, b.endpoint.token);
    } finally {
        await a.close();
        await b.close();
    }
});

test('an authorized POST runs the MCP handshake and a tool call', async () => {
    await withServer(async (server, tools) => {
        const init = await request(server, { body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }) });
        assert.equal(init.status, 200);
        assert.match(String(init.headers['content-type']), /application\/json/);
        assert.equal(JSON.parse(init.body).result.serverInfo.name, SERVER_NAME);

        const initialized = await request(server, { body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
        assert.equal(initialized.status, 202);
        assert.equal(initialized.body, '');

        const list = await request(server, { body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list' }) });
        assert.equal(JSON.parse(list.body).result.tools[0].name, 'find_references');

        const batch = await request(server, { body: JSON.stringify([
            { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'find_references', arguments: {} } },
            { jsonrpc: '2.0', method: 'notifications/cancelled' },
        ]) });
        assert.deepEqual(JSON.parse(batch.body), [{ jsonrpc: '2.0', id: 3, result: { content: [{ type: 'text', text: 'ok' }], isError: false } }]);
        assert.equal(tools.calls.length, 1);
    });
});

test('requests without the exact bearer token are refused before anything runs', async () => {
    await withServer(async (server, tools) => {
        const body = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'find_references' } });
        assert.equal((await request(server, { body, token: null })).status, 401);
        assert.equal((await request(server, { body, token: 'wrong' })).status, 401);
        assert.equal((await request(server, { body, token: `${server.endpoint.token}x` })).status, 401);
        assert.equal(tools.calls.length, 0);
    });
});

test('browser-originated requests are refused even with the token', async () => {
    await withServer(async (server, tools) => {
        const reply = await request(server, {
            body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'find_references' } }),
            headers: { Origin: 'http://evil.example' },
        });
        assert.equal(reply.status, 403);
        assert.equal(tools.calls.length, 0);
    });
});

test('other paths, methods, bad JSON, and oversized bodies are rejected', async () => {
    await withServer(async (server) => {
        assert.equal((await request(server, { path: '/other', body: '{}' })).status, 404);
        const get = await request(server, { method: 'GET' });
        assert.equal(get.status, 405);
        assert.equal(get.headers.allow, 'POST');
        const bad = await request(server, { body: '{not json' });
        assert.equal(bad.status, 400);
        assert.equal(JSON.parse(bad.body).error.code, -32700);
        const huge = await request(server, { body: 'x'.repeat(1024 * 1024 + 1) }).catch(() => ({ status: 413 }));
        assert.equal(huge.status, 413);
    });
});

test('close stops accepting connections', async () => {
    const server = await startCodeIntelligenceServer(fakeTools());
    await server.close();
    await assert.rejects(request(server, { body: '{}' }));
});

// ── withCodeIntelligenceServer ───────────────────────────────────────────────

test('withCodeIntelligenceServer runs without a server when disabled or without a checkout', async () => {
    let started = 0;
    const start = () => {
        started++;
        return Promise.reject(new Error('should not start'));
    };
    assert.equal(await withCodeIntelligenceServer(false, '/repo', fakeTools, (e) => Promise.resolve(e), start), undefined);
    assert.equal(await withCodeIntelligenceServer(true, null, fakeTools, (e) => Promise.resolve(e), start), undefined);
    assert.equal(started, 0);
});

test('withCodeIntelligenceServer passes the endpoint and closes the server even when run fails', async () => {
    const roots: string[] = [];
    let closed = 0;
    const start = () => Promise.resolve({
        endpoint: { url: 'http://127.0.0.1:1/mcp', token: 't' },
        close: () => {
            closed++;
            return Promise.resolve();
        },
    });
    const createTools = (root: string) => {
        roots.push(root);
        return fakeTools();
    };

    const endpoint = await withCodeIntelligenceServer(true, '/repo', createTools, (e) => Promise.resolve(e), start);
    await assert.rejects(withCodeIntelligenceServer(true, '/repo', createTools, () => Promise.reject(new Error('boom')), start), /boom/);

    assert.deepEqual(endpoint, { url: 'http://127.0.0.1:1/mcp', token: 't' });
    assert.deepEqual(roots, ['/repo', '/repo']);
    assert.equal(closed, 2);
});

test('withCodeIntelligenceServer degrades to no server when it cannot start', async () => {
    const originalWarn = console.warn;
    console.warn = () => undefined;
    try {
        const endpoint = await withCodeIntelligenceServer(
            true,
            '/repo',
            fakeTools,
            (e) => Promise.resolve(e),
            () => Promise.reject(new Error('EADDRINUSE')),
        );
        assert.equal(endpoint, undefined);
    } finally {
        console.warn = originalWarn;
    }
});
