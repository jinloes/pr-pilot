import test from 'node:test';
import assert from 'node:assert/strict';
import { createLineLogger, log, redact, setLogSink } from '../src/log';

function capture(run: () => void): string[] {
    const lines: string[] = [];
    const previous = setLogSink((line) => lines.push(line));
    try {
        run();
    } finally {
        setLogSink(previous);
    }
    return lines;
}

test('redact masks GitHub tokens and bearer credentials', () => {
    const classic = 'ghp_' + 'a'.repeat(36);
    const fine = 'github_pat_' + 'b'.repeat(40);
    assert.equal(
        redact(`token ${classic} and ${fine}; Authorization: Bearer abc.def-123456`),
        'token <redacted> and <redacted>; Authorization: Bearer <redacted>',
    );
    assert.equal(redact('git fetch timed out after 60s'), 'git fetch timed out after 60s');
});

test('log redacts before writing and survives a failing sink', () => {
    assert.deepEqual(capture(() => log('using ghp_' + 'c'.repeat(36))), ['using <redacted>']);
    const previous = setLogSink(() => { throw new Error('channel disposed'); });
    try {
        assert.doesNotThrow(() => log('still fine'));
    } finally {
        setLogSink(previous);
    }
});

test('createLineLogger emits whole lines with a prefix and joins split chunks', () => {
    const lines = capture(() => {
        const logger = createLineLogger('[sidecar] ');
        logger.write('INFO first line\nWARN sec');
        logger.write('ond line\r\n\n   \nERROR tail');
        logger.flush();
        logger.flush();
    });
    assert.deepEqual(lines, ['[sidecar] INFO first line', '[sidecar] WARN second line', '[sidecar] ERROR tail']);
});
