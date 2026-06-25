import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const extensionDir = path.resolve(__dirname, '..');
const repoRoot = path.resolve(extensionDir, '..');
const sourceDir = path.join(repoRoot, 'webview', 'dist');
const targetDir = path.join(extensionDir, 'webview-dist');
const sourceIndex = path.join(sourceDir, 'index.html');

if (!fs.existsSync(sourceIndex)) {
    console.error('webview/dist/index.html not found. Run `npm run build` inside webview/ first.');
    process.exit(1);
}

fs.rmSync(targetDir, { recursive: true, force: true });
fs.mkdirSync(path.dirname(targetDir), { recursive: true });
fs.cpSync(sourceDir, targetDir, { recursive: true });
console.log(`Staged webview assets into ${targetDir}`);

