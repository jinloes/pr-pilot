# IntelliJ-assisted review (experimental)

Maintainer and advanced-user documentation for the optional IntelliJ-assisted review mode and its
source-inventory prerequisite. The mode is experimental, off by default, and supported only with
IntelliJ IDEA 262+; ordinary reviews need none of this. See the [README](../README.md) for the
user-facing overview.

## Internal source inventory prerequisite

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

### Packaged semantic runtime and native readiness

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

#### Opt-in IntelliJ-assisted reviews

Ordinary reviews remain the default on both hosts. The mode is experimental and off by default:
first enable **Enable IntelliJ-assisted review (experimental)** under **Advanced review options** in
PR Pilot settings (IntelliJ IDEA: **Settings/Preferences > Tools > PR Pilot**; VS Code:
`pr-pilot.experimentalIntellijAssistedReview`). Users who relied on the checkbox before it was
gated must enable this setting once. While it is off, the controls below are hidden and a host
rejects any IntelliJ-assisted request instead of running an ordinary review. Then, with a PR
selected, expand **Review instructions (optional) → Advanced review options**, enable
**IntelliJ-assisted review**, then Generate. Preparation returns a retained exact-head worktree and
pauses; no provider is running.

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
no-PR review pane (**Show review** on a narrow window). Turning the setting off also hides
retained-worktree maintenance (an active assisted setup still shows it); turn it back on to remove
retained worktrees. Refresh the list, close that exact project
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

### Source inventory verification and manual setup

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
