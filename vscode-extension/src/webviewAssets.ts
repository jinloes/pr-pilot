import * as path from 'path';

export function resolveWebviewDistPath(
    extensionRoot: string,
    existsSync: (candidate: string) => boolean,
): string {
    const packagedDist = path.join(extensionRoot, 'webview-dist');
    if (existsSync(path.join(packagedDist, 'index.html'))) {
        return packagedDist;
    }
    return path.resolve(extensionRoot, '..', 'webview', 'dist');
}

