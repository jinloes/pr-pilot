import * as vscode from 'vscode';
import type { CodeIntelligenceBackend, CodeLocation, CodePosition, CodeSymbol } from './codeIntelligence';

/** Answers code-intelligence lookups through VS Code's language services for `root`. */
export function createVscodeCodeIntelligenceBackend(root: string): CodeIntelligenceBackend {
    const position = (p: CodePosition) => new vscode.Position(p.line, p.character);
    const uri = (absolutePath: string) => vscode.Uri.file(absolutePath);

    async function locations(command: string, absolutePath: string, p: CodePosition): Promise<CodeLocation[]> {
        // Opening the document lets language servers that only analyze open files resolve it.
        await vscode.workspace.openTextDocument(uri(absolutePath));
        const results = await vscode.commands.executeCommand<Array<vscode.Location | vscode.LocationLink> | undefined>(
            command,
            uri(absolutePath),
            position(p),
        );
        return (results ?? []).map(toCodeLocation);
    }

    return {
        async readFile(absolutePath) {
            return (await vscode.workspace.openTextDocument(uri(absolutePath))).getText();
        },
        definitions: (f, p) => locations('vscode.executeDefinitionProvider', f, p),
        references: (f, p) => locations('vscode.executeReferenceProvider', f, p),
        implementations: (f, p) => locations('vscode.executeImplementationProvider', f, p),
        async hover(absolutePath, p) {
            await vscode.workspace.openTextDocument(uri(absolutePath));
            const hovers = await vscode.commands.executeCommand<vscode.Hover[] | undefined>(
                'vscode.executeHoverProvider',
                uri(absolutePath),
                position(p),
            );
            return (hovers ?? []).flatMap((h) => h.contents.map(hoverText));
        },
        async workspaceSymbols(query): Promise<CodeSymbol[]> {
            const symbols = await vscode.commands.executeCommand<vscode.SymbolInformation[] | undefined>(
                'vscode.executeWorkspaceSymbolProvider',
                query,
            );
            return (symbols ?? []).map((s) => ({
                name: s.name,
                kind: vscode.SymbolKind[s.kind] ?? 'Symbol',
                containerName: s.containerName ?? '',
                location: toCodeLocation(s.location),
            }));
        },
        checkoutRevision() {
            try {
                const git = vscode.extensions.getExtension('vscode.git')?.exports?.getAPI?.(1);
                const commit: unknown = git?.getRepository?.(vscode.Uri.file(root))?.state?.HEAD?.commit;
                return Promise.resolve(typeof commit === 'string' ? commit : undefined);
            } catch {
                return Promise.resolve(undefined);
            }
        },
    };
}

function toCodeLocation(location: vscode.Location | vscode.LocationLink): CodeLocation {
    const target = 'targetUri' in location ? location.targetUri : location.uri;
    const range = 'targetUri' in location
        ? location.targetSelectionRange ?? location.targetRange
        : location.range;
    return {
        path: target.scheme === 'file' ? target.fsPath : undefined,
        line: range.start.line,
        character: range.start.character,
    };
}

function hoverText(content: vscode.MarkdownString | vscode.MarkedString): string {
    if (typeof content === 'string') return content;
    if ('language' in content) return `\`\`\`${content.language}\n${content.value}\n\`\`\``;
    return content.value;
}
