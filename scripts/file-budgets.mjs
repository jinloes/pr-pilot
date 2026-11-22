// Advisory LF-byte guidelines for source and test files so coding agents working in a 264k context
// window can usually read files in one pass, without forcing artificial splits when a larger
// cohesive module or test suite is cleaner design. Pre-existing large files have baselines in
// LEGACY_CEILINGS so untouched files stay quiet; crossing a target or baseline emits non-blocking
// warnings rather than failing verification.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { isAgentDoc } from './agent-docs.mjs'

export const TEST_FILE_MAX_BYTES = 20_000
export const SOURCE_FILE_MAX_BYTES = 32_000

const SIZED_CODE_FILE = /\.(java|tsx?|m?js|cjs|css|gradle)$/i
const TEST_PATH =
  /(?:^|\/)(?:src\/test|test|integration-test|e2e|visual)\/|\.(?:test|spec)\.[^/]+$|Test\.java$/

const SKIP_DIRS = new Set([
  'node_modules',
  'build',
  'dist',
  'dist-test',
  'out',
  'coverage',
  'staged-webview',
  'staged-sidecar',
  'webview-dist',
  'playwright-report',
  'test-results',
])

// Baselines for known larger files so untouched files stay quiet. Prefer extracting cohesive
// collaborators or splitting tests when natural seams exist, and remove entries once within target;
// adding or raising a baseline is allowed when keeping cohesive logic together is cleaner design.
export const LEGACY_CEILINGS = Object.freeze({
  // Source files (> 32,000 LF bytes)
  'intellij-plugin/src/main/java/com/jinloes/prpilot/services/SemanticSnapshotService.java': 46_500,
  'intellij-plugin/src/main/java/com/jinloes/prpilot/services/SourceInventoryService.java': 40_500,
  'intellij-plugin/src/main/java/com/jinloes/prpilot/settings/PluginSettingsComponent.java': 43_500,
  'intellij-plugin/src/main/java/com/jinloes/prpilot/ui/WebviewPanel.java': 51_500,
  'intellij-plugin/src/main/java/com/jinloes/prpilot/ui/WebviewReviewController.java': 48_500,
  'review-engine/src/main/java/com/jinloes/prpilot/engine/ReviewEngineApi.java': 35_000,
  'review-engine/src/main/java/com/jinloes/prpilot/review/BaseCommitContext.java': 44_000,
  'review-engine/src/main/java/com/jinloes/prpilot/review/ClaudeService.java': 33_000,
  'review-engine/src/main/java/com/jinloes/prpilot/review/ProtoSchema.java': 45_000,
  'review-engine/src/main/java/com/jinloes/prpilot/review/ProtoSchemaDelta.java': 65_000,
  'review-engine/src/main/java/com/jinloes/prpilot/review/ReviewPipelineService.java': 45_500,
  'review-engine/src/main/java/com/jinloes/prpilot/review/SourceInventoryClient.java': 47_500,
  'vscode-extension/src/reviewHandlers.ts': 37_000,

  // Test files (> 20,000 LF bytes)
  'core/src/test/java/com/jinloes/prpilot/model/PRReviewRequestTest.java': 20_500,
  'github-engine/src/test/java/com/jinloes/prpilot/sidecar/pr/DraftReviewCodecTest.java': 22_000,
  'github-engine/src/test/java/com/jinloes/prpilot/sidecar/pr/PrDiffServiceTest.java': 34_000,
  'github-engine/src/test/java/com/jinloes/prpilot/sidecar/pr/PrReviewStatusServiceTest.java': 30_000,
  'github-engine/src/test/java/com/jinloes/prpilot/sidecar/pr/PrSupplementalServiceTest.java': 28_500,
  'intellij-plugin/src/test/java/com/jinloes/prpilot/services/IntellijGitHubServiceTest.java': 33_000,
  'intellij-plugin/src/test/java/com/jinloes/prpilot/services/SemanticMcpToolsProviderTest.java': 24_000,
  'intellij-plugin/src/test/java/com/jinloes/prpilot/services/SemanticSnapshotServiceTest.java': 39_000,
  'intellij-plugin/src/test/java/com/jinloes/prpilot/services/SemanticSnapshotTestFixtures.java': 33_000,
  'intellij-plugin/src/test/java/com/jinloes/prpilot/services/SourceInventoryServiceTest.java': 38_000,
  'intellij-plugin/src/test/java/com/jinloes/prpilot/ui/WebviewPanelTest.java': 24_000,
  'review-engine/src/test/java/com/jinloes/prpilot/engine/ReviewSessionServiceTest.java': 24_000,
  'review-engine/src/test/java/com/jinloes/prpilot/review/BoundedProcessRunnerTest.java': 22_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ClaudeServiceTest.java': 26_000,
  'review-engine/src/test/java/com/jinloes/prpilot/review/CopilotModelDiscoveryTest.java': 21_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/CopilotServiceTest.java': 21_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/GitWorktreeServiceTest.java': 24_000,
  'review-engine/src/test/java/com/jinloes/prpilot/review/IjctlClientTest.java': 46_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ProtoSchemaChangesTest.java': 32_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ReviewCritiquePromptsTest.java': 21_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ReviewPipelineSecondReviewerTest.java': 20_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ReviewPipelineServiceTest.java': 27_000,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ReviewPipelineTestSupport.java': 22_000,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ReviewPromptsTest.java': 35_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ReviewResultParserTest.java': 28_000,
  'review-engine/src/test/java/com/jinloes/prpilot/review/ReviewRulesPassTest.java': 22_000,
  'review-engine/src/test/java/com/jinloes/prpilot/review/SourceInventoryClientTest.java': 47_500,
  'review-engine/src/test/java/com/jinloes/prpilot/review/SourceInventoryFilesTest.java': 28_500,
  'sidecar/src/test/java/com/jinloes/prpilot/sidecar/StdioJsonRpcServerGitHubParamsTest.java': 22_500,
  'sidecar/src/test/java/com/jinloes/prpilot/sidecar/StdioJsonRpcServerReviewsTest.java': 43_500,
  'webview/a11y/app.a11y.spec.ts': 29_000,
  'webview/a11y/review.a11y.spec.ts': 28_000,
  'webview/src/components/ChatPane/ChatPane.component.test.tsx': 24_500,
  'webview/src/components/DiffViewer/DiffViewer.component.test.tsx': 25_000,
  'webview/src/components/PRList/PRList.component.test.tsx': 21_000,
  'webview/src/components/ReviewPane/ReviewPane.component.test.tsx': 22_000,
  'webview/src/components/ReviewPane/ReviewPane.submission.component.test.tsx': 35_000,
  'webview/src/components/ReviewPane/useReviewController.component.test.tsx': 25_000,
  'webview/visual/app.visual.spec.ts': 39_500,
})

function normalize(file) {
  return file.replaceAll('\\', '/').replace(/^\.\//, '')
}

export function isSizedCodeFile(file) {
  const normalized = normalize(file)
  return Boolean(normalized) && !isAgentDoc(normalized) && SIZED_CODE_FILE.test(normalized)
}

export function isTestCodeFile(file) {
  return TEST_PATH.test(normalize(file))
}

export function listSizedCodeFiles(repoRoot) {
  const found = []
  const walk = (relativeDir) => {
    const entries = fs.readdirSync(path.join(repoRoot, relativeDir), { withFileTypes: true })
    for (const entry of entries) {
      if (entry.name.startsWith('.')) continue
      const relative = relativeDir ? `${relativeDir}/${entry.name}` : entry.name
      if (entry.isDirectory()) {
        if (!SKIP_DIRS.has(entry.name)) walk(relative)
      } else if (entry.isFile() && isSizedCodeFile(relative)) {
        found.push(relative)
      }
    }
  }
  walk('')
  return found.sort()
}

/**
 * Checks advisory single-read LF-byte guidelines across source (`<= 32_000`) and test (`<= 20_000`)
 * files under `repoRoot`, returning non-blocking `warnings` (`errors` stays empty so clean, cohesive
 * designs are never blocked by byte thresholds).
 */
export function checkFileBudgets(repoRoot, legacyCeilings = LEGACY_CEILINGS) {
  const errors = []
  const warnings = []
  const seen = new Set()

  for (const file of listSizedCodeFiles(repoRoot)) {
    seen.add(file)
    const raw = fs.readFileSync(path.join(repoRoot, file), 'utf8')
    const lfBytes = Buffer.byteLength(raw.replaceAll('\r\n', '\n'), 'utf8')
    const isTest = isTestCodeFile(file)
    const baseMax = isTest ? TEST_FILE_MAX_BYTES : SOURCE_FILE_MAX_BYTES
    const kind = isTest ? 'test' : 'source'
    const legacyMax = legacyCeilings[file]

    if (legacyMax !== undefined) {
      if (lfBytes <= baseMax) {
        warnings.push(
          `${file} is now ${lfBytes} LF bytes (<= ${baseMax}); remove its LEGACY_CEILINGS entry ` +
            'in scripts/file-budgets.mjs.',
        )
      } else if (lfBytes > legacyMax) {
        warnings.push(
          `${file} grew to ${lfBytes} LF bytes (baseline ${legacyMax}, advisory ${kind} target ` +
            `${baseMax}); extract a cohesive collaborator if a natural seam exists, or raise its ` +
            'LEGACY_CEILINGS baseline when keeping logic together is cleaner design.',
        )
      }
    } else if (lfBytes > baseMax) {
      warnings.push(
        `${file} is ${lfBytes} LF bytes (advisory ${kind} target ${baseMax}); extract a cohesive ` +
          'collaborator or split tests if a natural seam exists, or keep together when clean ' +
          'code and design warrant it.',
      )
    }
  }

  for (const file of Object.keys(legacyCeilings).sort()) {
    if (!seen.has(file)) {
      warnings.push(
        `${file} in LEGACY_CEILINGS no longer exists; remove it from scripts/file-budgets.mjs.`,
      )
    }
  }

  return { errors, warnings }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const { errors, warnings } = checkFileBudgets(repoRoot)
  warnings.forEach((warning) => console.error(`warn: ${warning}`))
  errors.forEach((error) => console.error(`error: ${error}`))
  if (errors.length === 0) {
    console.log('ok   file budgets')
  }
  process.exit(errors.length === 0 ? 0 : 1)
}
