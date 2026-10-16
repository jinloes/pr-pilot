import { spawn } from 'node:child_process';
import fs from 'node:fs';
import { access } from 'node:fs/promises';
import path from 'node:path';

const jarPath = path.resolve(process.argv[2] ?? 'sidecar/pr-pilot-sidecar.jar');
await access(jarPath);

const child = spawn(resolveJavaBinary(), ['-jar', jarPath], {
  stdio: ['pipe', 'pipe', 'pipe'],
});
const requiredCapabilities = [
  'githubAuth',
  'prDetail',
  'prDiff',
  'prList',
  'repoDetect',
  'draftReview',
  'draftReviewMutations',
  'prSearch',
  'starredRepos',
  'existingReviews',
];
let stderr = '';
let buffer = Buffer.alloc(0);
const timeout = setTimeout(() => fail('Timed out waiting for the sidecar initialize response.'), 15_000);

child.stderr.on('data', (chunk) => {
  stderr = (stderr + chunk.toString('utf8')).slice(-4096);
});
child.on('error', (error) => fail(`Could not start Java: ${error.message}`));
child.on('exit', (code, signal) => {
  fail(`Sidecar exited before initialization (code=${code}, signal=${signal}).`);
});
child.stdout.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  const headerEnd = buffer.indexOf('\r\n\r\n');
  if (headerEnd < 0) return;
  const match = /Content-Length:\s*(\d+)/i.exec(buffer.subarray(0, headerEnd).toString('ascii'));
  if (!match) fail('Sidecar returned an invalid Content-Length header.');
  const length = Number.parseInt(match[1], 10);
  const bodyStart = headerEnd + 4;
  if (buffer.length < bodyStart + length) return;
  const response = JSON.parse(buffer.subarray(bodyStart, bodyStart + length).toString('utf8'));
  const result = response.result;
  const missingCapabilities = requiredCapabilities.filter(
    (capability) => result?.capabilities?.[capability] !== true,
  );
  if (response.jsonrpc !== '2.0'
      || response.id !== 1
      || result?.serviceName !== 'pr-pilot-sidecar'
      || result?.protocolVersion !== 1
      || missingCapabilities.length > 0) {
    fail(`Unexpected initialize response: ${JSON.stringify(response)}`);
  }
  clearTimeout(timeout);
  child.removeAllListeners('exit');
  child.stdin.end();
  child.kill();
  console.log(`Sidecar ${result.serviceVersion} protocol ${result.protocolVersion} initialized successfully.`);
});

const request = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} });
const body = Buffer.from(request, 'utf8');
child.stdin.write(Buffer.concat([
  Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, 'ascii'),
  body,
]));

function fail(message) {
  clearTimeout(timeout);
  child.removeAllListeners('exit');
  child.kill();
  console.error(message);
  if (stderr.trim()) console.error(stderr.trim());
  process.exitCode = 1;
}

function resolveJavaBinary() {
  if (process.env.JAVA_BINARY) return process.env.JAVA_BINARY;
  if (process.env.JAVA_HOME) {
    const candidate = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(candidate)) return candidate;
  }
  return 'java';
}
