import test from 'node:test';
import assert from 'node:assert/strict';

import { buildGhAuthLoginCommand } from '../src/providerSetup';

test('buildGhAuthLoginCommand uses plain gh auth login for github.com and empty input', () => {
    assert.equal(buildGhAuthLoginCommand(''), 'gh auth login');
    assert.equal(buildGhAuthLoginCommand('https://github.com'), 'gh auth login');
    assert.equal(buildGhAuthLoginCommand('https://GITHUB.COM/'), 'gh auth login');
});

test('buildGhAuthLoginCommand passes --hostname for valid GitHub Enterprise origins', () => {
    assert.equal(
        buildGhAuthLoginCommand('https://github.example.com/'),
        'gh auth login --hostname github.example.com',
    );
    assert.equal(
        buildGhAuthLoginCommand('https://GHE.Corp.Internal'),
        'gh auth login --hostname ghe.corp.internal',
    );
});

test('buildGhAuthLoginCommand falls back safely on invalid, overlong, or unsafe URLs', () => {
    assert.equal(buildGhAuthLoginCommand('http://github.example.com'), 'gh auth login');
    assert.equal(buildGhAuthLoginCommand('https://github.example.com:8443'), 'gh auth login');
    assert.equal(buildGhAuthLoginCommand('https://user@github.example.com'), 'gh auth login');
    assert.equal(buildGhAuthLoginCommand('https://github.example.com;rm -rf /'), 'gh auth login');
    assert.equal(buildGhAuthLoginCommand('https://[::1]'), 'gh auth login');
    const overlongHost = `${'a'.repeat(63)}.`.repeat(8) + 'example.com';
    assert.equal(buildGhAuthLoginCommand(`https://${overlongHost}`), 'gh auth login');
    const overlongLabel = `${'a'.repeat(64)}.example.com`;
    assert.equal(buildGhAuthLoginCommand(`https://${overlongLabel}`), 'gh auth login');
    const maxValidHost = `${'a'.repeat(63)}.${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(57)}.com`;
    assert.equal(maxValidHost.length, 253);
    assert.equal(
        buildGhAuthLoginCommand(`https://${maxValidHost}`),
        `gh auth login --hostname ${maxValidHost}`,
    );
});
