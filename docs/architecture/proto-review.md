# Protobuf review

Key design decisions moved from [ARCHITECTURE.md](../../ARCHITECTURE.md). Each section encodes an
active constraint future code must respect.

### Protobuf schema compatibility is computed, not inferred
A `.proto` hunk cannot show whether a change is compatible. That depends on the enclosing message,
its reserved statements, the field a number used to belong to, and the file's syntax or edition.
`ProtoSchemaChanges` therefore gives the prompt a `<proto_schema_changes>` section. It handles up to
20 changed schemas, skipping vendored ones. For each, it:

- reads the version on the diff's old side;
- rebuilds the head version by applying the file's hunks with `UnifiedPatch`;
- parses both with `ProtoSchema` (proto2, proto3 and editions);
- compares them with `ProtoSchemaDelta` under Buf-style rule IDs such as `FIELD_SAME_TYPE` and
  `RPC_NO_DELETE`.

Each entry is tagged with the channels it breaks: `wire`, `reuse-risk`, `json`, `source` and
`semantic`, plus `alpha` or `beta` for unstable packages. Stale generated code (below) is tagged
`generated`. A type that moves to another changed schema
is reported as moved, not as deleted and added. Each entry is anchored on the changed element's added
line, or on the nearest added line, so a finding citing it attaches to the diff.

Every step is strict. Patching fails on any mismatched context or removed line, the parser rejects
syntax it does not understand, and a version over 64 KB is not read. Such a file is listed as
`unparsed` with the reason, and the model judges it from the diff, because a guessed delta would
assert breaks that do not exist with the authority of computed fact. An unexpected failure while
analyzing one schema is logged and reported as `unparsed (schema analysis failed)`: both versions
come from the pull request, so an analyzer bug must cost only that file's entries, not the review.
Entry details are built only
from identifiers, numbers and rule IDs, never from comments or string option values. The prompt
still labels names and paths as untrusted pull request data, since the author controls both
versions. The section stops at 100 entries, listed in declaration order so prompts are reproducible.

The prompt maps impacts to severities:

| Impact | Severity |
|---|---|
| `wire`, on a message an RPC reaches or code serializes | `major`, or `blocker` when the data is persisted |
| `semantic` | `major` |
| `json` | `major` with evidence the channel is used, otherwise `note` |
| `source` | `minor` with evidence the generated code is published, otherwise `note` |
| `reuse-risk` | left to the hygiene pass |
| `generated` | `note` |
| any break in an `alpha` package | `note` |

A `beta` package keeps the severity, and the finding says a removal needs a deprecation period first.

The passes split the work:

- Pass B treats each entry as fact and must not contradict it from a hunk.
- The critique keeps a finding that cites an entry even without a located caller, but downgrades an
  unevidenced `json` or `source` issue to a note.
- The hygiene pass takes its inventory of removed fields, enum values and reserved entries from the
  `FIELD_NO_DELETE_UNLESS_*`, `ENUM_VALUE_NO_DELETE_UNLESS_*` and `RESERVED_*_NO_DELETE` entries.
- The "Protobuf schemas" checklist leaves unreserved removals and deleted reserved entries to the
  hygiene pass, unless a number or name is actually reused.

Change the rule IDs, the impact tags and those prompt texts together, and bump
`ReviewPrompts.PROMPT_VERSION`.

### The schema delta compares against the diff's old side
A delta is only correct against the commit the diff starts from, which is not always `baseSha`.

- An incremental review's diff starts at the head reviewed last. `BaseCommitContext` compares against
  that baseline (`diffBaseSha`, fetched by SHA when missing), so changes reviewed before are not
  reported again.
- A pull request diff starts at the merge base. Comparing against a base branch that moved since the
  branch point would blame the pull request for the base branch's own schema changes. The full diff,
  and an incremental one whose baseline cannot be fetched, therefore use
  `git merge-base <baseSha> HEAD`.
- When the merge base cannot be computed either, the comparison falls back to `baseSha`.

Strict patching is the safety net. If the old side is wrong, the hunks do not apply and the file is
reported as `unparsed`, never compared against the wrong version. The merge base assumes `HEAD` is
the pull request head, as it is in a review worktree. Elsewhere, schemas come out `unparsed`.

Unlike guidance, the old side may be an earlier head of the pull request. Only a structural summary
at the diff's own trust level is derived from it, never guidance or instructions. The step runs after
guidance and history and before call sites, on its own 30-second share of the overall bound. A review
that changes no `.proto` file runs no extra git command. A read failure or timeout turns the affected
schemas into `unparsed` entries and costs none of the other base-commit context.

### Field comparisons follow the wire encoding
`ProtoSchemaDelta` pairs fields by number, then by name, and judges each change by what an old
reader decodes:

- A map's key and value types are compared separately, and the field takes the worse of the two
  results. A map is wire-compatible only with a repeated message whose fields are exactly key 1 and
  value 2 of the same types.
- A type change made with a cardinality change is still reported as a type change.
- Repeated to singular keeps the last value of a scalar or enum, but merges message elements into
  one. An element type the schema does not declare gets a note naming both outcomes.
- A oneof is matched by the field numbers it kept, so a rename is reported once, as
  `ONEOF_RENAMED`, even when members are added or removed with it.
- Validation options (`(buf.validate.field)`, `(validate.rules)`, `(buf.validate.predefined)`) are
  keyed by their full option path, such as `(buf.validate.field).string.min_len`, without a leading
  dot, and repeated assignments of one path are combined.

Known limits:

- Types match by last name component, and a message type counts as unchanged in shape only when
  both versions are declared in the file; the shape check is shallow and includes field names.
- An imported element type is assumed unpacked, so changing a repeated imported proto3 enum to
  singular is reported as wire-compatible.
- Message- and oneof-level validation (`(buf.validate.message)`, `(buf.validate.oneof)`) is not
  compared.

### Regenerated protobuf code is left out of the reviewed diff
`ReviewPipelineService.review` runs `GeneratedProtoCode.plan` on every review, even without a base
commit, before building the inspection manifest or base-commit context. A changed file counts as
generated protobuf code when its name matches a protoc or plugin output (`.pb.go`, `_pb2.py`,
`OuterClass.java`, and so on), or when one of its first 10 lines, visible in the diff, carries a
generator header.

A generated file is dropped from the reviewed diff as regenerated in either case:

- a generator configuration or dependency manifest changes;
- the `.proto` file it names as its source also changes. If it names no source, any changed `.proto`
  file counts.

The `.proto` change says the same thing in far fewer lines, and generated files are often a pull
request's largest. A generated file that changes alone stays in the diff and, unless it was deleted,
is listed as possibly edited by hand. The prompt asks for one note saying that the next regeneration
overwrites the edit.

Both lists go to `<generated_code>`. `PRReviewRequest.withGeneratedCodeOmitted` swaps in the smaller
diff but keeps the diff-coverage trailer, so coverage still reports only what the host left out. The
dropped files are not in the manifest, and they are passed to `BaseCommitContext` so its searches
skip them. Deep reviews are unaffected: their authorization digest is bound before the plan runs, and
execution validation never compares diffs.

Only the diff is read. Known limits:

- A hand edit inside a file regenerated in the same pull request is dropped with it; a CI
  regenerate-and-compare step is the control for that.
- `linguist-generated` attributes are ignored.
- A header outside the diff's visible lines is missed.
- Deep-review ranges still count generated files toward their file cap.

### Stale generated code is found by its generator comment
A schema changed in place with its committed generated code left as it was usually means someone
forgot to regenerate. An added, deleted, renamed, copied or binary schema does not count as changed
in place.

For those schemas, `BaseCommitContext.staleGeneratedCode` runs one `git grep -I -E` over the
old-side commit. It searches for generator source comments (`source:` or `@generated from file`) that
name each schema's file name. Only names matching `[A-Za-z0-9_.-]+\.proto` are spliced into the
pattern, with their dots escaped. Each hit is confirmed with `GeneratedProtoCode.sourceProto` and
`sameProto`. Skipped are files the diff changes or drops, `.proto` and vendored files, and paths
unsafe to list.

A schema with matches gets one `GENERATED_CODE_NOT_UPDATED [generated]` entry. It lists up to 5 files
and then counts the rest, and these entries count toward the 100-entry cap. The search fails open: a
timeout or error leaves the rest of the delta intact.

Known limits:

- Matching by path suffix and comment can be ambiguous when generators name sources relative to an
  import root.
- A hand-written file carrying such a comment is listed.
- A generated file renamed in the diff may be listed under its old path.
- `git grep` searches only below the review's working directory.

### Schema call sites include generated-code names
Code never calls a `.proto` element by its schema name alone. `ChangedSymbols` therefore reads a
changed `.proto` line as the message, enum, service, RPC or field it declares, and it adds the names
protoc's Java, Go, C++, C#, Python and JavaScript generators and the gRPC, Connect and grpc-gateway
plugins derive from it:

- for a field: `userId`, `getUserId`, `hasUserId`, `set_user_id`, and so on;
- for an RPC: its lower camel case and async forms;
- for a service: `FooServiceGrpc`, `NewFooServiceClient`, `RegisterFooServiceServer`, and so on.

Field names shared across messages (AIP standard fields such as `parent`, `page_token` and
`update_mask`, and generic words such as `name` and `status`) are skipped, because their accessors
would match other messages' code rather than consumers of the changed one. Matching stays textual and
whole-word, so the prompt still labels the hits as candidates to confirm. Enum values and oneof names
are not searched.
