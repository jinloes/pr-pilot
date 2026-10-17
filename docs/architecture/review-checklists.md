# Review checklists

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Security findings name their evidence
`SECURITY_CHECKLIST`, appended to `BUG_HUNT_CHECKLIST`, admits an injection or authorization finding
only when the model names the caller-controlled source and the changed line where it reaches the
sink, or the sibling handler whose check the new code omits. Unsafe deserialization, secrets and
cryptography, and error exposure findings need the weakness on the changed line itself. Generic
hardening (a missing rate limit, a theoretical denial of service, validation with no demonstrated
sink) is excluded, and the critique drops a security finding that names none of that evidence.
Unanchored security advice is a common false positive in LLM review, so a new security class must
carry the same gate rather than widen what counts as a finding.

The preamble treats the description, commits and comments as claims to check against the code. A
contradicted claim is reported with the claim quoted in `rationale`. A change to authentication,
authorization, CI workflows, dependencies or security settings that the stated purpose does not
mention gets one minor `security` note asking the author to confirm it, and the critique keeps that
note only when `<pr_description>` and `<commits>` indeed omit the change.

A test deleted, disabled or weakened while the behavior it covered remains is a `tests` issue. Its
own file often has no added line, so the output contract lets it anchor on an added line of the
production change whose behavior it covered, and the critique's removed-code keep rule accepts that
cross-file anchor for a deleted test or assertion only. Every other removed-code finding still
anchors in its own file.

### Change-triggered checklists are deterministic and path-free
`TriggeredChecklists` adds Pass B checklists for CI workflows, dependency manifests and lockfiles,
container images, schema migrations, protobuf schemas and tooling config, protobuf and gRPC code, LLM
integrations, and test changes only when a changed file's path, or one of its added lines (DDL
statements, model SDK calls), shows that kind of change. The
triggers are regular expressions over the diff, not a model call as in `ReviewRulesPass`, so the
same diff always yields the same prompt and a change that triggers nothing costs nothing. A path
trigger fires for removal-only changes such as a deleted test file, while an added-line trigger
reads only `+` lines, so DDL in a context or removed line does not add the migration checklist. A
checklist can also exclude files outright: the protobuf and gRPC code checklist ignores generated
protobuf code, whose runtime calls would otherwise add advice meant for hand-written callers.

The section lists each triggered checklist once, in a fixed order, and never echoes a file path:
paths are author-controlled, and only trusted titles and items may enter the instruction part of the
prompt. Patterns lean broad because an unneeded checklist costs a few hundred tokens and a missed
one costs findings; narrow one only together with a look-alike negative case in
`TriggeredChecklistsTest`. Each chunked-review batch prompt is built from that batch's diff, so a
batch gets only the checklists its own files trigger. Put a check here rather than in
`BUG_HUNT_CHECKLIST` when it applies only to a recognizable kind of file.

### Review rules are trigger-gated and applied one agent per rule
`reviewRulesDirectory` rules never enter `repo_guidelines`. `LocalReviewRules.load` reads up to 50
`.md`/`.yaml`/`.yml` files (path order, depth 4, no links inside the folder, 32 KB per file, no
total cap). A YAML mapping with a valid `name` and non-blank `description`, `trigger` and `prompt`
is a structured rule (`enabled: false` skips it; `severity`, `owner` and `model` are ignored; a
duplicate name is skipped). Every other readable file is an unstructured rule named by its path
whose prompt is its content.

Built-in rules are structured YAML files in review-engine's `review-rules/` resources, listed in
`LocalReviewRules.BUILT_IN_RULE_FILES` (a test fails when the list and the folder disagree, since a
jar cannot be listed reliably). With `builtInRules` on (the default; a null wire value means on),
`load(dir, true)` puts them first and the folder's rules after. A built-in is dropped when a folder
rule declares the same name, either as a valid structured rule (which replaces it) or with
`enabled: false` (which turns it off). Unstructured `.md` rules are named by path, so they never
override one. Built-ins do not count toward the 50-file folder cap, but they do count toward the
25-rule agent cap. Their sources start with `built-in/`.

`ReviewRulesPass` runs after the candidate is built and after the attribution snapshot, and before
the hygiene pass. When structured rules exist, one tool-free selection call (90 s) sees the PR
metadata, description, changed paths with added/removed counts, and each rule's name, description
and trigger, and returns `{"triggered":[...]}`. Unknown names are ignored. Any failure or
unparseable output fails open to every structured rule, because a missed rule is worse than an extra
agent. Unstructured rules are always selected. At most 25 rules run (the rest are named in a
status), each in its own read-only agent with MCP off (10 min each, 3 concurrently on daemon
threads). An agent sees only its rule, `OUTPUT_CONTRACT`, PR metadata and the diff (the condensed
index for chunked reviews, as the hygiene pass does). Its findings are anchor-validated, end their
rationale with ` (rule: <name>)`, and are merged into the candidate only when non-empty, so a review
without rules is unchanged. A failed agent is reported and skipped.

Status lines come only from the calling thread, which polls `checkCancelled` while it waits;
providers receive a no-op status consumer because host status sinks are not thread-safe. The whole
phase has a 12-minute deadline. At expiry, queued rules are skipped and running agents are cancelled
with `Future.cancel(true)`. That interrupt makes Claude's `waitFor` and Copilot's `get` destroy or
close their run, without calling `cancelCurrentRequest`, whose token is one-way and would abort the
whole review. Findings already collected are kept. User cancellation cancels every agent and
propagates; `ClaudeService` and `CopilotService` track concurrent runs in sets so one cancel stops
them all. In deep review, the authority check runs before the selection call and before each agent
submission, and both prompts carry the semantic sections.

In recall mode, rule findings the critique dropped are restored by the hygiene restore rule (not low
confidence, anchored, not covered by a same-category finding within `HYGIENE_COVER_LINES`). Rule
findings carry no `sources` of their own and are not in the attribution snapshot. After critique,
`ReviewerAttribution.reattach` runs unchanged, and `unattributeUnmatchedRuleFindings` then clears
the primary-label fallback it gives a rule finding that no reviewer candidate matched, so a rule
finding is labeled only through a match (same file, same category, within two lines). Rule identity
is engine-owned, not read from the critique's text: before critique the pipeline snapshots each rule
finding's file, category and line as `RuleSite` values, and a final comment at such a site counts as
a rule finding even when the critique reworded it and dropped its ` (rule: <name>)` tag. A comment
that still carries the tag for a loaded rule also counts. Other unmatched comments keep the
fallback.
