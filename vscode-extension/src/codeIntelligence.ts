import * as fs from 'fs';
import * as path from 'path';

/**
 * Read-only code-intelligence tools served to a Copilot review over MCP. Lookups run against the
 * reviewer's local checkout through a {@link CodeIntelligenceBackend} (VS Code's language services
 * in production). Requests come from a model reading untrusted pull-request content, so every
 * path is confined to the checkout root and every input and output is bounded.
 */

/** Zero-based line and character, as VS Code reports them. */
export interface CodePosition {
    line: number;
    character: number;
}

/** A result location. `path` is an absolute file-system path, or undefined for non-file URIs. */
export interface CodeLocation {
    path: string | undefined;
    line: number;
    character: number;
}

export interface CodeSymbol {
    name: string;
    kind: string;
    containerName: string;
    location: CodeLocation;
}

export interface CodeIntelligenceBackend {
    readFile(absolutePath: string): Promise<string>;
    definitions(absolutePath: string, position: CodePosition): Promise<CodeLocation[]>;
    references(absolutePath: string, position: CodePosition): Promise<CodeLocation[]>;
    implementations(absolutePath: string, position: CodePosition): Promise<CodeLocation[]>;
    hover(absolutePath: string, position: CodePosition): Promise<string[]>;
    workspaceSymbols(query: string): Promise<CodeSymbol[]>;
    /** Commit the checkout is at, when known. */
    checkoutRevision(): Promise<string | undefined>;
}

export interface ToolDefinition {
    name: string;
    description: string;
    inputSchema: Record<string, unknown>;
}

export interface ToolResult {
    content: Array<{ type: 'text'; text: string }>;
    isError: boolean;
}

export interface CodeIntelligenceTools {
    list(): ToolDefinition[];
    /** Undefined for an unknown tool name. */
    call(name: string, args: unknown): Promise<ToolResult> | undefined;
}

export const MAX_LOCATIONS = 100;
export const MAX_SYMBOLS = 50;
export const MAX_HOVER_CHARS = 4000;
export const MAX_SNIPPET_CHARS = 200;
const MAX_PATH_CHARS = 1024;
const MAX_SYMBOL_CHARS = 200;
const DEFAULT_TIMEOUT_MS = 15_000;

export class ToolInputError extends Error {}

const SYMBOL_LOOKUP_SCHEMA = {
    type: 'object',
    properties: {
        path: {
            type: 'string',
            description: 'File path relative to the repository root, as it appears in the diff.',
        },
        symbol: {
            type: 'string',
            description: 'Exact identifier to look up, such as a method, class, field, or function name.',
        },
        line: {
            type: 'integer',
            minimum: 1,
            description: 'Optional 1-based line where the symbol appears; the nearest occurrence is used.',
        },
    },
    required: ['path', 'symbol'],
    additionalProperties: false,
};

const TOOL_DEFINITIONS: ToolDefinition[] = [
    {
        name: 'find_references',
        description:
            'Find every reference to a symbol across the repository using the IDE language server.'
            + ' Results come from the local checkout, which may be at a different commit than the PR.',
        inputSchema: SYMBOL_LOOKUP_SCHEMA,
    },
    {
        name: 'find_definition',
        description:
            'Find where a symbol is declared using the IDE language server, resolving imports,'
            + ' overloads, and inherited members that text search cannot.',
        inputSchema: SYMBOL_LOOKUP_SCHEMA,
    },
    {
        name: 'find_implementations',
        description:
            'Find implementations and overrides of an interface, abstract method, or class using the'
            + ' IDE language server.',
        inputSchema: SYMBOL_LOOKUP_SCHEMA,
    },
    {
        name: 'type_info',
        description:
            'Show the resolved type, signature, and documentation of a symbol from the IDE language server.',
        inputSchema: SYMBOL_LOOKUP_SCHEMA,
    },
    {
        name: 'search_symbols',
        description: 'Search declarations in the repository by name using the IDE language server.',
        inputSchema: {
            type: 'object',
            properties: {
                query: { type: 'string', description: 'Symbol name or prefix to search for.' },
            },
            required: ['query'],
            additionalProperties: false,
        },
    },
];

type Realpath = (p: string) => Promise<string>;

/**
 * Resolves a repository-relative path to the real path of an existing file inside `root`. Rejects
 * absolute paths, `..` escapes, and symbolic links that lead outside the checkout.
 */
export async function resolveRepoFile(
    root: string,
    relativePath: unknown,
    realpath: Realpath = fs.promises.realpath,
): Promise<string> {
    if (typeof relativePath !== 'string' || !relativePath.trim()) {
        throw new ToolInputError('"path" must be a non-empty repository-relative path.');
    }
    if (relativePath.length > MAX_PATH_CHARS || relativePath.includes('\0')) {
        throw new ToolInputError('"path" is not a valid repository-relative path.');
    }
    if (path.isAbsolute(relativePath) || /^[A-Za-z]:/.test(relativePath) || relativePath.startsWith('\\\\')) {
        throw new ToolInputError('"path" must be relative to the repository root.');
    }
    const realRoot = await realpath(root);
    const candidate = path.resolve(realRoot, relativePath);
    if (!isInside(realRoot, candidate)) {
        throw new ToolInputError('"path" must stay inside the repository.');
    }
    let real: string;
    try {
        real = await realpath(candidate);
    } catch {
        throw new ToolInputError(`${relativePath} does not exist in the local checkout.`);
    }
    if (!isInside(realRoot, real)) {
        throw new ToolInputError('"path" must stay inside the repository.');
    }
    return real;
}

function isInside(root: string, candidate: string): boolean {
    const relative = path.relative(root, candidate);
    return relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative);
}

/** Repository-relative POSIX path for `absolutePath`, or undefined when it is outside every root. */
export function toRepoRelative(roots: string[], absolutePath: string | undefined): string | undefined {
    if (!absolutePath) return undefined;
    for (const root of roots) {
        if (isInside(root, absolutePath)) return path.relative(root, absolutePath).split(path.sep).join('/');
    }
    return undefined;
}

/**
 * Finds a whole-word occurrence of `symbol` in `text`: the one nearest the 1-based `line` when
 * given (earlier wins a tie), otherwise the first.
 */
export function locateSymbol(text: string, symbol: string, line?: number): CodePosition | undefined {
    const escaped = symbol.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const pattern = new RegExp(`(?<![\\w$])${escaped}(?![\\w$])`, 'g');
    const target = line === undefined ? undefined : line - 1;
    let best: CodePosition | undefined;
    let bestDistance = Number.POSITIVE_INFINITY;
    const lines = text.split(/\r?\n/);
    for (let index = 0; index < lines.length; index++) {
        pattern.lastIndex = 0;
        const match = pattern.exec(lines[index]);
        if (!match) continue;
        if (target === undefined) return { line: index, character: match.index };
        const distance = Math.abs(index - target);
        if (distance < bestDistance) {
            best = { line: index, character: match.index };
            bestDistance = distance;
        }
    }
    return best;
}

export function truncate(text: string, max: number): string {
    return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new ToolInputError('The language server did not answer in time.')), timeoutMs);
    });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function textResult(text: string, isError = false): ToolResult {
    return { content: [{ type: 'text', text }], isError };
}

interface SymbolLookupArgs {
    path: unknown;
    symbol: unknown;
    line?: unknown;
}

function requireSymbol(value: unknown): string {
    if (typeof value !== 'string' || !value.trim() || value.length > MAX_SYMBOL_CHARS || /[\r\n]/.test(value)) {
        throw new ToolInputError('"symbol" must be a single identifier of at most 200 characters.');
    }
    return value.trim();
}

function optionalLine(value: unknown): number | undefined {
    if (value === undefined || value === null) return undefined;
    if (typeof value !== 'number' || !Number.isInteger(value) || value < 1) {
        throw new ToolInputError('"line" must be a positive integer.');
    }
    return value;
}

function asRecord(args: unknown): Record<string, unknown> {
    return args !== null && typeof args === 'object' && !Array.isArray(args) ? args as Record<string, unknown> : {};
}

/** Builds the MCP tools over `root`, the reviewer's local checkout of the PR's repository. */
export function createCodeIntelligenceTools(
    root: string,
    backend: CodeIntelligenceBackend,
    options: { realpath?: Realpath; timeoutMs?: number } = {},
): CodeIntelligenceTools {
    const realpath = options.realpath ?? fs.promises.realpath;
    const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

    async function roots(): Promise<string[]> {
        const realRoot = await realpath(root).catch(() => root);
        return realRoot === root ? [root] : [realRoot, root];
    }

    async function header(): Promise<string> {
        const revision = await backend.checkoutRevision().catch(() => undefined);
        const at = revision ? ` at commit ${revision.slice(0, 12)}` : '';
        return `Results from the reviewer's local checkout${at}, which may differ from the pull request.`
            + ' Confirm cited lines with Read in the working directory.';
    }

    async function locate(args: SymbolLookupArgs): Promise<{ file: string; position: CodePosition; symbol: string }> {
        const symbol = requireSymbol(args.symbol);
        const line = optionalLine(args.line);
        const file = await resolveRepoFile(root, args.path, realpath);
        const text = await withTimeout(backend.readFile(file), timeoutMs);
        const position = locateSymbol(text, symbol, line);
        if (!position) {
            throw new ToolInputError(`"${symbol}" does not appear in ${String(args.path)} in the local checkout.`);
        }
        return { file, position, symbol };
    }

    async function formatLocations(locations: CodeLocation[], noun: string, symbol: string): Promise<string> {
        const repoRoots = await roots();
        const seen = new Set<string>();
        const lines: string[] = [];
        const fileText = new Map<string, Promise<string[]>>();
        let outside = 0;
        for (const location of locations) {
            const relative = toRepoRelative(repoRoots, location.path);
            if (!relative || !location.path) {
                outside++;
                continue;
            }
            const key = `${relative}:${location.line}`;
            if (seen.has(key)) continue;
            seen.add(key);
            if (lines.length >= MAX_LOCATIONS) continue;
            const absolute = location.path;
            if (!fileText.has(absolute)) {
                fileText.set(absolute, backend.readFile(absolute).then((t) => t.split(/\r?\n/)).catch(() => []));
            }
            const source = (await fileText.get(absolute)!)[location.line] ?? '';
            lines.push(`${relative}:${location.line + 1}: ${truncate(source.trim(), MAX_SNIPPET_CHARS)}`);
        }
        const summary = [`${seen.size} ${noun} of ${symbol} in the repository.`];
        if (seen.size > lines.length) summary.push(`Showing the first ${lines.length}.`);
        if (outside > 0) summary.push(`${outside} outside the repository omitted.`);
        return [await header(), summary.join(' '), ...lines].join('\n');
    }

    function locationTool(
        noun: string,
        lookup: (file: string, position: CodePosition) => Promise<CodeLocation[]>,
    ): (args: unknown) => Promise<ToolResult> {
        return async (args) => {
            const { file, position, symbol } = await locate(asRecord(args) as unknown as SymbolLookupArgs);
            const locations = await withTimeout(lookup(file, position), timeoutMs);
            return textResult(await formatLocations(locations, noun, symbol));
        };
    }

    const handlers: Record<string, (args: unknown) => Promise<ToolResult>> = {
        find_references: locationTool('references', (f, p) => backend.references(f, p)),
        find_definition: locationTool('definitions', (f, p) => backend.definitions(f, p)),
        find_implementations: locationTool('implementations', (f, p) => backend.implementations(f, p)),
        type_info: async (args) => {
            const { file, position, symbol } = await locate(asRecord(args) as unknown as SymbolLookupArgs);
            const hovers = await withTimeout(backend.hover(file, position), timeoutMs);
            const body = hovers.map((h) => h.trim()).filter(Boolean).join('\n\n');
            return textResult([
                await header(),
                body ? truncate(body, MAX_HOVER_CHARS) : `No type information for ${symbol}.`,
            ].join('\n'));
        },
        search_symbols: async (args) => {
            const query = asRecord(args).query;
            if (typeof query !== 'string' || !query.trim() || query.length > MAX_SYMBOL_CHARS) {
                throw new ToolInputError('"query" must be a non-empty string of at most 200 characters.');
            }
            const repoRoots = await roots();
            const symbols = await withTimeout(backend.workspaceSymbols(query.trim()), timeoutMs);
            const lines: string[] = [];
            for (const s of symbols) {
                const relative = toRepoRelative(repoRoots, s.location.path);
                if (!relative) continue;
                if (lines.length >= MAX_SYMBOLS) break;
                const container = s.containerName ? ` in ${s.containerName}` : '';
                lines.push(`${s.name} (${s.kind}${container}) — ${relative}:${s.location.line + 1}`);
            }
            const summary = lines.length ? `${lines.length} matching declarations.` : `No declarations match ${query.trim()}.`;
            return textResult([await header(), summary, ...lines].join('\n'));
        },
    };

    return {
        list: () => TOOL_DEFINITIONS,
        call(name, args) {
            const handler = Object.prototype.hasOwnProperty.call(handlers, name) ? handlers[name] : undefined;
            if (!handler) return undefined;
            return handler(args).catch((err: unknown) => textResult(
                err instanceof ToolInputError ? err.message : 'The language server lookup failed.',
                true,
            ));
        },
    };
}
