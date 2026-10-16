// Hand-mirrored logic that no test keeps in sync. scripts/verify.mjs prints a reminder when a
// change touches one side of an entry. Every entry is tech debt: retire it by exposing the logic
// as an engine capability and deleting the TypeScript copy (see AGENTS.md), not by adding entries.
const JAVA = 'src/main/java/com/jinloes/prpilot'
const VSCODE = 'vscode-extension/src'

/**
 * `sides` holds two lists of repository-relative paths; `*` matches within one path segment.
 * A change on either side reminds you to check the other, unless `oneWay` is set, in which case
 * only changes to the first side do.
 */
export const MIRRORS = [
  {
    what: 'chat persona and focused-chat prompt (CHAT_PERSONA, buildFocusedChatPrompt)',
    sides: [[`review-engine/${JAVA}/review/ReviewChatPrompts.java`], [`${VSCODE}/claude.ts`]],
  },
  {
    what: 'worktree lifecycle (which dir belongs to the active PR, when to tear it down)',
    sides: [
      [
        `intellij-plugin/${JAVA}/ui/WebviewWorktreeManager.java`,
        `intellij-plugin/${JAVA}/ui/WorktreeCoordinator.java`,
      ],
      [`${VSCODE}/worktree.ts`],
    ],
    extra: 'Only resolveWorkingDir/clearWorktree mirror it; the git work is an engine capability.',
  },
  {
    what: 'PR notification polling, source labeling and merge logic',
    sides: [
      [`intellij-plugin/${JAVA}/services/PRNotificationService.java`],
      [`${VSCODE}/notifications.ts`, `${VSCODE}/notificationPoller.ts`],
    ],
  },
  {
    what: 'CLI binary-probing candidates',
    sides: [
      [`review-engine/${JAVA}/review/BinaryLocator.java`],
      [`${VSCODE}/claude.ts`, `${VSCODE}/copilot.ts`],
    ],
  },
  {
    what: 'Copilot model probing and model picker',
    sides: [
      [
        `review-engine/${JAVA}/review/CopilotModelDiscovery.java`,
        `intellij-plugin/${JAVA}/settings/CopilotModelSelector.java`,
      ],
      [`${VSCODE}/copilot.ts`, `${VSCODE}/copilotModel.ts`],
    ],
    extra: 'Compare listModels/filterModelIds and the selectCopilotModel command.',
  },
  {
    what: 'settings UI (provider-aware model selector, effort, base URL)',
    sides: [
      [`intellij-plugin/${JAVA}/settings/PluginSettingsComponent.java`],
      [`${VSCODE}/settings.ts`, `${VSCODE}/settingsView*.ts`],
    ],
  },
  {
    what: 'default Copilot reasoning effort (DEFAULT_REASONING_EFFORT)',
    sides: [[`review-engine/${JAVA}/review/CopilotService.java`], [`${VSCODE}/copilot.ts`]],
  },
  {
    what: 'webview bridge message schemas',
    sides: [
      ['webview/src/bridge/types.ts'],
      [
        `intellij-plugin/${JAVA}/ui/WebviewBridgeMessages.java`,
        `intellij-plugin/${JAVA}/ui/WebviewBridgeHandler.java`,
        `${VSCODE}/extension.ts`,
        `${VSCODE}/prHandlers.ts`,
        `${VSCODE}/reviewHandlers.ts`,
      ],
    ],
    oneWay: true,
  },
  {
    what: 'diff-coverage trailer grammar (split, header/path format, limits)',
    sides: [[`core/${JAVA}/model/DiffCoverage.java`], ['webview/src/lib/diffCoverage.ts']],
    extra:
      'Add accepted and rejected cases to core/src/test/resources/diff-coverage/' +
      'trailer.golden.txt, which DiffCoverageTest and diffCoverage.test.ts both read.',
  },
  {
    what: 'published review body (fallback bodies, general-note and detached-section rules)',
    sides: [
      [
        `github-engine/${JAVA}/sidecar/pr/DraftReviewMutationService.java`,
        `github-engine/${JAVA}/sidecar/pr/DraftReviewCodec.java`,
      ],
      ['webview/src/components/ReviewPane/publishBody.ts'],
    ],
    extra:
      'DraftReviewMutationServiceSubmit*Test and publishBody.test.ts pin the strings; ' +
      'retire this by adding a read-only publish-preview engine capability.',
  },
  {
    what: 'repository review instructions (key normalization, limits, compose format)',
    sides: [
      [`intellij-plugin/${JAVA}/settings/RepositoryReviewInstructions.java`],
      [`${VSCODE}/repositoryInstructions.ts`],
    ],
    extra: 'This is host-owned settings resolution, so there is no engine capability to share.',
  },
  {
    what: 'persisted settings',
    sides: [
      [`intellij-plugin/${JAVA}/settings/PluginSettings.java`],
      ['vscode-extension/package.json', `${VSCODE}/extension.ts`, `${VSCODE}/settings.ts`],
    ],
    oneWay: true,
    extra: 'A new setting needs a package.json contribution and a reader.',
  },
  {
    what: 'sidecar notification shapes (reviews/status, reviews/chunk, reviews/chatChunk)',
    sides: [
      [`sidecar/${JAVA}/sidecar/StdioJsonRpcServerAsyncReviewHandlers.java`],
      [`${VSCODE}/sidecarTransport.ts`],
    ],
    extra: 'Notifications are not in RPC_METHODS, so no test checks the dispatch.',
  },
]

function normalize(file) {
  return file.replaceAll('\\', '/').replace(/^\.\//, '')
}

function patternToRegExp(pattern) {
  const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replaceAll('*', '[^/]*')
  return new RegExp(`^${escaped}$`)
}

function matching(files, patterns) {
  const regexps = patterns.map(patternToRegExp)
  return files.filter((file) => regexps.some((regexp) => regexp.test(file)))
}

/** Returns one reminder per mirror entry that the changed paths touch. */
export function mirrorNotes(changedPaths) {
  const files = [...new Set(changedPaths.map(normalize).filter(Boolean))]
  const notes = []
  for (const mirror of MIRRORS) {
    const [first, second] = mirror.sides
    const changedFirst = matching(files, first)
    const changedSecond = mirror.oneWay ? [] : matching(files, second)
    let note
    if (changedFirst.length > 0 && changedSecond.length > 0) {
      const changed = [...changedFirst, ...changedSecond].join(', ')
      note = `both sides changed (${changed}); confirm they still agree.`
    } else if (changedFirst.length > 0) {
      note = `you changed ${changedFirst.join(', ')}; check ${second.join(', ')}.`
    } else if (changedSecond.length > 0) {
      note = `you changed ${changedSecond.join(', ')}; check ${first.join(', ')}.`
    } else {
      continue
    }
    notes.push(`Hand-mirrored ${mirror.what}: ${note}${mirror.extra ? ` ${mirror.extra}` : ''}`)
  }
  return notes
}
