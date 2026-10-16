const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vscode = require('vscode');

const extensionRoot = path.resolve(__dirname, '..');
const manifest = require(path.join(extensionRoot, 'package.json'));
const { REQUIRED_CAPABILITIES } = require(path.join(extensionRoot, 'dist', 'sidecarProtocol.js'));
const { resolveSidecarJarPath } = require(path.join(extensionRoot, 'dist', 'sidecarJar.js'));

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function javaBinary() {
  if (process.env.JAVA_BINARY) return process.env.JAVA_BINARY;
  if (process.env.JAVA_HOME) {
    const candidate = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(candidate)) return candidate;
  }
  return 'java';
}

function readFrame(buffer) {
  const headerEnd = buffer.indexOf('\r\n\r\n');
  if (headerEnd < 0) return null;
  const header = buffer.subarray(0, headerEnd).toString('ascii');
  const match = /Content-Length:\s*(\d+)/i.exec(header);
  if (!match) throw new Error(`Invalid sidecar header: ${header}`);
  const length = Number.parseInt(match[1], 10);
  const bodyStart = headerEnd + 4;
  if (buffer.length < bodyStart + length) return null;
  return JSON.parse(buffer.subarray(bodyStart, bodyStart + length).toString('utf8'));
}

function initializeSidecar(jarPath) {
  return new Promise((resolve, reject) => {
    const child = cp.spawn(javaBinary(), ['-jar', jarPath], { stdio: ['pipe', 'pipe', 'pipe'] });
    let stderr = '';
    let stdout = Buffer.alloc(0);
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error('Timed out waiting for sidecar initialize response.'));
    }, 15_000);

    function cleanup() {
      clearTimeout(timer);
      child.removeAllListeners('exit');
      child.kill();
    }

    child.on('error', (error) => {
      cleanup();
      reject(error);
    });
    child.on('exit', (code, signal) => {
      cleanup();
      reject(new Error(`Sidecar exited before initialization (code=${code}, signal=${signal}).\n${stderr.trim()}`));
    });
    child.stderr.on('data', (chunk) => {
      stderr = (stderr + chunk.toString('utf8')).slice(-4096);
    });
    child.stdout.on('data', (chunk) => {
      stdout = Buffer.concat([stdout, chunk]);
      let response;
      try {
        response = readFrame(stdout);
      } catch (error) {
        cleanup();
        reject(error);
        return;
      }
      if (!response) return;
      cleanup();
      resolve(response);
    });

    const request = Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }), 'utf8');
    child.stdin.write(Buffer.concat([
      Buffer.from(`Content-Length: ${request.length}\r\n\r\n`, 'ascii'),
      request,
    ]));
  });
}

describe('PR Pilot VS Code extension smoke', () => {
  it('activates without throwing', async () => {
    const extension = vscode.extensions.getExtension('jinloes.pr-pilot-vscode');
    assert.ok(extension, 'Extension should be installed in the extension host');
    await extension.activate();
    assert.equal(extension.isActive, true);
  });

  it('registers every contributed command after activation', async () => {
    const extension = vscode.extensions.getExtension('jinloes.pr-pilot-vscode');
    assert.ok(extension, 'Extension should be installed in the extension host');
    await extension.activate();

    const registered = new Set(await vscode.commands.getCommands(false));
    const contributed = manifest.contributes.commands.map((entry) => entry.command);
    assert.deepEqual(
      contributed.filter((command) => !registered.has(command)),
      [],
      'All package.json contributed commands should be registered',
    );
  });

  it('opens the main PR Pilot UI command without throwing', async () => {
    const extension = vscode.extensions.getExtension('jinloes.pr-pilot-vscode');
    assert.ok(extension, 'Extension should be installed in the extension host');
    await extension.activate();

    await vscode.commands.executeCommand('pr-pilot.open');
    await delay(500);

    const labels = vscode.window.tabGroups.all.flatMap((group) => group.tabs.map((tab) => tab.label));
    assert.ok(labels.some((label) => label.includes('PR Pilot')), `Expected a PR Pilot webview tab, saw: ${labels.join(', ')}`);
  });

  it('starts the staged sidecar and receives all required capabilities', async function () {
    if (process.env.PR_PILOT_SKIP_SIDECAR_SMOKE === '1') this.skip();

    const jarPath = resolveSidecarJarPath(extensionRoot);
    assert.ok(jarPath, 'Expected a staged or development sidecar jar');
    assert.match(path.normalize(jarPath), /[\\/]sidecar[\\/]pr-pilot-sidecar\.jar$/);

    const response = await initializeSidecar(jarPath);
    assert.equal(response.jsonrpc, '2.0');
    assert.equal(response.id, 1);
    assert.equal(response.result?.serviceName, 'pr-pilot-sidecar');
    assert.equal(response.result?.protocolVersion, 1);

    const missing = REQUIRED_CAPABILITIES.filter((capability) => response.result?.capabilities?.[capability] !== true);
    assert.deepEqual(missing, [], `Missing sidecar capabilities: ${missing.join(', ')}`);
  });
});