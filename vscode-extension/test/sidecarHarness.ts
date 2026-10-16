// Fake sidecar process plumbing shared by the SidecarClient test files. Not a test file itself:
// run-unit-tests.mjs only executes *.test.js.
import { after } from 'node:test';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'path';
import { PassThrough } from 'node:stream';
import type { ChildProcessWithoutNullStreams } from 'node:child_process';

import { REQUIRED_CAPABILITIES, encodeFrame, extractFrames, type SidecarSpawn } from '../src/sidecar';

export const tempRoot = mkdtempSync(path.join(tmpdir(), 'pr-pilot-sidecar-test-'));
export const fakeJar = path.join(tempRoot, 'pr-pilot-sidecar.jar');
writeFileSync(fakeJar, 'test');
after(() => rmSync(tempRoot, { recursive: true, force: true }));

export const initializeResult = {
  serviceName: 'pr-pilot-sidecar',
  serviceVersion: '0.1.0',
  protocolVersion: 1,
  // Derived rather than listed so adding a capability cannot silently leave this fixture behind.
  // When it was a hand-written list, it fell four capabilities short of what the client requires
  // and every client test transparently exercised a *failed* handshake instead of a healthy one.
  capabilities: Object.fromEntries(REQUIRED_CAPABILITIES.map((name) => [name, true])) as Record<string, boolean>,
};

export const NO_RESPONSE = Symbol('NO_RESPONSE');

export function createSidecarHarness(
  responder: (method: string, params: unknown) => unknown | typeof NO_RESPONSE,
): {
  spawnSidecar: SidecarSpawn;
  children: ChildProcessWithoutNullStreams[];
  commands: Array<{ command: string; args: string[] }>;
  methods: string[];
  requests: Array<{ id: number; method: string; params: unknown }>;
  killCount: { value: number };
} {
  const children: ChildProcessWithoutNullStreams[] = [];
  const commands: Array<{ command: string; args: string[] }> = [];
  const methods: string[] = [];
  const requests: Array<{ id: number; method: string; params: unknown }> = [];
  const killCount = { value: 0 };
  const spawnSidecar: SidecarSpawn = (command, args) => {
    commands.push({ command, args });
    const stdin = new PassThrough();
    const stdout = new PassThrough();
    const stderr = new PassThrough();
    const child = Object.assign(new EventEmitter(), {
      stdin,
      stdout,
      stderr,
      kill: () => {
        killCount.value++;
        return true;
      },
    }) as unknown as ChildProcessWithoutNullStreams;
    children.push(child);
    let requestBuffer: Buffer<ArrayBufferLike> = Buffer.alloc(0);
    stdin.on('data', (chunk: Buffer) => {
      requestBuffer = extractFrames(Buffer.concat([requestBuffer, chunk]), (body) => {
        const request = JSON.parse(body) as { id: number; method: string; params: unknown };
        methods.push(request.method);
        requests.push({ id: request.id, method: request.method, params: request.params });
        const result = responder(request.method, request.params);
        if (result === NO_RESPONSE) return;
        queueMicrotask(() => stdout.write(encodeFrame(JSON.stringify({
          jsonrpc: '2.0',
          id: request.id,
          result,
        }))));
      });
    });
    return child;
  };
  return { spawnSidecar, children, commands, methods, requests, killCount };
}
