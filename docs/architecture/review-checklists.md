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
