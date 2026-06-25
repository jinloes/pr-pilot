# PR Pilot

PR Pilot is a dual-host code review assistant for GitHub pull requests:

- IntelliJ plugin (`intellij-plugin`)
- VS Code extension (`vscode-extension`)

It helps you discover PRs, generate AI-assisted reviews (Claude or Copilot), edit comments, and submit review drafts.

## What it does

- Lists pull requests by scope (current repo, review-requested, assigned, authored)
- Generates structured code review feedback with inline comments
- Supports chat over PR context (title/body, diff, generated review)
- Saves and restores pending review drafts
- Uses temporary git worktrees for accurate PR-branch context
- Supports background PR notifications in both hosts

## Repository layout

- `core/` - Kotlin Multiplatform shared logic (JVM + JS)
- `intellij-plugin/` - IntelliJ host integration
- `vscode-extension/` - VS Code host integration
- `webview/` - Shared React webview UI
- `.github/workflows/release.yml` - Tag-driven release workflow for both plugin artifacts
- `AGENTS.md` - Agent workflow, test requirements, and parity rules
- `ARCHITECTURE.md` - Architecture details and design constraints

## Requirements

- Java 17 (for Gradle builds)
- Node.js 20+ (Node 20.17+ recommended by extension engines)
- npm
- GitHub CLI (`gh`) authenticated (`gh auth login`)
- Optional for runtime review providers:
  - Claude CLI (`claude`)
  - GitHub Copilot CLI (`copilot`)

## Local development

### Build IntelliJ plugin

```bash
./gradlew :intellij-plugin:buildPlugin
```

Output:

- `intellij-plugin/build/distributions/*.zip`

### Build webview assets

```bash
cd webview
npm ci
npm run build
```

### Build/test VS Code extension

```bash
cd vscode-extension
npm ci
npm run build
npm run test:unit
```

### Package VS Code extension (`.vsix`)

This stages the shared webview assets (`webview/dist`) into `vscode-extension/webview-dist` before packaging.

```bash
cd webview
npm ci
npm run build

cd ../vscode-extension
npm ci
npm run package:vsix
```

## Verification commands

These are the repository's required checks from `AGENTS.md`:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew check
./gradlew :core:jvmTest :intellij-plugin:unitTest
```

```bash
(cd webview && npm run lint)
(cd webview && npx tsc --noEmit)
(cd vscode-extension && npm run lint)
(cd vscode-extension && npx tsc --noEmit)
(cd vscode-extension && npm run test:unit)
```

## CI and release workflow

Releases are tag-driven via `.github/workflows/release.yml`.

What the workflow does:

1. Sets up Java 17 and Node 20
2. Builds IntelliJ plugin artifact
3. Builds and packages VS Code extension artifact
4. Creates a GitHub Release and uploads both artifacts

### RC vs final release tags

- RC / prerelease: `vX.Y.Z-rc.N` (example: `v1.4.0-rc.1`)
- Final release: `vX.Y.Z` (example: `v1.4.0`)

The workflow marks tags containing `-rc.` as GitHub prereleases automatically.

### Typical release commands

```bash
git tag v1.4.0-rc.1
git push origin v1.4.0-rc.1

# later, final
git tag v1.4.0
git push origin v1.4.0
```

## Cross-host parity rule

User-facing behavior must stay aligned between IntelliJ and VS Code. If you update host-specific logic in one, update the corresponding implementation in the other (see mapping table in `AGENTS.md`).

## Notes

- Runtime auth and provider setup are host-specific, but shared model and review semantics should remain aligned.
- For deeper architecture details and persistence files, see `ARCHITECTURE.md`.

