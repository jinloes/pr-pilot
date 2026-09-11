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
# Internal source inventory prerequisite

`pr_pilot_source_inventory` is an optional, **262+ only** IntelliJ MCP tool with two
operations: `DISCOVER` and `VERIFY`. It is not deep review, import/index readiness,
document freshness, or a `READY` signal. Ordinary reviews still compile against
**2026.1.4 (261)**; `ideaLocalPath` does not select the compile SDK. MCP is optional,
261 advertises no inventory tool, and an unknown MCP constructor ABI disables only
this tool. No automatic open, trust, import, refresh, document save, or indexing is
performed.

The v2 native service independently enumerates VFS children from the project and
every resolved in-worktree source root, including hidden, excluded,
ignored/generated and nested-repository leaves, except the root Git administrative
entry. It never unwraps routing providers or claims physical identity.
`Location.nativePath` and scope describe logical VFS containment, not a realpath.
Source membership comes from the native project file index, not extensions
or a `.idea` exemption. External/unresolved module sources, unloaded modules,
unmapped/unknown membership, source symlinks, changing observations and incomplete
manifests block coverage. Library/SDK roots are metadata, not byte-attested inputs.
Limits are 100,000 visited nodes, 10,000 source files, 100 MiB source bytes, 1 MiB
JSON, 30 seconds per phase/process, and 180 seconds per discovery/client operation.
Full repeated walks count toward the phase's node limit; limits fail closed rather
than returning partial success.

The internal Java entry point is:

```java
new SourceInventoryClient(new SourceInventoryClient.Launch(
    absoluteTrustedJava, absoluteWorkerJar, absoluteTrustedGit,
    absoluteTrustedIjctl, absoluteTrustedConfig, explicitServer,
    absoluteTrustedHome, absoluteExecutableDirectoryPath))
    .collect(canonicalGitRoot, expectedFullHead);
```

All assets, HOME and executable-directory PATH entries must be absolute, caller-trusted,
and outside the worktree. `absoluteExecutableDirectoryPath` is an OS-separated string
of trusted directories (including the Node installation needed by ijctl), not a command.
Do not derive these values from repository files, a project SDK, or the native model.
`collect` **always launches a fresh ordinary JVM** from the explicitly selected Java.
The native tool returns `DISCOVERED` or `VFS_VERIFIED`; neither attests disk or Git.
The external worker independently securely walks every physical leaf, reconciles the
entire native path/kind set, and proves even empty source-root directories exist.
It invokes only the fixed tool with `--no-daemon`, private temporary argument files,
bounded transport and strict response binding. It compares index path/mode/object IDs to
HEAD, rejects untracked SOURCE leaves (even ignored generated files), hashes
tracked source bytes against Git blobs without filters, and repeats Git,
inventory, hashes and native VERIFY with a fresh nonce. Only the worker's `COVERED` attests source
bytes, **not whole-worktree cleanliness** or a future-use lease.

Build the minimal, Java17-bytecode worker with `:review-engine:sourceInventoryWorkerJar`.
Its stable output is `review-engine/build/libs/pr-pilot-source-inventory-worker.jar`.
It contains only the inventory classes, bounded runner and existing Jackson dependencies:
no IntelliJ, Spring, Copilot, dependency manifest/classpath or test code.
The parent launches `JAVA -Xmx256m -jar WORKER --worker ROOT HEAD GIT IJCTL CONFIG SERVER NONCE PRIVATE_DIR`.
Child environments are cleared: only explicit HOME/PATH/private TMPDIR/TMP/TEMP
(and SystemRoot on Windows) are supplied. Ambient JVM/Node/Git/provider options
are not forwarded. Git also disables optional locks, prompts, fsmonitor and untracked cache.
Processes run in owner-only storage outside ROOT; Git uses `-C ROOT`.
Owned descendants are recorded and terminated/waited before private requests are removed,
including timeout and interruption. Worker stdout is a strict 1 MiB aggregate-capped
v2 envelope with nonce/status/code/result; native payloads inside it are strictly v2,
and old envelopes are rejected. Stderr pollution, malformed output or any mismatch fails closed.

## Packaged semantic runtime and native readiness

The engine JAR now contains the worker plus a digest/version manifest. `SemanticRuntime`
loads these classloader resources, verifies them and extracts a temporary owned copy outside
the review worktree. Installed code never falls back to a sibling Gradle build directory.
The private settings operation returns presence, hashes and physical-identity fingerprints,
not settings contents, and does not call back into MCP.

Manually configure the owner-controlled, non-symlink file
`~/.pr-pilot/semantic-review.json`:

```json
{
  "schemaVersion": 1,
  "java": "/absolute/trusted/jdk/bin/java",
  "git": "/absolute/trusted/git",
  "ijctl": "/absolute/trusted/intellij-mcp-cli/dist/cli.js",
  "ijctlConfig": "/absolute/owner-controlled/ijctl.json",
  "home": "/absolute/owner-controlled/home",
  "path": ["/absolute/trusted/node/bin", "/usr/bin", "/bin"],
  "servers": ["explicit-review-ide-server"]
}
```

Replace all placeholders. Launch assets and parent paths must not be group/other-writable
or symlinked; configs/HOME must belong to the current user, and executables must belong to
that user or root. No asset may be under the worktree. Node >=20 and exactly ijctl 0.3.0
are required; the Java runtime must support secure no-follow traversal (JDK25 on the tested
macOS configuration). Project SDKs, ambient overrides and configurable worker JARs are not
accepted. Missing setup fails closed; PR Pilot never installs tools.

The separate optional `pr_pilot_review_snapshot` schema-2 tool supports STATUS, CAPTURE and
VERIFY. STATUS returns ARMING while receipts are collected, then MANUAL_SYNC_REQUIRED:
manually sync only after arming. A paired successful import with unchanged settings can
establish a project-lifetime baseline. Settings-document changes invalidate that baseline
even if an edit is reverted without a VFS event. Settings-list/VFS changes, failed/cancelled
reloads and close/reopen also require rearming and another manual sync.

CAPTURE/VERIFY additionally require the current native inventory discovery, complete source
hash list and bounded changed ranges; VERIFY requires the snapshot ID. READY is native
readiness only: independent public `SourceInventoryClient.collect(fixtureRoot, fixtureHead)`
is still mandatory. Supply the **fixture's HEAD**, never the application repository's HEAD.
The response preserves unsupported/ambiguous/skipped/capped PSI evidence in
`declarationLimitations`; empty evidence is not proof of absence.

### Opt-in IntelliJ-assisted reviews

Ordinary reviews remain the default on both hosts. With a PR selected, expand **Review
instructions (optional) → Advanced review options**, enable **IntelliJ-assisted review**, then
Generate. Preparation returns a retained exact-head worktree and pauses; no provider is running.

1. Manually open that exact worktree in a supported IntelliJ 262+ instance with the native tools
   installed and MCP enabled. VS Code uses this separate IntelliJ project too.
2. Decide whether to trust the PR's build files. **Import/sync can execute code from the PR.**
   Neither PR Pilot nor its pinned skills performs this trust decision, import, save or indexing.
3. Select the exact server in the setup panel. Finish import/indexing and resolve dirty documents.
   On a newly armed project, perform a fresh manual sync so START/SUCCESS can establish readiness.
4. Press **Continue**. PR Pilot refetches the remote head/diff, checks saved settings and binds
   physical source coverage plus native readiness before launching the selected Claude or Copilot.
5. A readiness failure leaves setup available for **Retry** after you resolve it. A changed head
   requires a new preparation. **Use ordinary review instead** is an explicit separate action,
   never an automatic downgrade. **Cancel** abandons the operation but retains the worktree.

Evidence remains provisional until final delivery checks pass. Later import, settings, VFS,
source, index or project-instance changes invalidate deep authority, including after an earlier
candidate was generated. Both providers receive the same bounded collector evidence and full
bundled, MIT-licensed IntelliJ connection/code-intelligence skills pinned to upstream
`34ec93ad2f9dcd3177a3ffb48eebecbee7512fb4`; repository text cannot change the closed tool policy.
The bundle does not grant builds, terminal writes, dependency installation or inherited MCP.

Retained worktrees survive cancellation, host disposal and restart. To clean one, open
**Retained IntelliJ review worktrees** from Advanced options, the review context menu, or the
no-PR review pane (**Show review** on a narrow window). Refresh the list, close that exact project
in every IDE, explicitly confirm closure, and remove it. Active leases, dirty trees, tampering
or uncertain identity block cleanup; removal never uses `git worktree remove --force`.

**Verification status:** service-entry tests feed raw native observations through a package-private
test seam, then drive real STATUS/arm/START/finish/CAPTURE/VERIFY orchestration, including queued
finish invalidation and scheduled MCP dispatch. They do not manufacture READY or import baselines.
The production constructor still selects the strict real-platform adapter; incompatible tracker
ABIs fail closed. These fixtures, host callbacks and fake-provider pipelines do not prove an
installed SDK/import/provider run. Renewed real-adapter timing controls and the installed
ZIP/Boot/VSIX clean/defect/lifecycle matrix remain separate gates. The visual suite also retains an
unresolved high-contrast toast baseline mismatch; passing unit tests are not completion evidence.

## Source inventory verification and manual setup

Secure no-follow traversal is mandatory **in the external worker only**. On macOS the old Microsoft JDK 17 and the
261 SDK's test JBR may not supply `SecureDirectoryStream`; inventory explicitly
returns `UNSUPPORTED_API` rather than falling back to path-based traversal.
JDK 25 on the implementation machine supplies secure traversal. An explicitly chosen
IDE JBR25 also runs as an ordinary JVM without IDE arguments/classpath; running the
same library *in-process* under IntelliJ's routed filesystem is not sufficient.
Unsupported runtime/filesystem support returns `UNSUPPORTED_RUNTIME`/`UNSUPPORTED_API`,
never path-based fallback. No automatic provisioning occurs; ordinary reviews are unaffected.
Use a supported JDK/filesystem for positive tests; do not change global Gradle settings.
Use a session-only init script selecting `Test.javaLauncher` 25 for all Java projects
(not only the Gradle daemon), then:

```bash
./gradlew -Dorg.gradle.java.home=/absolute/supported-jdk/Contents/Home -I /absolute/session-test-runtime.gradle \
  :core:test --tests '*SourceInventoryTest' \
  :review-engine:test --tests '*SourceInventoryFilesTest'
./gradlew -Dorg.gradle.java.home=/absolute/supported-jdk/Contents/Home -I /absolute/session-test-runtime.gradle \
  :review-engine:sourceInventoryWorkerJar :review-engine:test \
  --tests '*SourceInventoryClientTest' --tests '*BoundedProcessRunnerTest' \
  :intellij-plugin:unitTest --tests '*SourceInventoryServiceTest' --tests '*SourceInventoryMcpProviderTest' \
  -x :intellij-plugin:buildWebview -x :intellij-plugin:installWebviewDeps
./gradlew spotlessCheck :intellij-plugin:buildPlugin
```

The IntelliJ Gradle plugin independently selects its unit-test JVM from the pinned
261 SDK. A daemon-JDK override alone does not change that launcher. If it lacks
secure traversal, positive worker-boundary tests are blocked on that launcher;
run the same compiled JUnit classes with a supported JVM and the Gradle test
runtime classpath, recording the alternate command and JVM. Do not treat a
launcher failure as a native membership regression or hide it as live evidence.
The SDK wrapper regressions use the actual compiled SDK provider, plus an isolated
loader for the installed SDK262 under `~/Applications/IntelliJ IDEA.app/Contents`.
The installed-SDK test is explicitly skipped when that separate installation is absent;
set the unitTest system property `sourceInventory.sdk262` for a different installation.

**TEST-4 requires explicit user authorization and manual IDE actions.** This
implementation does not perform them. Manually build/package with the existing
plugin build prerequisites, install the resulting plugin into a disposable 262+
IDE profile, enable its MCP server and configure an explicit trusted `ijctl`
server outside the fixture. Open/trust/import/index a disposable Git fixture
manually. Never install into the user's normal IDE automatically.

Include in the fixture:

- a tracked extensionless file in a native source root;
- an excluded subtree containing a manually re-included source root;
- a generated source root whose untracked/ignored file is added only after the
  clean baseline (it must fail the engine contamination check);
- untracked `.idea/workspace.xml` proven OUTSIDE_SOURCE, then the same path
  classified SOURCE in a separate fixture configuration.

For the raw protocol recipe below, set `IJCTL`, `CONFIG`, `ROOT`, and `SERVER` to
trusted absolute paths / the explicit server name. Save this Python recipe in
private temporary storage **outside the fixture** and run it there. It captures
both response envelopes and uses exactly this command:

```text
IJCTL --config CONFIG --project ROOT --server SERVER --no-daemon --timeout 30000 call pr_pilot_source_inventory --args-file REQUEST
```

```python
import hashlib, json, os, pathlib, subprocess, tempfile, uuid

root = pathlib.Path(os.environ["ROOT"]).resolve(strict=True)
evidence = pathlib.Path(tempfile.mkdtemp(prefix="inventory-evidence-")).resolve()
assert not evidence.is_relative_to(root)
os.chmod(evidence, 0o700)

def call(name, request):
    args = evidence / (name + "-request.json")
    args.write_text(json.dumps(request), encoding="utf-8")
    args.chmod(0o600)
    result = subprocess.run([
        os.environ["IJCTL"], "--config", os.environ["CONFIG"],
        "--project", str(root), "--server", os.environ["SERVER"],
        "--no-daemon", "--timeout", "30000", "call",
        "pr_pilot_source_inventory", "--args-file", str(args),
    ], capture_output=True, text=True, timeout=35)
    (evidence / (name + "-stdout.json")).write_text(result.stdout)
    (evidence / (name + "-stderr.txt")).write_text(result.stderr)
    assert result.returncode == 0 and not result.stderr
    envelope = json.loads(result.stdout)
    assert envelope["ok"] and envelope["connectionMode"] == "direct"
    assert envelope["server"] == os.environ["SERVER"]
    native = envelope["result"]
    assert not native.get("isError", False)
    payload = native.get("structuredContent")
    if payload is None:
        assert len(native["content"]) == 1
        payload = json.loads(native["content"][0]["text"])
    assert payload["nonce"] == request["nonce"]
    print(name, payload["status"], payload["reasons"])
    return payload

base = {"schemaVersion": 2, "projectPath": str(root)}
discovered = call("discover", dict(base, operation="DISCOVER", nonce=str(uuid.uuid4())))
assert discovered["status"] == "DISCOVERED"
discovery = discovered["discovery"]
hashes = []
for entry in discovery["entries"]:
    if entry["membership"] != "SOURCE":
        continue
    assert entry["kind"] == "FILE"
    name = entry["path"]
    assert all(p not in ("", ".", "..") for p in name.split("/"))
    assert "\\" not in name and "\0" not in name
    path = root / name
    assert path.resolve(strict=True).is_relative_to(root)
    assert not any(p.is_symlink() for p in (path, *path.parents))
    hashes.append({"path": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
hashes.sort(key=lambda item: item["path"].encode("utf-8"))
verify = dict(base, operation="VERIFY", nonce=str(uuid.uuid4()),
              discoveryId=discovery["discoveryId"], files=hashes)
covered = call("verify", verify)
assert covered["status"] == "VFS_VERIFIED"
print("Retain private evidence:", evidence)
```

This raw recipe is for a manually controlled disposable fixture, not a substitute
for the secure engine consumer or an atomic filesystem snapshot. **TEST-3** tests
the actual `collect` method, fixed child process and Git reconciliation; **TEST-4**
records real native membership using raw ijctl. Neither proves import/index
readiness. Keep raw evidence private and delete its temporary request files when
finished.

Within 180 seconds, repeat VERIFY with a fresh nonce after omitting a hash or
adding a hash: require rejection. Repeat after editing a source byte, changing
and reverting a source root, adding an external module source, or closing and
reopening the project: require rejection, never consume stale VFS_VERIFIED evidence.
Request a different project's path on the same bound connection and require
rejection: the 262 MCP dispatcher may reject before native invocation with
`ok=false`, `isError=true`, and `MCP_TOOL_ERROR`; if the request reaches the native service,
require `WRONG_PROJECT`. Never consume rejected evidence.
Repeat the baseline with the actual engine consumer before and
after adding ignored generated SOURCE output: only the clean baseline may pass.
Manually check ordinary plugin loading/review behavior on 261 and with MCP
disabled. Record IDE build, project instance/discovery IDs, epochs, complete
entries, file count, manifest digest, inputs and rejection reasons.

To exercise the actual public API against the manually prepared live connection,
start an ordinary supported `jshell --class-path /absolute/pr-pilot-source-inventory-worker.jar`
from private storage outside the fixture, then run (replace every placeholder):

```java
import java.nio.file.*;
import com.jinloes.prpilot.review.SourceInventoryClient;
var launch = new SourceInventoryClient.Launch(
    Path.of("/absolute/ordinary-jdk/bin/java"),
    Path.of("/absolute/pr-pilot-source-inventory-worker.jar"),
    Path.of("/absolute/git"), Path.of("/absolute/ijctl"),
    Path.of("/absolute/private/ijctl.json"), "explicit-server",
    Path.of("/absolute/trusted/home"), "/absolute/node/bin:/usr/bin:/bin");
var result = new SourceInventoryClient(launch).collect(
    Path.of("/absolute/canonical/fixture"), "FULL_EXPECTED_HEAD");
System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
```

For a manual worker-wire diagnostic, create owner-only PRIVATE_DIR outside ROOT,
generate NONCE, and run the fixed `--worker` command above with the same cleared
environment. Delete its fixed `request.json` and directory only after all owned
processes exit. Prefer `collect`, which owns timeout/cancellation and cleanup.
Require all tracked sources (including extensionless and re-included leaves);
repeat with virtual-only roots/leaves and the contamination controls above.
Raw ijctl output alone is not worker coverage. Live 262/native-index and ordinary
261/MCP-disabled checks remain required; unit seams are not their substitutes.

The integrated collector consumes this discovery/coverage rather than assuming a file manifest
or module digest. Native import/readiness remains an independent gate, with collection,
provider-stage and delivery revalidation. Manual scripts above diagnose these lower-level
authorities; they do not replace the actual host workflow or installed-provider verification.
