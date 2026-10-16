import test from 'node:test';
import assert from 'node:assert/strict';

import { encodeFrame, extractFrames } from '../src/sidecar';

test('encodeFrame writes a bounded Content-Length header followed by the UTF-8 body', () => {
  const frame = encodeFrame('{"message":"héllo"}');
  const text = frame.toString('utf8');
  const body = Buffer.from('{"message":"héllo"}', 'utf8');
  assert.equal(text, `Content-Length: ${body.length}\r\n\r\n{"message":"héllo"}`);
});

test('encodeFrame rejects payloads larger than the protocol limit', () => {
  assert.throws(
    () => encodeFrame('x'.repeat(8 * 1024 * 1024 + 1)),
    /exceeds the maximum size/,
  );
});

test('extractFrames decodes a single complete frame and leaves no remainder', () => {
  const frame = encodeFrame('{"a":1}');
  const seen: string[] = [];
  const remaining = extractFrames(frame, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}']);
  assert.equal(remaining.length, 0);
});

test('extractFrames decodes multiple concatenated frames in order', () => {
  const combined = Buffer.concat([encodeFrame('{"a":1}'), encodeFrame('{"b":2}')]);
  const seen: string[] = [];
  const remaining = extractFrames(combined, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}', '{"b":2}']);
  assert.equal(remaining.length, 0);
});

test('extractFrames holds back a partial trailing frame for the next chunk', () => {
  const complete = encodeFrame('{"a":1}');
  const partialNext = encodeFrame('{"b":2}');
  const combined = Buffer.concat([complete, partialNext.subarray(0, partialNext.length - 3)]);
  const seen: string[] = [];
  const remaining = extractFrames(combined, (body) => seen.push(body));
  assert.deepEqual(seen, ['{"a":1}']);
  assert.equal(remaining.length, partialNext.length - 3);
});

test('extractFrames rejects an unparseable header', () => {
  const garbage = Buffer.from('Content-Type: application/json\r\n\r\n{}', 'ascii');
  assert.throws(() => extractFrames(garbage, () => undefined), /invalid Content-Length/);
});

test('extractFrames rejects frames larger than the protocol limit', () => {
  const oversizedHeader = Buffer.from(`Content-Length: ${8 * 1024 * 1024 + 1}\r\n\r\n`, 'ascii');
  assert.throws(() => extractFrames(oversizedHeader, () => undefined), /exceeds the maximum size/);
});
