import { defineConfig } from '@vscode/test-cli';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const extensionDir = path.dirname(fileURLToPath(import.meta.url));
const testRoot = path.join(extensionDir, '.vscode-test');
const workspaceFolder = path.join(testRoot, 'workspace');
const userDataDir = path.join(testRoot, 'user-data');
const extensionsDir = path.join(testRoot, 'extensions');

for (const dir of [workspaceFolder, userDataDir, extensionsDir]) {
  fs.mkdirSync(dir, { recursive: true });
}
if (!fs.existsSync(path.join(workspaceFolder, '.git'))) {
  spawnSync('git', ['init', '--quiet'], { cwd: workspaceFolder, stdio: 'ignore' });
}

export default defineConfig({
  files: 'integration-test/**/*.test.js',
  version: process.env.PR_PILOT_VSCODE_VERSION ?? '1.90.0',
  extensionDevelopmentPath: extensionDir,
  workspaceFolder,
  launchArgs: [
    '--disable-extensions',
    '--disable-workspace-trust',
    `--user-data-dir=${userDataDir}`,
    `--extensions-dir=${extensionsDir}`,
  ],
  skipExtensionDependencies: true,
  mocha: {
    timeout: 30_000,
    ui: 'bdd',
    color: true,
  },
});