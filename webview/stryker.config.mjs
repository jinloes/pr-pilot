export default {
  packageManager: 'npm',
  mutate: ['src/lib/**/*.ts', '!src/lib/**/*.test.ts'],
  testRunner: 'command',
  coverageAnalysis: 'off',
  commandRunner: {
    command: 'node scripts/run-logic-tests.mjs',
  },
  // The tap runner can execute node:test TAP output, but its per-test coverage does not see TSX-loaded ESM modules here.
  // diffCoverage.test.ts also reads the shared golden from ../core, so keep real repo-relative paths intact.
  inPlace: true,
  incremental: true,
  incrementalFile: 'reports/stryker-incremental.json',
  reporters: ['html', 'clear-text', 'progress', 'json'],
  thresholds: {
    break: null,
  },
}
