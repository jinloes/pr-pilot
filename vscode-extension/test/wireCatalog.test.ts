import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'fs';
import * as path from 'path';

import { REQUIRED_CAPABILITIES } from '../src/sidecar';

// The Java-side EngineCapabilityCoverageTest proves every engine capability is *reachable* over
// JSON-RPC. It cannot prove any host actually calls it — and that gap was not theoretical: the four
// Phase 1 context capabilities (getCheckStatus/getCommits/getLinkedIssues/getRepoProfile) shipped
// exposed, coverage-tested, and completely unconsumed by this client for months, so every VS Code
// review went out with empty <ci_status>, <commits>, <linked_issue>, and <repo_profile> sections
// and nothing failed.
//
// These tests close that direction by reading the engine interfaces as the source of truth and
// asserting this client keeps up with them. Same technique as settingDefaults.test.ts, which pins
// package.json contributions against extension.ts readers.

/** Walks up from the compiled test location to the repository root (identified by settings.gradle). */
function findRepoRoot(): string {
    let dir = __dirname;
    for (let i = 0; i < 8; i++) {
        if (fs.existsSync(path.join(dir, 'settings.gradle'))) return dir;
        dir = path.dirname(dir);
    }
    throw new Error('could not locate the repository root from ' + __dirname);
}

const repoRoot = findRepoRoot();

function readSource(...segments: string[]): string {
    return fs.readFileSync(path.join(repoRoot, ...segments), 'utf8');
}

/**
 * Extracts the wire names from an engine interface's `RPC_METHODS` map — the second string in each
 * `Map.entry("javaMethod", "wire/name")` pair.
 */
function declaredWireNames(): Set<string> {
    const sources = [
        readSource('github-engine', 'src', 'main', 'java', 'com', 'jinloes', 'prpilot', 'engine', 'GitHubEngineApi.java'),
        readSource('review-engine', 'src', 'main', 'java', 'com', 'jinloes', 'prpilot', 'engine', 'ReviewEngineApi.java'),
    ];
    const names = new Set<string>();
    for (const source of sources) {
        const block = /RPC_METHODS\s*=\s*Map\.ofEntries\(([\s\S]*?)\);/.exec(source);
        assert.ok(block, 'an engine interface is missing an RPC_METHODS = Map.ofEntries(...) block');
        for (const [, wire] of block[1].matchAll(/Map\.entry\(\s*"[^"]+"\s*,\s*"([^"]+)"\s*\)/g)) {
            names.add(wire);
        }
    }
    assert.ok(names.size > 0, 'parsed no wire names from the engine interfaces');
    return names;
}

/**
 * Extracts the wire names this client actually calls from every sidecar client module. Only
 * literals in the first argument position of the request helpers count, so the transport's
 * variable-based `request(method, ...)` calls do not contribute false positives.
 */
function calledWireNames(): Set<string> {
    const sourceDir = path.join(repoRoot, 'vscode-extension', 'src');
    const source = fs.readdirSync(sourceDir)
        .filter((name) => /^sidecar.*\.ts$/.test(name))
        .map((name) => fs.readFileSync(path.join(sourceDir, name), 'utf8'))
        .join('\n');
    const names = new Set<string>();
    for (const [, wire] of source.matchAll(/this\.(?:request|requestRaw|contextSummary)\(\s*'([^']+)'/g)) {
        names.add(wire);
    }
    assert.ok(names.size > 0, 'parsed no wire calls from sidecar client modules');
    return names;
}

/** Capability group names declared by the sidecar's bootstrap service. */
function declaredCapabilities(): Set<string> {
    const source = readSource('sidecar', 'src', 'main', 'java', 'com', 'jinloes', 'prpilot', 'sidecar', 'SidecarBootstrapService.java');
    const block = /CAPABILITY_METHODS\s*=\s*Map\.ofEntries\(([\s\S]*?)\);/.exec(source);
    assert.ok(block, 'SidecarBootstrapService is missing a CAPABILITY_METHODS = Map.ofEntries(...) block');
    const names = new Set<string>();
    for (const [, capability] of block[1].matchAll(/Map\.entry\(\s*"([^"]+)"/g)) {
        names.add(capability);
    }
    assert.ok(names.size > 0, 'parsed no capability names from SidecarBootstrapService');
    return names;
}

test('the parser finds the wire names it depends on', () => {
    // Guards the tests below against silently passing on an empty set if a Java file is reformatted
    // out of the shape these regexes expect.
    assert.ok(declaredWireNames().has('prs/getCheckStatus'));
    assert.ok(declaredWireNames().has('reviews/generate'));
    assert.ok(calledWireNames().has('prs/getCheckStatus'));
});

test('sidecar client modules call every engine capability exposed over RPC', () => {
    const missing = [...declaredWireNames()].filter((name) => !calledWireNames().has(name)).sort();
    assert.deepEqual(
        missing,
        [],
        `these engine capabilities are exposed over RPC but have no sidecar client method, so VS Code `
        + `cannot reach them: ${missing.join(', ')}. Add a client method, or document the gap per AGENTS.md.`,
    );
});

test('sidecar client modules call no wire method the engines do not declare', () => {
    // `initialize` is sidecar bootstrap rather than an engine capability — the same exception the
    // Java-side coverage test makes.
    const declared = declaredWireNames();
    const unknown = [...calledWireNames()]
        .filter((name) => name !== 'initialize' && !declared.has(name))
        .sort();
    assert.deepEqual(
        unknown,
        [],
        `these wire methods are called but declared by no engine interface: ${unknown.join(', ')}`,
    );
});

test('REQUIRED_CAPABILITIES matches the sidecar capability groups exactly', () => {
    assert.deepEqual([...REQUIRED_CAPABILITIES].sort(), [...declaredCapabilities()].sort());
});

test('review generation reuses one commit fetch for linked-issue resolution', () => {
    const source = readSource('vscode-extension', 'src', 'reviewHandlers.ts');
    const start = source.indexOf('async function handleGenerateReview');
    const end = source.indexOf('\nasync function handleSaveDraft', start);
    assert.ok(start >= 0 && end > start, 'could not isolate handleGenerateReview');
    const generateReview = source.slice(start, end);

    assert.equal(
        [...generateReview.matchAll(/deps\.client\(\)\.getCommits\(/g)].length,
        1,
        'handleGenerateReview must fetch commits exactly once',
    );
    assert.match(
        generateReview,
        /const commitsPromise = deps\.client\(\)\.getCommits\([\s\S]*commitsPromise\.then\(\(commitContext\) =>[\s\S]*deps\.client\(\)\.getLinkedIssues\([\s\S]*commitContext\.closingIssueNumbers/,
    );
    assert.match(generateReview, /commits:\s*commits\.summary/);
});
