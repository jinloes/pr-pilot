# CI and releases

Key design decisions for `.github/` workflows and the scripts they call. Each section encodes an
active constraint future CI changes must respect. Workflow and script locations are in
[CODEMAP.md](../../CODEMAP.md#root-and-automation).

### Required checks are matched by job name

A branch ruleset requires status checks by the job's `name:`, not by its workflow file or job id.
Renaming a required job drops it from the merge gate, and a required name that no job reports blocks
every pull request, so rename a job and its ruleset entry together. The pull-request gate is `Gradle
check`, `IntelliJ plugin`, `Webview`, `Webview visual regression`, `VS Code extension`, `VS Code
extension host smoke`, `Host checks (windows-latest)`, `Host checks (macos-latest)`, `Lint
workflows`, `zizmor`, and `Dependency review`. `Changed-line coverage (report only)` and `Windows
parity (non-blocking)` stay optional by design. CodeQL's `Analyze` jobs pass even when they find
alerts; gate on alerts with a code-scanning rule, not by requiring those jobs. Jobs that run tests
upload their reports and screenshots when they fail, so a red check can be diagnosed without a
re-run; new test jobs should do the same.

The gate is the `main merge gate` repository ruleset on the default branch; list it with `gh api
repos/jinloes/pr-pilot/rulesets`. Each entry is bound to the GitHub Actions app, so a check of the
same name posted by another integration does not satisfy it. It does not require a branch to be up
to date, so open pull requests are not forced to rebase each time `main` moves. The repository
admin role may always bypass it, so an admin push straight to `main` skips the gate and the
push-triggered CI run is its only signal.

### Gradle jobs run on owner-only JDK copies

`SemanticRuntime` refuses to launch an executable that sits under a group- or other-writable
directory. The JDKs `setup-java` installs on GitHub's Ubuntu runners fail that check, so the
review-engine tests that launch `java.home` fail there. `.github/scripts/trusted-jdks.sh` copies the
JDKs it is given to `~/.trusted-jdks` and removes group and other write from the copies, the
checkout, and their ancestors that the runner user owns, failing on any ancestor it cannot fix. A
tool-cache JDK home can be a symlink to a preinstalled `/usr/lib/jvm` JDK, and a JVM reports its
resolved location as `java.home`, so the script copies the directory each home resolves to and
fails unless each copy's `java` reports that copy as `java.home`. It exports the first copy as
`JAVA_HOME` and limits Gradle toolchain discovery to the copies. Any Linux job that runs Gradle
tests must call it after `setup-java` and `setup-gradle` (`setup-java` exports its own
`JAVA_HOME`), pass the JDK Gradle runs on first, and run Gradle under `umask 022` so directories
created later are not group-writable either. The script refuses to run outside GitHub Actions
because it appends to the Gradle user home's `gradle.properties`. Do not relax the runtime check to
make CI pass.

### Changed-line coverage is report-only

JaCoCo is opt-in (`-Pcoverage`) so local builds skip instrumentation. `Gradle check` merges every
module's tests into `jacocoAggregateReport`, and the webview and VS Code jobs write LCOV with `npm
run test:coverage`. The pull-request-only coverage job runs diff-cover twice because one run cannot
mix JaCoCo XML and LCOV, writes both tables to the job summary, and annotates uncovered changed
lines as notices. It sets no `--fail-under`: coverage is reviewer input, and code that runs only in
child JVMs (the source-inventory and semantic workers) is never measured. LCOV `SF:` paths must be
repository-relative (`webview/scripts/prefix-lcov-paths.mjs`,
`vscode-extension/scripts/run-coverage.mjs`) or diff-cover matches no changed line. The job checks
out full history to diff against the base branch. JaCoCo adds a synthetic `$jacocoInit` method to
instrumented classes and interfaces, so code that reflects over declared members must skip
synthetic ones (`EngineCapabilityCoverageTest`); otherwise it passes locally and fails only in the
`-Pcoverage` CI run.

### Mutation testing is nightly and report-only

`mutation.yml` runs PIT on `core` and `review-engine` and Stryker on `webview/src/lib` nightly;
neither fits a pull-request time budget and neither enforces a score (no `mutationThreshold`,
Stryker `break: null`). Stryker runs incrementally from `reports/stryker-incremental.json`, which
the workflow caches under a per-run key and restores by prefix, so each run starts from the newest
results. PIT runs a full analysis every night: PIT 1.30.1 fails with "no history plugin has been
installed" when history is enabled, so do not set `enableDefaultIncrementalAnalysis` or
`withHistory`. PIT skips the review-engine tests that launch real processes, and the classes only
they cover, because PIT's minions do not receive the `test` task's jar and fixture system
properties (`review-engine/build.gradle`). PIT's coverage phase injects synthetic
`$$pitCoverageProbes` and `$$pitCoverageProbeSize` fields into every class it instruments,
including `core` classes that `review-engine` tests load, so reflection over declared fields must
skip synthetic ones (`SourceInventory.declaredWireFields`); otherwise tests that pass under `test`
fail PIT's coverage run with "did not pass without mutation". Stryker mutates `webview` in place
because `diffCoverage.test.ts` reads the shared golden through a repo-relative path; it restores
the originals when it finishes, so check `git status` after a killed local run.

### Playwright container image

Visual baselines are rendered in the `mcr.microsoft.com/playwright` image matching the
`@playwright/test` version locked in `webview/package-lock.json`. CI's `Webview visual regression`
job, the release workflow's `visual` job, and the local Docker command in
[webview/AGENTS.md](../../webview/AGENTS.md) must use the same `v<version>-<distro>` tag pinned by
digest; `scripts/playwright-image.mjs` fails `verify.mjs` and `Lint workflows` when they drift. The
CI containers run as uid 1001, the hosted runner's user, so files written into the mounted workspace
stay owned by the runner. Dependabot proposes `@playwright/test` outside its grouped update, and
that pull request fails until the image moves with it. To bump Playwright:

1. Update `@playwright/test` in `webview`.
2. Resolve the new tag's digest, for example with `docker buildx imagetools inspect <image>:<tag>`.
3. Replace all three references with `<image>:<tag>@sha256:<digest>`.
4. Run the Docker visual check from `webview/AGENTS.md`. If rendering changed, regenerate the
   baselines in that container with `npm run test:visual -- --update-snapshots` and commit them.
5. Run `node scripts/playwright-image.mjs`.

### Windows parity is measured, not gated

`Host checks` runs the host-sensitive subset on Windows and macOS (binary probing, worktree paths,
extension unit tests, and VSIX packaging) and is required. `Windows parity (non-blocking)` runs the
whole Gradle `check` and the webview unit tests on Windows with `continue-on-error`, because
Windows-only failures remain in `core`, `review-engine`, `review-benchmark` and `intellij-plugin`;
its test reports are uploaded for triage. The causes seen locally and in CI are POSIX file
permissions, command lines over the Windows length limit (`CreateProcess error=206`), CRLF checkouts
in test git repositories that inherit the system `core.autocrlf=true`, undeletable read-only git
object files, path canonicalization (including fixtures that use `/fixture` as an absolute path),
URL-to-path conversion, symlink privileges, and tests that need `sh`. Fix those failures rather
than skipping tests on Windows, then make the job required and drop `continue-on-error`. The
plugin's webview build starts `npm.cmd` on Windows: Java cannot launch npm's `.cmd` shim by its
bare name, and runners have no `npm.exe`, so without it `intellij-plugin` does not build there.
Non-ASCII test literals rely on
[Java sources compile as UTF-8](module-runtime-boundaries.md#java-sources-compile-as-utf-8).
`.gitattributes` pins LF for the diff-coverage golden, which both toolchains compare byte for
byte, and for shell scripts. It also disables line-ending conversion for the digest-pinned
`semantic-skills` bundle; see
[Bundled skill bytes are pinned](semantic-review.md#bundled-skill-bytes-are-pinned).

### Actions are pinned by commit SHA

Every `uses:` names a full commit SHA with a `# vX.Y.Z` comment, so a moved or compromised tag
cannot change what runs; Dependabot updates the SHA and the comment together. Workflows also set
`permissions: {}` and grant each job only what it needs, with a comment on every write grant; check
out with `persist-credentials: false`; pass `${{ }}` values to `run:` through `env:` rather than
inline; and pin Linux jobs to `ubuntu-24.04`, so a runner-image change lands as a reviewed edit.
`setup-gradle` uses `cache-provider: basic`, its MIT-licensed wrapper over `actions/cache`, instead
of the default enhanced provider, which is proprietary. `zizmor` enforces most of these rules.

### Dependency updates wait a week and arrive grouped

Dependabot opens weekly minor and patch updates grouped per ecosystem, with a 7-day cooldown so a
release that is yanked or found compromised in its first week is never proposed. Two packages stay
out of the groups: `@playwright/test` (see "Playwright container image") and `@types/vscode`, whose
minor and major updates are ignored because `vsce` rejects a declared `@types/vscode` newer than
`engines.vscode`; bump it together with `engines.vscode`. `vsce` itself is pinned in the `vsce`
script of `vscode-extension/package.json`, which `package-vsix.mjs` and the release workflow both
call. Dependabot cannot see a version inside `npm exec`, so bump that pin by hand.

### Dependency review covers npm; Gradle comes from submission

`Dependency review` fails a pull request that adds or changes a runtime dependency with a moderate
or worse advisory. The workflow sets `fail-on-scopes: runtime` explicitly instead of relying on the
action's default, so an action update cannot change the policy. Development-only packages
(`"dev": true` in the lockfile, such as the Stryker and VS Code test-harness trees) are listed in
the job summary without failing it, and Dependabot alerts still cover them. The job compares the
dependency graph of the base and head commits. npm lockfiles are read statically, but GitHub
cannot read Gradle dependencies from `build.gradle`; `dependency-submission.yml` submits `main`'s
resolved graph on every push. A pull request's own Gradle changes are therefore not reviewed
before merge; Dependabot alerts cover them once they land. The repository's dependency graph must
stay enabled, or both the review and the submission jobs fail; enabling Dependabot alerts enables
it. Submission resolves every configuration, so it excludes the `*_runIdeLocal` ones: they point
at a local IntelliJ installation and fail without `ideaLocalPath` by design (see "Pinned IntelliJ
SDK versus local sandbox" in `hosts-and-sidecar.md`).

### Transitive security fixes are temporary floors

Dependency submission reports each project's build classpath as well as its own configurations, so
a library that a Gradle plugin brings in raises alerts like a declared one. `classpath` constraints
in the `buildscript` blocks of the root and `intellij-plugin` build files floor those libraries:
the Spring Boot plugin pulls httpclient5, httpcore5, Commons Lang and Jackson for `bootBuildImage`,
and the IntelliJ Platform plugin's structure and Marketplace client libraries pull jsoup and
Jackson. A constraint only raises a version that a plugin already requests; it adds no library. A
subproject's build classloader delegates to the root's first, so both blocks and every module
declaration keep one Jackson version. In `sidecar`, Spring Boot's BOM overrides Gradle's
highest-version-wins resolution: a patched Jackson, Log4j or Commons Lang declared by an engine
module is downgraded unless `sidecar/build.gradle` raises the matching `ext[...]` BOM property.
npm has two floors of its own. `webview` overrides `qs`, because Stryker requires
`typed-rest-client` 2.3.x, which pins `qs` 6.15.1 exactly. `vscode-extension` overrides
`serialize-javascript`, because `@vscode/test-cli` requires mocha 11, and every mocha 11 release
depends on 6.x. Each floor is temporary: remove it once the plugin, BOM or parent package that
pinned the old version moves past it, after checking what resolves without it
(`buildEnvironment`, `dependencyInsight` or `npm ls`). Refresh lockfiles by hand with
`npm update --before=<date a week ago>` so they keep Dependabot's 7-day cooldown.

### CodeQL runs as advanced setup

`codeql.yml` analyzes Actions, Java/Kotlin, and JavaScript/TypeScript on pushes to `main`, pull
requests, and a weekly schedule. GitHub rejects advanced-setup uploads while default setup is
enabled, so default setup must stay off in the repository's code security settings. Java uses
`build-mode: none`, so analysis needs no JDK toolchain, IDE download, or webview build; switch to a
Gradle build only if buildless extraction misses code that matters.

### Workflow lint

`Lint workflows` runs actionlint with shellcheck on `PATH` (actionlint uses it to check `run:`
scripts), shellchecks `.github/scripts/*.sh`, and runs the Playwright image check. `zizmor` audits
the workflows and `dependabot.yml` and fails on any finding rather than uploading SARIF. Both tools'
versions are pinned in `workflow-lint.yml` with SHA-256 digests: actionlint's from its release
checksums file, and shellcheck's computed from the release asset because shellcheck publishes no
checksums. Before pushing CI changes, run the same tools locally: `actionlint`, `shellcheck
.github/scripts/*.sh`, and `zizmor .github` (add `--offline` without a GitHub token; CI also runs
the online audits). Silence a zizmor finding only with an inline `# zizmor: ignore[<audit>]` comment
that says why.

### Release builds restore no caches and publish separately

`release.yml` restores no caches (`cache-disabled` for Gradle, `package-manager-cache: false` for
Node), so nothing a pull-request run wrote can reach a published artifact. `build` holds only
`contents: read`. It reruns the pull-request checks and verifies the plugin against every
recommended IDE (`-PverifyRecommendedIdes`), where pull requests verify only the pinned platform;
those IDE downloads are why it frees runner disk first. It then uploads the plugin ZIP and VSIX.
`publish` alone holds `contents: write` and runs no project code. It creates the release with the
runner's preinstalled `gh` instead of a third-party action, marks `-rc.` tags as prereleases, and
on a re-run uploads to the existing release.
