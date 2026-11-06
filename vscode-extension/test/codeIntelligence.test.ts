import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
    MAX_LOCATIONS,
    createCodeIntelligenceTools,
    locateSymbol,
    resolveRepoFile,
    toRepoRelative,
    type CodeIntelligenceBackend,
    type CodeLocation,
    type CodePosition,
} from '../src/codeIntelligence';

function tempRepo(): { root: string; outside: string; cleanup: () => void } {
    const base = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-ci-test-')));
    const root = path.join(base, 'repo');
    const outside = path.join(base, 'outside');
    fs.mkdirSync(path.join(root, 'src'), { recursive: true });
    fs.mkdirSync(outside);
    fs.writeFileSync(path.join(root, 'src', 'Widget.java'), [
        'class Widget {',
        '  void render() {}',
        '  void paint() { render(); }',
        '  void rerender() { render(); }',
        '}',
    ].join('\n'));
    fs.writeFileSync(path.join(root, 'src', 'Caller.java'), 'class Caller { void go(Widget w) { w.render(); } }\n');
    fs.writeFileSync(path.join(outside, 'secret.txt'), 'secret\n');
    return { root, outside, cleanup: () => fs.rmSync(base, { recursive: true, force: true }) };
}

interface Calls {
    references: Array<{ file: string; position: CodePosition }>;
}

function fakeBackend(root: string, overrides: Partial<CodeIntelligenceBackend> = {}): { backend: CodeIntelligenceBackend; calls: Calls } {
    const calls: Calls = { references: [] };
    const backend: CodeIntelligenceBackend = {
        readFile: (p) => fs.promises.readFile(p, 'utf8'),
        definitions: () => Promise.resolve([{ path: path.join(root, 'src', 'Widget.java'), line: 1, character: 7 }]),
        references: (file, position) => {
            calls.references.push({ file, position });
            return Promise.resolve([
                { path: path.join(root, 'src', 'Widget.java'), line: 2, character: 17 },
                { path: path.join(root, 'src', 'Widget.java'), line: 2, character: 17 },
                { path: path.join(root, 'src', 'Caller.java'), line: 0, character: 37 },
                { path: '/elsewhere/Lib.java', line: 4, character: 0 },
                { path: undefined, line: 0, character: 0 },
            ]);
        },
        implementations: () => Promise.resolve([]),
        hover: () => Promise.resolve(['```java\nvoid render()\n```', '  ']),
        workspaceSymbols: () => Promise.resolve([
            { name: 'render', kind: 'Method', containerName: 'Widget', location: { path: path.join(root, 'src', 'Widget.java'), line: 1, character: 7 } },
            { name: 'render', kind: 'Method', containerName: 'Lib', location: { path: '/elsewhere/Lib.java', line: 3, character: 0 } },
        ]),
        checkoutRevision: () => Promise.resolve('0123456789abcdef0123'),
        ...overrides,
    };
    return { backend, calls };
}

function text(result: { content: Array<{ text: string }> }): string {
    return result.content.map((c) => c.text).join('\n');
}

// ── resolveRepoFile ──────────────────────────────────────────────────────────

test('resolveRepoFile resolves a repository-relative file to its real path', async () => {
    const repo = tempRepo();
    try {
        assert.equal(await resolveRepoFile(repo.root, 'src/Widget.java'), path.join(repo.root, 'src', 'Widget.java'));
        assert.equal(await resolveRepoFile(repo.root, './src/../src/Widget.java'), path.join(repo.root, 'src', 'Widget.java'));
    } finally {
        repo.cleanup();
    }
});

test('resolveRepoFile rejects absolute, escaping, empty, and non-string paths', async () => {
    const repo = tempRepo();
    try {
        for (const bad of [path.join(repo.root, 'src', 'Widget.java'), '../outside/secret.txt', 'src/../../outside/secret.txt', '', '  ', 42, undefined, 'C:\\Windows\\win.ini', '.', 'a\0b']) {
            await assert.rejects(resolveRepoFile(repo.root, bad), `expected ${String(bad)} to be rejected`);
        }
    } finally {
        repo.cleanup();
    }
});

test('resolveRepoFile rejects a symbolic link that leads outside the checkout', async () => {
    const repo = tempRepo();
    try {
        fs.symlinkSync(path.join(repo.outside, 'secret.txt'), path.join(repo.root, 'src', 'link.txt'));
        await assert.rejects(resolveRepoFile(repo.root, 'src/link.txt'), /inside the repository/);
    } finally {
        repo.cleanup();
    }
});

test('resolveRepoFile reports a missing file without leaking the absolute path', async () => {
    const repo = tempRepo();
    try {
        await assert.rejects(resolveRepoFile(repo.root, 'src/Missing.java'), (err: Error) => {
            assert.match(err.message, /src\/Missing\.java does not exist/);
            assert.ok(!err.message.includes(repo.root));
            return true;
        });
    } finally {
        repo.cleanup();
    }
});

// ── locateSymbol ─────────────────────────────────────────────────────────────

test('locateSymbol finds the first whole-word occurrence without a line hint', () => {
    assert.deepEqual(locateSymbol('rerender();\nrender();', 'render'), { line: 1, character: 0 });
});

test('locateSymbol picks the occurrence nearest the 1-based line, earlier on a tie', () => {
    const source = 'a render\nb\nc render\nd\ne render';
    assert.deepEqual(locateSymbol(source, 'render', 4), { line: 2, character: 2 });
    assert.deepEqual(locateSymbol(source, 'render', 2), { line: 0, character: 2 });
    assert.deepEqual(locateSymbol(source, 'render', 99), { line: 4, character: 2 });
});

test('locateSymbol treats regex characters literally and respects $ boundaries', () => {
    assert.deepEqual(locateSymbol('x = a.b(1); $a.b(2)', 'a.b'), { line: 0, character: 4 });
    assert.equal(locateSymbol('$render()', 'render'), undefined);
    assert.equal(locateSymbol('nothing here', 'render'), undefined);
});

// ── toRepoRelative ───────────────────────────────────────────────────────────

test('toRepoRelative maps paths under any root and drops the rest', () => {
    assert.equal(toRepoRelative(['/r', '/link'], '/link/src/A.java'), 'src/A.java');
    assert.equal(toRepoRelative(['/r'], '/other/A.java'), undefined);
    assert.equal(toRepoRelative(['/r'], undefined), undefined);
    assert.equal(toRepoRelative(['/r'], '/r'), undefined);
});

// ── createCodeIntelligenceTools ──────────────────────────────────────────────

test('tools list advertises the five read-only lookups with JSON schemas', () => {
    const tools = createCodeIntelligenceTools('/r', fakeBackend('/r').backend);
    assert.deepEqual(tools.list().map((t) => t.name), [
        'find_references', 'find_definition', 'find_implementations', 'type_info', 'search_symbols',
    ]);
    for (const tool of tools.list()) assert.equal(tool.inputSchema.type, 'object');
});

test('find_references locates the symbol near the hinted line and formats deduplicated in-repo results', async () => {
    const repo = tempRepo();
    try {
        const { backend, calls } = fakeBackend(repo.root);
        const tools = createCodeIntelligenceTools(repo.root, backend);

        const result = await tools.call('find_references', { path: 'src/Widget.java', symbol: 'render', line: 2 })!;

        assert.equal(result.isError, false);
        assert.deepEqual(calls.references[0], {
            file: path.join(repo.root, 'src', 'Widget.java'),
            position: { line: 1, character: 7 },
        });
        assert.equal(text(result), [
            'Results from the reviewer\'s local checkout at commit 0123456789ab, which may differ from the pull request. Confirm cited lines with Read in the working directory.',
            '2 references of render in the repository. 2 outside the repository omitted.',
            'src/Widget.java:3: void paint() { render(); }',
            'src/Caller.java:1: class Caller { void go(Widget w) { w.render(); } }',
        ].join('\n'));
    } finally {
        repo.cleanup();
    }
});

test('find_references caps the listed locations', async () => {
    const repo = tempRepo();
    try {
        const many: CodeLocation[] = Array.from({ length: MAX_LOCATIONS + 5 }, (_, i) => ({
            path: path.join(repo.root, 'src', 'Widget.java'), line: i, character: 0,
        }));
        const tools = createCodeIntelligenceTools(repo.root, fakeBackend(repo.root, {
            references: () => Promise.resolve(many),
            checkoutRevision: () => Promise.resolve(undefined),
        }).backend);

        const output = text(await tools.call('find_references', { path: 'src/Widget.java', symbol: 'render' })!);

        assert.match(output, new RegExp(`${MAX_LOCATIONS + 5} references of render in the repository\\. Showing the first ${MAX_LOCATIONS}\\.`));
        assert.equal(output.split('\n').length, 2 + MAX_LOCATIONS);
        assert.match(output, /^Results from the reviewer's local checkout, which/);
    } finally {
        repo.cleanup();
    }
});

test('type_info joins non-blank hover blocks', async () => {
    const repo = tempRepo();
    try {
        const tools = createCodeIntelligenceTools(repo.root, fakeBackend(repo.root).backend);
        const output = text(await tools.call('type_info', { path: 'src/Widget.java', symbol: 'render' })!);
        assert.match(output, /\n```java\nvoid render\(\)\n```$/);
    } finally {
        repo.cleanup();
    }
});

test('search_symbols keeps only declarations inside the repository', async () => {
    const repo = tempRepo();
    try {
        const tools = createCodeIntelligenceTools(repo.root, fakeBackend(repo.root).backend);
        const output = text(await tools.call('search_symbols', { query: 'render' })!);
        assert.match(output, /1 matching declarations\.\nrender \(Method in Widget\) — src\/Widget\.java:2$/);
        assert.ok(!output.includes('Lib'));
    } finally {
        repo.cleanup();
    }
});

test('invalid tool input becomes an MCP tool error rather than a thrown exception', async () => {
    const repo = tempRepo();
    try {
        const tools = createCodeIntelligenceTools(repo.root, fakeBackend(repo.root).backend);
        const cases: Array<[string, unknown, RegExp]> = [
            ['find_definition', { path: '../outside/secret.txt', symbol: 'x' }, /inside the repository/],
            ['find_definition', { path: 'src/Widget.java', symbol: 'missing' }, /"missing" does not appear in src\/Widget\.java/],
            ['find_definition', { path: 'src/Widget.java', symbol: 'a\nb' }, /"symbol" must be/],
            ['find_definition', { path: 'src/Widget.java', symbol: 'render', line: 0 }, /"line" must be/],
            ['find_definition', null, /"symbol" must be/],
            ['search_symbols', { query: '' }, /"query" must be/],
        ];
        for (const [name, args, message] of cases) {
            const result = await tools.call(name, args)!;
            assert.equal(result.isError, true, `${name} ${JSON.stringify(args)}`);
            assert.match(text(result), message);
        }
    } finally {
        repo.cleanup();
    }
});

test('a failing language server returns a generic tool error', async () => {
    const repo = tempRepo();
    try {
        const tools = createCodeIntelligenceTools(repo.root, fakeBackend(repo.root, {
            definitions: () => Promise.reject(new Error(`internal detail ${repo.root}`)),
        }).backend);
        const result = await tools.call('find_definition', { path: 'src/Widget.java', symbol: 'render' })!;
        assert.deepEqual(result, { content: [{ type: 'text', text: 'The language server lookup failed.' }], isError: true });
    } finally {
        repo.cleanup();
    }
});

test('a lookup that never answers times out as a tool error', async () => {
    const repo = tempRepo();
    try {
        const tools = createCodeIntelligenceTools(repo.root, fakeBackend(repo.root, {
            references: () => new Promise<CodeLocation[]>(() => undefined),
        }).backend, { timeoutMs: 10 });
        const result = await tools.call('find_references', { path: 'src/Widget.java', symbol: 'render' })!;
        assert.equal(result.isError, true);
        assert.match(text(result), /did not answer in time/);
    } finally {
        repo.cleanup();
    }
});

test('unknown and prototype tool names are not dispatched', () => {
    const tools = createCodeIntelligenceTools('/r', fakeBackend('/r').backend);
    assert.equal(tools.call('run_shell', {}), undefined);
    assert.equal(tools.call('constructor', {}), undefined);
    assert.equal(tools.call('__proto__', {}), undefined);
});
