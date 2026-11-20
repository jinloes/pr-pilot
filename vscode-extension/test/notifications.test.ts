import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import * as path from 'node:path';
import { runInNewContext } from 'node:vm';

import {
  EMPTY_NOTIFICATION_HEALTH,
  formatOverflowSummary,
  markNotificationWarningShown,
  MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL,
  mergeBySource,
  normalizeNotificationSeedSources,
  notificationMessage,
  notificationWarningMessage,
  notifySourceLabel,
  partitionNotificationBurst,
  planNotificationPoll,
  prNotificationKey,
  recordNotificationDegraded,
  recordNotificationFailure,
  recordNotificationSuccess,
  settleNotificationSources,
  shouldWarnAboutNotificationFailure,
  summaryNotificationMessage,
} from '../src/notifications';
import type { PR } from '../src/models';

function pr(owner: string, repo: string, number: number): PR {
  return {
    owner,
    repo,
    number,
    title: `Title #${number}`,
    author: 'octocat',
    createdAt: '',
    htmlUrl: 'https://github.test',
    isDraft: false,
    hasReviewDraft: false,
    reviewStatus: 'UNAVAILABLE',
  };
}

test('notifySourceLabel distinguishes the two sources', () => {
  assert.equal(notifySourceLabel('reviewRequested'), 'Review requested');
  assert.match(notifySourceLabel('starredRepo'), /Starred repo/);
});

test('notificationMessage names the source and the PR', () => {
  const msg = notificationMessage(pr('acme', 'foo', 7), 'reviewRequested');
  assert.match(msg, /Review requested/);
  assert.match(msg, /acme\/foo #7/);
  assert.match(msg, /Title #7/);
});

test('starred-repo message is labeled distinctly', () => {
  const msg = notificationMessage(pr('acme', 'bar', 9), 'starredRepo');
  assert.match(msg, /Starred repo/);
  assert.doesNotMatch(msg, /Review requested/);
});

test('mergeBySource gives review-requested precedence and preserves order', () => {
  const shared = pr('acme', 'foo', 1);
  const starredOnly = pr('acme', 'bar', 2);
  const merged = mergeBySource([shared], [shared, starredOnly]);

  assert.equal(merged.length, 2);
  assert.equal(merged[0].source, 'reviewRequested');
  assert.equal(merged[0].pr.number, 1);
  assert.equal(merged[1].source, 'starredRepo');
  assert.equal(merged[1].pr.number, 2);
});

test('notification health records failures and recovers on success', () => {
  const firstFailure = recordNotificationFailure(EMPTY_NOTIFICATION_HEALTH, 'network down', 1_000);
  const secondFailure = recordNotificationFailure(firstFailure, 'still down', 2_000);

  assert.equal(firstFailure.consecutiveFailures, 1);
  assert.equal(secondFailure.consecutiveFailures, 2);
  assert.equal(secondFailure.message, 'still down');
  assert.equal(shouldWarnAboutNotificationFailure(secondFailure, 2_000), true);

  const recovered = recordNotificationSuccess(secondFailure, 3_000);
  assert.equal(recovered.status, 'healthy');
  assert.equal(recovered.consecutiveFailures, 0);
  assert.equal(recovered.message, undefined);
  assert.equal(recovered.lastSuccessAt, 3_000);
});

test('notification health records partial success as degraded', () => {
  const first = recordNotificationDegraded(EMPTY_NOTIFICATION_HEALTH, 'starredRepo: unavailable', 1_000);
  const second = recordNotificationDegraded(first, 'starredRepo: still unavailable', 2_000);

  assert.equal(first.status, 'degraded');
  assert.equal(first.lastSuccessAt, 1_000);
  assert.equal(first.message, 'starredRepo: unavailable');
  assert.equal(shouldWarnAboutNotificationFailure(first, 1_000), false);
  assert.equal(shouldWarnAboutNotificationFailure(second, 2_000), true);
  assert.match(notificationWarningMessage(second), /partially working/);
  assert.doesNotMatch(notificationWarningMessage(second), /not working/);
});

test('settles review-requested success when starred repositories fail', async () => {
  const review = pr('acme', 'review', 1);
  const result = await settleNotificationSources([
    { source: 'reviewRequested', load: async () => [review] },
    { source: 'starredRepo', load: async () => { throw new Error('starred unavailable'); } },
  ]);

  assert.deepEqual(result.reviewRequested, [review]);
  assert.deepEqual(result.starred, []);
  assert.deepEqual(result.successfulSources, ['reviewRequested']);
  assert.equal(result.failures[0].source, 'starredRepo');
});

test('settles starred success when review-requested fails', async () => {
  const starred = pr('acme', 'starred', 2);
  const result = await settleNotificationSources([
    { source: 'reviewRequested', load: async () => { throw new Error('review unavailable'); } },
    { source: 'starredRepo', load: async () => [starred] },
  ]);

  assert.deepEqual(result.reviewRequested, []);
  assert.deepEqual(result.starred, [starred]);
  assert.deepEqual(result.successfulSources, ['starredRepo']);
  assert.equal(result.failures[0].source, 'reviewRequested');
});

test('persistent partial failure seeds the healthy source and keeps delivering its new PRs', () => {
  const existing = pr('acme', 'existing-review', 1);
  const next = pr('acme', 'new-review', 2);
  const failedSourceUnknown = pr('acme', 'unknown-starred', 3);
  const first = planNotificationPoll(new Set(), new Set(), {
    reviewRequested: [existing],
    starred: [],
    successfulSources: ['reviewRequested'],
    failures: [{ source: 'starredRepo', message: 'unavailable' }],
  });

  assert.equal(first.status, 'degraded');
  assert.deepEqual(first.seededSources, ['reviewRequested']);
  assert.deepEqual(first.notifications, []);
  assert.deepEqual(first.seen, [prNotificationKey(existing)]);

  const second = planNotificationPoll(
    new Set(first.seededSources),
    new Set(first.seen),
    {
      reviewRequested: [existing, next],
      starred: [],
      successfulSources: ['reviewRequested'],
      failures: [{ source: 'starredRepo', message: 'still unavailable' }],
    },
  );

  assert.equal(second.status, 'degraded');
  assert.deepEqual(second.notifications.map(({ pr: candidate }) => prNotificationKey(candidate)), [
    prNotificationKey(next),
  ]);
  assert.deepEqual(second.seen, [prNotificationKey(existing), prNotificationKey(next)]);
  assert.equal(second.seen.includes(prNotificationKey(failedSourceUnknown)), false);

  const repeated = planNotificationPoll(
    new Set(second.seededSources),
    new Set(second.seen),
    {
      reviewRequested: [existing, next],
      starred: [],
      successfulSources: ['reviewRequested'],
      failures: [{ source: 'starredRepo', message: 'still unavailable' }],
    },
  );
  assert.deepEqual(repeated.notifications, []);

  const recovered = planNotificationPoll(
    new Set(repeated.seededSources),
    new Set(repeated.seen),
    {
      reviewRequested: [existing, next],
      starred: [failedSourceUnknown],
      successfulSources: ['reviewRequested', 'starredRepo'],
      failures: [],
    },
  );
  assert.equal(recovered.status, 'healthy');
  assert.deepEqual(recovered.notifications, []);
  assert.deepEqual(recovered.seededSources, ['reviewRequested', 'starredRepo']);
  assert.equal(recovered.seen.includes(prNotificationKey(failedSourceUnknown)), true);
});

test('total source failure leaves an unseeded poller state unchanged', () => {
  const plan = planNotificationPoll(new Set(), new Set(), {
    reviewRequested: [],
    starred: [],
    successfulSources: [],
    failures: [
      { source: 'reviewRequested', message: 'review unavailable' },
      { source: 'starredRepo', message: 'starred unavailable' },
    ],
  });

  assert.equal(plan.status, 'failed');
  assert.deepEqual(plan.seededSources, []);
  assert.deepEqual(plan.seen, []);
  assert.deepEqual(plan.notifications, []);
});

test('per-source seed restoration validates values and supports the legacy global seed', () => {
  assert.deepEqual(normalizeNotificationSeedSources(undefined, true), [
    'reviewRequested',
    'starredRepo',
  ]);
  assert.deepEqual(
    normalizeNotificationSeedSources(['starredRepo', 'invalid', 'starredRepo'], true),
    ['starredRepo'],
  );
  assert.deepEqual(normalizeNotificationSeedSources([], true), []);
});

test('planNotificationPoll marks drafted PRs seen while suppressing duplicate notifications', () => {
  const drafted = { ...pr('acme', 'widgets', 10), hasReviewDraft: true };
  const undrafted = pr('acme', 'widgets', 11);
  const plan = planNotificationPoll(
    new Set(['reviewRequested']),
    new Set(),
    {
      reviewRequested: [drafted, undrafted],
      starred: [],
      successfulSources: ['reviewRequested'],
      failures: [],
    },
  );

  assert.deepEqual(plan.seen, ['acme/widgets#10', 'acme/widgets#11']);
  assert.deepEqual(plan.notifications.map((item) => item.pr.number), [11]);
});

test('partitionNotificationBurst caps individual notifications at 3 and formats overflow summary', () => {
  const small = [
    { pr: pr('acme', 'a', 1), source: 'reviewRequested' as const },
    { pr: pr('acme', 'a', 2), source: 'starredRepo' as const },
    { pr: pr('acme', 'a', 3), source: 'starredRepo' as const },
  ];
  assert.equal(MAX_INDIVIDUAL_NOTIFICATIONS_PER_POLL, 3);
  assert.deepEqual(partitionNotificationBurst(small), { individual: small, overflow: [] });

  const burst = [
    { pr: pr('acme', 'a', 1), source: 'reviewRequested' as const },
    { pr: pr('acme', 'a', 2), source: 'reviewRequested' as const },
    { pr: pr('acme', 'a', 3), source: 'reviewRequested' as const },
    { pr: pr('acme', 'a', 4), source: 'reviewRequested' as const },
    { pr: pr('acme', 'b', 5), source: 'starredRepo' as const },
    { pr: pr('acme', 'c', 6), source: 'starredRepo' as const },
  ];
  const { individual, overflow } = partitionNotificationBurst(burst);
  assert.deepEqual(individual.map((item) => item.pr.number), [1, 2, 3]);
  assert.deepEqual(overflow.map((item) => item.pr.number), [4, 5, 6]);
  assert.equal(
    formatOverflowSummary(overflow),
    '+3 more new pull requests (1 review requested, ★ 2 starred repos)',
  );
  assert.equal(
    summaryNotificationMessage([overflow[1]]),
    'PR Pilot · +1 more new pull requests (★ 1 starred repo)',
  );
});

test('notification warning is suppressed until its cooldown expires', () => {
  const failedTwice = recordNotificationFailure(
    recordNotificationFailure(EMPTY_NOTIFICATION_HEALTH, 'down', 1_000),
    'down',
    2_000,
  );
  const warned = markNotificationWarningShown(failedTwice, 2_000);

  assert.match(notificationWarningMessage(failedTwice), /not working/);
  assert.equal(shouldWarnAboutNotificationFailure(warned, 2_000 + 29 * 60_000), false);
  assert.equal(shouldWarnAboutNotificationFailure(warned, 2_000 + 30 * 60_000), true);
});

test('PRNotificationPoller.resetAndSync fences stale in-flight polls, coalesces a fresh silent seed, and resets failure counts', async () => {
  const infoMessages: string[] = [];
  const warningMessages: string[] = [];
  const vscodeStub = {
    window: {
      showInformationMessage: async (message: string) => {
        infoMessages.push(message);
        return undefined;
      },
      showWarningMessage: async (message: string) => {
        warningMessages.push(message);
        return undefined;
      },
    },
    commands: { executeCommand: async () => undefined },
  };
  const cache = new Map<string, { exports: Record<string, unknown> }>();
  function load(file: string): Record<string, unknown> {
    const cached = cache.get(file);
    if (cached) return cached.exports;
    const mod = { exports: {} as Record<string, unknown> };
    cache.set(file, mod);
    const requireLocal = (name: string): unknown => {
      if (name === 'vscode') return vscodeStub;
      if (name.startsWith('.')) {
        const target = path.resolve(path.dirname(file), `${name}.js`);
        if (existsSync(target)) return load(target);
      }
      return require(name);
    };
    runInNewContext(readFileSync(file, 'utf8'), {
      module: mod, exports: mod.exports, require: requireLocal,
      __dirname: path.dirname(file), __filename: file,
      console, process, Buffer, Date, setInterval: () => ({ unref: () => undefined }), clearInterval: () => undefined,
    }, { filename: file });
    return mod.exports;
  }

  const { PRNotificationPoller } = load(require.resolve('../src/notificationPoller')) as {
    PRNotificationPoller: new (...args: unknown[]) => {
      resetAndSync: () => void;
      retry: () => Promise<void>;
      dispose: () => void;
      getHealth: () => { status: string; consecutiveFailures: number };
    };
  };

  const stateStore = new Map<string, unknown>();
  let currentBaseUrl = 'https://github.com';
  let releaseGithubCom!: () => void;
  const githubComGate = new Promise<void>((resolve) => { releaseGithubCom = resolve; });
  let failRequests = false;

  const sidecarStub = {
    searchPullRequests: async (baseUrl: string) => {
      if (failRequests) throw new Error(`Unreachable ${baseUrl}`);
      if (baseUrl === 'https://github.com') {
        await githubComGate;
        return { status: 'ok', prs: [pr('acme', 'pub', 1)] };
      }
      return { status: 'ok', prs: [pr('acme', 'ghe', 99)] };
    },
    listStarredRepositories: async () => ({ status: 'ok', repositories: [] }),
  };

  const poller = new PRNotificationPoller(
    {
      globalState: {
        get: (key: string) => stateStore.get(key),
        update: async (key: string, value: unknown) => { stateStore.set(key, value); },
      },
    },
    sidecarStub,
    () => ({
      get: (key: string, fallback: unknown) => {
        if (key === 'notificationsEnabled') return true;
        if (key === 'notifyReviewRequested') return true;
        if (key === 'notifyStarredRepos') return false;
        return fallback;
      },
    }),
    () => currentBaseUrl,
    () => undefined,
  );

  // Start first resetAndSync against github.com (blocks on githubComGate), then switch to GHE and resetAndSync.
  poller.resetAndSync();
  await new Promise((resolve) => setImmediate(resolve));
  currentBaseUrl = 'https://ghe.example.com';
  poller.resetAndSync();
  releaseGithubCom();
  await poller.retry();

  // Existing GHE PR #99 was silently seeded (no retroactive toast), and a subsequent poll does not re-notify #99.
  assert.deepEqual(infoMessages, []);
  const savedSeen = stateStore.get('pr-pilot.notifications.seenState') as { seen: string[] };
  assert.deepEqual(Array.from(savedSeen.seen), ['acme/ghe#99']);

  // Two mid-typing base-URL changes that each fail once reset consecutiveFailures instead of warning.
  failRequests = true;
  currentBaseUrl = 'https://ghe-prefix-1.example';
  poller.resetAndSync();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(poller.getHealth().consecutiveFailures, 1);

  currentBaseUrl = 'https://ghe-prefix-2.example';
  poller.resetAndSync();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(poller.getHealth().consecutiveFailures, 1);
  assert.deepEqual(warningMessages, []);
  poller.dispose();
});
