# PR Pilot

PR Pilot is a dual-host code review assistant for GitHub pull requests:

- IntelliJ plugin (`intellij-plugin`)
- VS Code extension (`vscode-extension`)

It helps you discover PRs, generate AI-assisted reviews (Claude or Copilot), edit comments, and submit review drafts.

> **Beta availability:** PR Pilot is prerelease software distributed through
> [GitHub Releases](https://github.com/jinloes/pr-pilot/releases), not an IDE marketplace. Install
> only a release marked **Pre-release**. Each published beta includes an IntelliJ plugin ZIP and a
> VS Code VSIX. If the releases page has no prerelease, there is currently no installable beta.

## What it does

- Lists pull requests by scope (current repo, review-requested, assigned, authored)
- Generates structured code review feedback with inline comments
- Supports chat over PR context (title/body, diff, generated review)
- Saves and restores pending review drafts
- Uses temporary git worktrees for accurate PR-branch context
- Supports background PR notifications in both hosts

## Install a beta

### Prerequisites

Both hosts require:

- Git
- [GitHub CLI](https://cli.github.com/) authenticated to the account that can access the pull
  requests you want to review
- One authenticated review provider:
  - Claude Code CLI (`claude`)
  - GitHub Copilot CLI (`copilot`)

The VS Code extension also requires Java 17 or newer to run its bundled sidecar. IntelliJ uses the
IDE runtime and does not launch the sidecar.

Run these preflight checks in a terminal:

```bash
git --version
gh --version
gh auth status
java -version       # required for VS Code; verify the major version is 17 or newer
claude --version    # if using Claude
copilot --version   # if using Copilot
```

If `gh auth status` fails, run `gh auth login`. For provider sign-in, run `claude auth login` or
`copilot login`. PR Pilot's first-run checklist verifies the selected binary before loading the PR
list. When a provider has no safe non-interactive authentication status command, the checklist
labels authentication as **unverified** instead of claiming it is ready. Claude probe timeouts,
execution failures, and unsupported commands also remain non-blocking and unverified; only a
conclusive signed-out response blocks onboarding.

### IntelliJ IDEA

1. Open the latest **Pre-release** on the
   [releases page](https://github.com/jinloes/pr-pilot/releases) and download its IntelliJ `.zip`
   asset. Do not unzip it.
2. In IntelliJ IDEA, open **Settings/Preferences > Plugins**.
3. Select the gear menu, choose **Install Plugin from Disk**, and select the downloaded ZIP.
4. Restart the IDE when prompted.
5. Open **View > Tool Windows > PR Pilot**.

### VS Code

1. Open the latest **Pre-release** on the
   [releases page](https://github.com/jinloes/pr-pilot/releases) and download its `.vsix` asset.
2. In VS Code, open **Extensions**, select the **...** menu, choose **Install from VSIX**, and select
   the downloaded file. Alternatively, run:

   ```bash
   code --install-extension /path/to/pr-pilot-version.vsix
   ```

3. Reload VS Code when prompted.
4. Open PR Pilot from the Activity Bar or run **PR Pilot: Open PR Pilot** from the Command Palette.

### Configure a review provider

- **IntelliJ IDEA:** open **Settings/Preferences > Tools > PR Pilot**.
- **VS Code:** run **PR Pilot: Open Settings** or use the settings button in the PR Pilot view.

Select **Claude** or **Copilot** as the review provider. Keep the default model initially; model,
reasoning-effort, and MCP options are advanced controls. PR Pilot checks the selected provider CLI
during onboarding and again before generating a review. Settings remain available from the setup
screen so you can switch providers.

## Complete your first review

1. Open the local GitHub repository that contains the pull request.
2. Open PR Pilot, choose a pull-request scope, and select a pull request.
3. Confirm the provider readiness message, then select **Generate Review**.
4. Inspect the summary and inline comments, edit or remove anything you do not want to publish, and
   save the pending draft. PR Pilot also autosaves later edits to an existing draft.
5. Choose **Approve**, **Comment**, or **Request Changes**, review the confirmation, and submit the
   GitHub review.

AI output can be incomplete or wrong. Review every comment and the underlying diff before
submitting.

## Data and privacy

- GitHub authentication is sourced through `gh`; tokens stay inside the shared GitHub engine and
  are not returned to either host or persisted by PR Pilot.
- The selected local provider CLI receives the pull-request prompt and context needed to generate a
  review. Its data handling follows that provider and account's configuration.
- PR Pilot creates temporary local git worktrees for PR branch context and removes them when the
  review workspace is released.
- Pending review comments are stored as GitHub pending reviews. PR Pilot also keeps small local
  indexes and a text-free review outcome log under `~/.pr-pilot`.

See [ARCHITECTURE.md](ARCHITECTURE.md#local-data-files) for the exact local files and retained
fields.

## Troubleshooting

### GitHub authentication or access fails

Run `gh auth status`. If needed, run `gh auth login`, select the correct account, and retry. For a
404, verify the pull-request URL and that the active account can access its repository.

### The provider CLI is unavailable

Run `claude --version` or `copilot --version` in a new terminal. Install or authenticate the
selected CLI if the command fails, then restart the IDE so it inherits the updated `PATH`.

### VS Code reports a Java or sidecar error

Run `java -version` in the environment that launches VS Code and confirm Java 17 or newer is first
on `PATH`, then reload the VS Code window. Reinstall the VSIX if the bundled sidecar is reported as
missing or corrupt.

### PR branch or worktree setup fails

Confirm the open folder is a git clone with the expected GitHub `origin`, then refresh its remote
references:

```bash
git remote -v
git fetch origin
```

Retry the PR after resolving authentication, network, or local filesystem errors. Do not manually
edit PR Pilot's temporary worktrees.

### A draft needs recovery

Reopen the same pull request in PR Pilot; it loads an existing GitHub pending review when one is
available. If the local draft index is unavailable, the GitHub pending review remains the source of
truth and can also be inspected on the pull request in GitHub. Avoid deleting files under
`~/.pr-pilot` while recovering a draft.

## Beta feedback

Report bugs, installation failures, and workflow feedback in
[GitHub Issues](https://github.com/jinloes/pr-pilot/issues). Include the host, PR Pilot version,
provider, reproduction steps, and relevant error text. Do not include credentials, private source
code, or sensitive pull-request content.

## Repository layout

- `core/` - Plain Java 17 shared models and diff parser used by both hosts
- `github-engine/` - Plain Java 17 GitHub/repository/review engine shared by both hosts
- `intellij-plugin/` - IntelliJ host integration
- `review-benchmark/` - Developer CLI that measures review recall against Mae's comments
- `sidecar/` - Thin stdio JSON-RPC process adapter used by the VS Code extension
- `vscode-extension/` - VS Code host integration
- `webview/` - Shared React webview UI
- `diagrams/` - Mermaid architecture and PR review-generation diagrams
- `.github/workflows/release.yml` - Tag-driven release workflow for both plugin artifacts
- `AGENTS.md` - Agent workflow, test requirements, and parity rules
- `ARCHITECTURE.md` - Architecture details and design constraints
- `CODEMAP.md` - Task-oriented implementation and test-file map

## Development requirements

- Java 17+ (for Gradle builds and at VS Code extension runtime)
- Node.js 20.17 or newer
- npm
- GitHub CLI (`gh`) authenticated (`gh auth login`)
- Optional for runtime review providers:
  - Claude CLI (`claude`)
  - GitHub Copilot CLI (`copilot`)

The IntelliJ plugin calls `github-engine` directly in the IDE JVM. The VS Code extension bundles
the sidecar JAR and launches it with `java`; it checks protocol compatibility and required
capabilities during activation. If Java 17+ is unavailable, PR Pilot reports an actionable setup
error instead of falling back to a separate TypeScript GitHub implementation.

## Local development

### Build IntelliJ plugin

```bash
./gradlew :intellij-plugin:buildPlugin
```

Compilation, unit tests, packaging, and CI use the pinned IntelliJ **2026.1.4** SDK,
regardless of installed IDEs or `ideaLocalPath`. Ordinary reviews remain supported on
2026.1; the separate local-development sandbox requires build **262** (2026.2) or newer.
This sandbox configuration alone does not enable deep semantic reviews.

For local IDE development:

```bash
# Launch-free diagnostics and selector/wiring tests
./gradlew :intellij-plugin:testSandboxIdeSelection :intellij-plugin:printSandboxIdeSelection
# Explicitly launch the local-development sandbox
./gradlew :intellij-plugin:runIdeLocal
```

An absolute `ideaLocalPath` override takes precedence, for example:

```bash
./gradlew :intellij-plugin:printSandboxIdeSelection \
  -PideaLocalPath="$HOME/Applications/IntelliJ IDEA.app"
```

An invalid, missing, malformed, or older explicit override fails without falling back;
correct it or omit it. On macOS, without an override the selector inspects only direct
`IntelliJ*.app` children of `~/Applications` and `/Applications`, including unversioned
and EAP applications. It compares complete numeric builds from `product-info.json`,
not names or modification times; equal builds are ordered by canonical path and
aliases are deduplicated. Invalid discovered candidates are reported and skipped.
Other operating systems require an explicit override.

No eligible local installation is needed for ordinary compilation/check. Local
selection fails only when requested and never downloads a substitute IDE.
`runIdeLocal` uses its own Gradle-managed runtime and sandbox; it does not modify the
installed IDE's normal configuration or plugins. The original `runIde` remains the
pinned-SDK runner. Neither task is launched automatically by build/check.

Output:

- `intellij-plugin/build/distributions/*.zip`

### Measure review recall against Mae

List PRs (`owner/repo#N` or PR URLs, one per line) in a file and keep local clones under
`<repos-root>/<repo>`. The benchmark reviews the commit Mae first reviewed and writes Markdown and
JSON reports under `build/review-benchmark/<timestamp>/`.

```bash
./gradlew :review-benchmark:reviewBenchmark \
  --args="--prs prs.txt --repos-root ~/src --provider copilot --model <model>"
```

Run with `--args="--help"` for the matching window, judge, and output options. Add `--chunked` to
review large diffs file by file with the hosts' larger 1 MB chunked-review diff, or
`--no-call-sites` to measure recall without base-commit call-site context. The report also gives
recall on complete diffs only, since a finding in a file the size limit dropped cannot be matched.
Pull requests over GitHub's 300-file diff limit cannot be opened in the hosts at all, so treat
their benchmark score as an upper bound. Model output varies between runs, so pass `--repeat N` to
run the whole set N times; a `-summary.md` report gives recall per run with the mean, minimum, and
maximum, plus matched findings per pull request in each run.

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

This builds and stages the Java sidecar and shared webview assets before packaging.

```bash
./gradlew :sidecar:bootJar

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
./gradlew :core:test :intellij-plugin:unitTest
```

```bash
(cd webview && npm run lint)
(cd webview && npx tsc --noEmit)
(cd vscode-extension && npm run lint)
(cd vscode-extension && npx tsc --noEmit)
(cd vscode-extension && npm run test:unit)
```

## CI and release workflow

Continuous integration runs via `.github/workflows/ci.yml` on pushes to `main`, pull requests, and manual dispatch.

CI checks:

- Gradle Spotless + JVM checks (`spotlessCheck`, `check`, `:core:test`, `:intellij-plugin:unitTest`)
- Webview lint/typecheck/build
- VS Code extension lint/typecheck/unit tests/build
- Java 17 sidecar protocol smoke test and packaged `.vsix` JAR assertion

Releases are tag-driven via `.github/workflows/release.yml`.

What the workflow does:

1. Sets up Java 17 and Node 20.17+ for the exact tagged commit
2. Runs Gradle formatting/checks and JVM tests
3. Runs webview lint, typecheck, unit tests, accessibility tests, and build
4. Runs VS Code extension lint, typecheck, unit tests, build, and the sidecar protocol smoke test
5. Builds both installable artifacts and verifies their packaged contents
6. Creates a GitHub Release and uploads both artifacts only after every verification step passes

If verification fails, the workflow stops before the GitHub Release upload. Fix the failure on a new
commit and create a new tag; do not move or reuse a published tag.

Release tags must be `vX.Y.Z` or `vX.Y.Z-rc.N`. The workflow derives that version once and injects it
into both the IntelliJ plugin and VSIX manifests. Linux runs the full verification suite; Windows and
macOS CI run targeted provider-binary, worktree-path, extension test, and packaging checks. These
portable checks invoke npm through its JavaScript CLI and Gradle through the wrapper main class, so
Node 20 never needs to spawn Windows `.cmd` or `.bat` shims.

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

- GitHub authentication and API behavior are shared in `github-engine`; hosts never receive or persist GitHub tokens.
- Review-provider CLI setup remains host-specific, while prompt and review semantics stay aligned.
- For system visuals, see `diagrams/`. For design constraints and persistence files, see
  `ARCHITECTURE.md`. For implementation entry points and related tests, see `CODEMAP.md`.

### IntelliJ-assisted review (experimental)

IntelliJ-assisted review is an optional, experimental mode that is off by default and requires a
separate IntelliJ IDEA 262+ installation, a hand-written `~/.pr-pilot/semantic-review.json`, and a
manual worktree import per review. Ordinary reviews do not need it. Enabling it, the native
readiness protocol, and source-inventory verification are documented in
[docs/intellij-assisted-review.md](docs/intellij-assisted-review.md).
