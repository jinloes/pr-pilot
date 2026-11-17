---
name: cut-release
description: Cut and verify a PR Pilot release or release candidate (`vX.Y.Z` or `vX.Y.Z-rc.N`) for both the IntelliJ plugin and VS Code extension via `.github/workflows/release.yml`. Use when asked to create/cut/publish an RC, prerelease, or final release.
---

# Cut Release / Release Candidate

PR Pilot releases and release candidates are strictly tag-driven via `.github/workflows/release.yml`
(`Release Plugins`).

## Hard invariants

- **Never** run `gh release create` locally or upload hand-built `.zip` / `.vsix` artifacts. Only
  the `Publish release` job in `release.yml` holds `contents: write` and creates the GitHub Release
  after cache-free verification succeeds.
- **Never** edit `intellij-plugin/build.gradle` or `vscode-extension/package.json` versions in git.
  `scripts/release-version.mjs` derives `<version>` from the git tag during `release.yml` and stamps
  both packaged manifests.
- **Never** move, delete, or reuse a pushed release tag. If `Release Plugins` fails, land the fix on
  `main` and cut the next tag (for example `v1.0.0-rc.3`).
- Tag format must match `^v\d+\.\d+\.\d+(?:-rc\.\d+)?$` (`v1.0.0-rc.1` for prereleases, `v1.0.0` for
  final releases). Tags containing `-rc.` are automatically marked `--prerelease`.

## Preflight

1. **Inspect working tree, branch, and prior tags:**
   ```bash
   git --no-pager status -s
   git fetch origin main --tags
   git --no-pager tag --sort=-creatordate | head -n 10
   gh release list --limit 5
   ```
2. **Confirm target changes are on `origin/main` with green CI before tagging:**
   - Prefer landing changes through pull requests so the `main merge gate` ruleset runs beforehand
     and GitHub's `--generate-notes` automatically populates `## What's Changed`.
   - Check the latest `CI` and `Workflow lint` runs on `main`:
     ```bash
     gh run list --branch main --limit 5
     ```
   - Wait for in-progress `main` CI runs to succeed before pushing the release tag rather than
     pushing `main` and a `v*` tag in a single blind push.
3. **Confirm exact tag name** if the user did not specify one (for example incrementing the latest
   `-rc.N` on the current `vX.Y.Z` line vs promoting to `vX.Y.Z`).

## Tag and trigger `Release Plugins`

Create an annotated tag on the verified `origin/main` commit and push only that tag:

```bash
git tag -a <tag> -m "<tag>" <verified-main-sha>
git push origin <tag>
```

Confirm `.github/workflows/release.yml` started:

```bash
sleep 3
gh run list --workflow=release.yml --limit 3
```

## Monitor and verify publication

`Release Plugins` takes roughly **20 minutes** because `Build and verify` runs with caches disabled
and verifies the IntelliJ plugin against every recommended IDE (`-PverifyRecommendedIdes`) before
packaging `intellij-plugin-<version>.zip` and `pr-pilot-<tag>.vsix`:

1. `Webview visual regression` (~2 min, pinned Playwright container)
2. `Build and verify` (~18 min: Gradle `spotlessCheck check`, webview + VS Code checks, recommended
   IDE plugin verifier, `:sidecar:bootJar` + protocol smoke test, `.zip` and `.vsix` manifest checks)
3. `Publish release` (~20 s: creates the GitHub Release and uploads both artifacts)

Until `Publish release` finishes, GitHub shows **only a bare git tag** with no Release entry or
plugin assets — do not claim the release is published before step 3 completes.

- Watch or poll the workflow run (`gh run view <run-id>`) and tell the user the run URL and expected
  ~20-minute duration if still in progress.
- Once completed, verify the published release and both installable assets:
  ```bash
  gh release view <tag>
  ```
  Confirm:
  - `prerelease: true` for `-rc.` tags (`false` for final `vX.Y.Z` tags)
  - Both `intellij-plugin-<version>.zip` and `pr-pilot-<tag>.vsix` are attached
- **Release notes check:** GitHub's `--generate-notes` lists merged PRs only. If commits between the
  previous tag and `<tag>` landed directly on `main` without PRs (so the release body has only a
  `**Full Changelog**` link), summarize those commits (`git --no-pager log <prev-tag>..<tag> --oneline`)
  and ask before updating the release notes with `gh release edit <tag> --notes ...`.
