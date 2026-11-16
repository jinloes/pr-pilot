- A workflow triggered by pull_request_target or workflow_run that checks out, builds, or runs code from the pull request: that code runs with the base repository's secrets and a write token.
- An attacker-controlled value expanded with ${{ }} inside a run: script or a script input: a pull request or issue title or body, a comment or review body, a branch name (github.head_ref, head.ref, head.label), or a commit message or author. Pass it through env: and quote the variable instead.
- A third-party action (outside the actions and github organizations) referenced by a tag or branch instead of a full commit SHA.
- permissions missing (inheriting the repository default), write-all, or wider than the job needs; a secret or a self-hosted runner available to a job that runs for pull requests from forks.
- actions/checkout without persist-credentials: false in a job that later runs pull request code or uploads the workspace as an artifact.
- A package installed ad hoc without a pinned version, or a script downloaded and piped to a shell (curl ... | sh).
- A schema, lint, breaking-change, or test step removed, skipped by a new condition, or made non-blocking (continue-on-error: true, || true).
In other CI systems, apply the same checks: untrusted branch names, titles, or commit messages interpolated into shell steps, secrets reachable from fork builds, and unpinned images, plugins, or scripts.
