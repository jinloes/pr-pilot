package com.jinloes.prpilot.review;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Pass B checklists for kinds of change that need checks the always-on bug hunt does not carry: CI
 * workflows, dependency manifests, container images, schema migrations, protobuf schemas and code,
 * LLM integrations, and test changes.
 *
 * <p>A checklist is added to the first-pass prompt only when a changed file's path, or one of its
 * added lines, shows that kind of change, so a review pays for these checks only on the pull
 * requests that need them. The triggers read nothing but the diff, unlike the model-selected
 * triggers of {@link LocalReviewRules}. An unneeded checklist costs a few hundred prompt tokens
 * while a missed one costs findings, so the patterns lean broad.
 */
final class TriggeredChecklists {

    /**
     * A checklist and its trigger: a file matches when {@code path} is found in its path, or {@code
     * addedLine} in one of its added lines, unless {@code excluded} accepts the file. Any of the
     * three may be null.
     */
    record Checklist(
            String title,
            Pattern path,
            Pattern addedLine,
            Predicate<InspectionManifest.FileTarget> excluded,
            String items) {
        Checklist(String title, Pattern path, Pattern addedLine, String items) {
            this(title, path, addedLine, null, items);
        }

        boolean matches(InspectionManifest.FileTarget file) {
            if (excluded != null && excluded.test(file)) return false;
            if (path != null && path.matcher(file.path()).find()) return true;
            if (addedLine == null) return false;
            for (InspectionManifest.HunkTarget hunk : file.hunks()) {
                for (int line : hunk.changedNewLines()) {
                    String text = file.newSideLines().get(line);
                    if (text != null && addedLine.matcher(text).find()) return true;
                }
            }
            return false;
        }
    }

    private static final Pattern CI_PATH =
            Pattern.compile(
                    "(?i)(?:^|/)(?:\\.github/workflows/[^/]+\\.ya?ml|action\\.ya?ml"
                            + "|\\.gitlab-ci\\.ya?ml|azure-pipelines[^/]*\\.ya?ml|jenkinsfile[^/]*"
                            + "|\\.circleci/config\\.ya?ml|bitbucket-pipelines\\.ya?ml"
                            + "|\\.buildkite/[^/]+\\.ya?ml|\\.travis\\.ya?ml)$");

    /** Package and build manifests and lockfiles; also generator inputs for generated code. */
    static final Pattern DEPENDENCY_PATH =
            Pattern.compile(
                    "(?i)(?:^|/)(?:package\\.json|package-lock\\.json|npm-shrinkwrap\\.json"
                            + "|yarn\\.lock|pnpm-lock\\.yaml|\\.npmrc|\\.yarnrc(?:\\.yml)?"
                            + "|build\\.gradle(?:\\.kts)?|settings\\.gradle(?:\\.kts)?"
                            + "|[^/]*\\.versions\\.toml|gradle\\.lockfile"
                            + "|gradle-wrapper\\.properties|pom\\.xml"
                            + "|requirements[^/]*\\.(?:txt|in)|pipfile(?:\\.lock)?|pyproject\\.toml"
                            + "|poetry\\.lock|uv\\.lock|setup\\.py|setup\\.cfg|go\\.mod|go\\.sum"
                            + "|cargo\\.toml|cargo\\.lock|gemfile(?:\\.lock)?|[^/]*\\.gemspec"
                            + "|composer\\.(?:json|lock)|[^/]*\\.(?:cs|fs|vb)proj"
                            + "|directory\\.(?:packages|build)\\.props|packages\\.config"
                            + "|packages\\.lock\\.json|nuget\\.config|podfile(?:\\.lock)?"
                            + "|package\\.swift|package\\.resolved|pubspec\\.(?:yaml|lock)"
                            + "|packages/manifest\\.json|packages-lock\\.json)$");

    private static final Pattern CONTAINER_PATH =
            Pattern.compile(
                    "(?i)(?:^|/)(?:(?:[^/]*\\.)?(?:dockerfile|containerfile)(?:\\.[^/]*)?"
                            + "|(?:docker-)?compose(?:[.-][^/]*)?\\.ya?ml)$");

    /** Migration folders, except the prose documents (upgrade guides) some keep there. */
    private static final Pattern MIGRATION_PATH =
            Pattern.compile(
                    "(?i)(?:^|/)(?:migrations?|db/migrate|db/changelog|alembic|flyway|liquibase)/"
                            + "(?!.*\\.(?:md|markdown|rst|adoc|txt)$)"
                            + "|(?:^|/)v\\d+(?:[._]\\d+)*__[^/]+\\.sql$");

    private static final Pattern DDL_LINE =
            Pattern.compile(
                    "(?i)\\b(?:alter\\s+table|create\\s+(?:unique\\s+)?index"
                            + "|drop\\s+(?:table|index|column)|rename\\s+column"
                            + "|truncate\\s+table)\\b");

    private static final Pattern PROMPT_PATH =
            Pattern.compile("(?i)(?:^|/)prompts?/|\\.prompty?$|\\.prompt\\.[a-z0-9]+$");

    private static final Pattern PROTO_PATH = Pattern.compile("(?i)\\.proto$");

    private static final Pattern PROTO_TOOLING_PATH =
            Pattern.compile(
                    "(?i)(?:^|/)(?:buf(?:\\.work|\\.gen|\\.policy)?\\.ya?ml|buf\\.lock"
                            + "|proto\\.lock)$");

    /** Protobuf and gRPC runtime imports, types, and calls in hand-written code. */
    private static final Pattern PROTO_CODE_LINE =
            Pattern.compile(
                    "com\\.google\\.protobuf|\\bio\\.grpc\\b"
                            + "|google\\.golang\\.org/(?:protobuf|grpc)|_pb2(?:_grpc)?\\b"
                            + "|@bufbuild/protobuf|@connectrpc/|\\bGoogle\\.Protobuf\\b"
                            + "|\\bGrpc\\.(?:Core|Net)\\b|\\bgrpc\\.aio\\b"
                            + "|\\bprotojson\\.|\\bJsonFormat\\b|\\bjson_format\\b"
                            + "|\\bproto\\.(?:Marshal|Unmarshal|Merge|Equal|Clone)\\b"
                            + "|\\b(?:mergeFrom|MergeFrom|SerializeToString|ParseFromString)\\("
                            + "|\\bUNRECOGNIZED\\b|\\bnew(?:Blocking|Future)?Stub\\("
                            + "|\\bwithDeadline(?:After)?\\(|\\bstatus\\.Errorf?\\("
                            + "|\\bStatusRuntimeException\\b"
                            + "|\\bgrpc\\.(?:NewClient|Dial|DialContext|NewServer)\\(");

    /**
     * Model SDKs, model names, and prompt or tool-call identifiers. SDK names match as identifier
     * prefixes (OpenAIClient, LangChain4j); names that are also English words need a word boundary
     * (cohere, not coherent) or an SDK qualifier (bedrock-runtime, not a "bedrock" comment).
     */
    private static final Pattern LLM_LINE =
            Pattern.compile(
                    "(?i)\\b(?:openai|anthropic|langchain|langgraph|llama_?index|litellm|ollama"
                            + "|mistralai|huggingface|vertexai|genai|semantic_?kernel|cohere\\b"
                            + "|bedrock-?(?:runtime|agent)|springframework\\.ai\\b"
                            + "|generate_?content\\b|messages\\.create\\b|system_?prompt"
                            + "|tool_?calls?\\b|chatlanguagemodel|llms?\\b|llm(?-i:[A-Z_])"
                            + "|gpt-[0-9o]|claude-(?:[0-9]|opus|sonnet|haiku)|gemini-[0-9])"
                            + "|chat\\.?completions?");

    private static final Pattern TEST_PATH =
            Pattern.compile(
                    "(?:^|/)(?i:tests?|specs?|__tests__|androidtest|testfixtures"
                            + "|integrationtest|e2e)/"
                            + "|(?:^|/)test_[^/]*\\.py$"
                            + "|_(?:test|spec)\\.(?:go|py|rb|exs?)$"
                            + "|\\.(?:test|spec)\\.[A-Za-z0-9]+$"
                            + "|[a-z0-9](?:Test|Tests|Spec|IT)\\.(?:java|kt|kts|cs|scala|groovy"
                            + "|swift)$");

    static final List<Checklist> CHECKLISTS =
            List.of(
                    new Checklist(
                            "CI workflows",
                            CI_PATH,
                            null,
                            "- A workflow triggered by pull_request_target or workflow_run that"
                                    + " checks out, builds, or runs code from the pull request:"
                                    + " that code runs with the base repository's secrets and a"
                                    + " write token.\n"
                                    + "- An attacker-controlled value expanded with ${{ }} inside"
                                    + " a run: script or a script input: a pull request or issue"
                                    + " title or body, a comment or review body, a branch name"
                                    + " (github.head_ref, head.ref, head.label), or a commit"
                                    + " message or author. Pass it through env: and quote the"
                                    + " variable instead.\n"
                                    + "- A third-party action (outside the actions and github"
                                    + " organizations) referenced by a tag or branch instead of"
                                    + " a full commit SHA.\n"
                                    + "- permissions missing (inheriting the repository default),"
                                    + " write-all, or wider than the job needs; a secret or a"
                                    + " self-hosted runner available to a job that runs for pull"
                                    + " requests from forks.\n"
                                    + "- actions/checkout without persist-credentials: false in a"
                                    + " job that later runs pull request code or uploads the"
                                    + " workspace as an artifact.\n"
                                    + "- A package installed ad hoc without a pinned version, or"
                                    + " a script downloaded and piped to a shell (curl ... |"
                                    + " sh).\n"
                                    + "- A schema, lint, breaking-change, or test step removed,"
                                    + " skipped by a new condition, or made non-blocking"
                                    + " (continue-on-error: true, || true).\n"
                                    + "In other CI systems, apply the same checks: untrusted"
                                    + " branch names, titles, or commit messages interpolated"
                                    + " into shell steps, secrets reachable from fork builds, and"
                                    + " unpinned images, plugins, or scripts.\n"),
                    new Checklist(
                            "Dependency manifests and lockfiles",
                            DEPENDENCY_PATH,
                            null,
                            "- A new dependency whose name resembles a popular package's (a"
                                    + " likely typosquat), or one the change neither uses nor"
                                    + " explains. You cannot query the registry to prove a"
                                    + " package exists, so report this as a \"note\" that names"
                                    + " the resemblance.\n"
                                    + "- A floating version (latest, *, +, an open range, a"
                                    + " snapshot) where the repository otherwise pins exact"
                                    + " versions.\n"
                                    + "- A dependency added or changed in a manifest whose"
                                    + " lockfile exists in the working directory but is not"
                                    + " updated in this diff.\n"
                                    + "- A dependency resolved from a git URL, a tarball or file"
                                    + " URL, or a new registry or repository (including a build"
                                    + " wrapper's distribution URL); a lockfile entry whose"
                                    + " resolved URL points to a different host than the rest"
                                    + " of the file, or whose integrity hash changes while its"
                                    + " version does not.\n"
                                    + "- A new install, postinstall, or prepare script, or a"
                                    + " build plugin that runs code at install or build time.\n"
                                    + "- A major-version upgrade while code in the repository"
                                    + " still uses an API the new version removed or changed;"
                                    + " confirm the call site with Grep before reporting it.\n"),
                    new Checklist(
                            "Container images",
                            CONTAINER_PATH,
                            null,
                            "- A base image referenced by latest or another mutable tag"
                                    + " without a digest.\n"
                                    + "- A container that runs as root: no USER instruction, or"
                                    + " a later USER root.\n"
                                    + "- A secret, token, or private key copied into the image,"
                                    + " passed as a build ARG or ENV, or deleted in a later"
                                    + " layer (earlier layers keep it).\n"
                                    + "- A COPY or ADD of the whole build context when no"
                                    + " .dockerignore excludes .git, .env, and credential"
                                    + " files.\n"
                                    + "- ADD from a remote URL, or a download piped to a shell,"
                                    + " without checksum verification.\n"
                                    + "- In a compose file, a service that runs privileged,"
                                    + " mounts the Docker socket, uses the host network or PID"
                                    + " namespace, or holds a secret inline in its"
                                    + " environment.\n"),
                    new Checklist(
                            "Schema migrations",
                            MIGRATION_PATH,
                            DDL_LINE,
                            "- A column type change, a new NOT NULL or CHECK constraint, or a"
                                    + " default computed per row (now(), random()) that rewrites"
                                    + " or scans the whole table while holding a lock, on a"
                                    + " table that may be large. Safer forms: add the"
                                    + " constraint as NOT VALID and validate it separately, or"
                                    + " backfill in batches.\n"
                                    + "- An index created or dropped without CONCURRENTLY"
                                    + " (PostgreSQL) or the database's online option, which"
                                    + " blocks writes while it runs.\n"
                                    + "- A column or table renamed or dropped while the"
                                    + " currently deployed code, which keeps running during the"
                                    + " rollout, still reads or writes it. Use expand and"
                                    + " contract: add the new shape, deploy code that uses it,"
                                    + " backfill, then remove the old one in a later release.\n"
                                    + "- A new NOT NULL column with no default or backfill for"
                                    + " existing rows, or a large backfill inside the same"
                                    + " transaction as the schema change.\n"
                                    + "- A destructive DROP, TRUNCATE, or DELETE with no"
                                    + " down-migration, backup, or note that the data loss is"
                                    + " intended.\n"),
                    new Checklist(
                            "Protobuf schemas",
                            PROTO_PATH,
                            null,
                            "- A field whose number changed, or a field number or name that a"
                                    + " different field used in the base version or that a"
                                    + " reserved statement lists: data written by one version is"
                                    + " misread by the other.\n"
                                    + "- A type or label change on an existing field. Only these"
                                    + " keep the binary wire format, and an old reader still"
                                    + " truncates values out of its range: among int32, uint32,"
                                    + " int64, uint64, and bool; sint32 and sint64; fixed32 and"
                                    + " sfixed32; fixed64 and sfixed64; string to bytes; an enum"
                                    + " and an integer type. Of these, only int32 and uint32,"
                                    + " int64 and uint64, and the fixed and sfixed pairs also"
                                    + " keep JSON. Repeated to singular loses data. Adding or"
                                    + " removing optional, required, or a oneof around a field"
                                    + " changes its presence and its generated code.\n"
                                    + "- A renamed field or enum value, or a changed json_name:"
                                    + " the binary wire format is unaffected, but ProtoJSON,"
                                    + " text format, and FieldMask paths break.\n"
                                    + "- A new enum whose zero value is not <ENUM>_UNSPECIFIED or"
                                    + " has a business meaning, or an enum whose zero value"
                                    + " changes, which changes what unset means.\n"
                                    + "- A service or RPC removed or renamed, or an RPC's request"
                                    + " type, response type, or streaming changed: each breaks"
                                    + " existing clients. A changed package or language option"
                                    + " moves generated imports and Any type URLs.\n"
                                    + "- A meaning change with no structural one: a changed"
                                    + " default, a comment that changes a field's unit, format,"
                                    + " or meaning, an existing field made REQUIRED, IMMUTABLE,"
                                    + " or OUTPUT_ONLY, a tightened protovalidate or PGV rule,"
                                    + " pagination added to an existing RPC, or a changed"
                                    + " google.api.http binding.\n"
                                    + "- A new RPC whose shape is cheap to fix only now: no"
                                    + " dedicated request and response messages"
                                    + " (google.protobuf.Empty or a wrapper type instead), a"
                                    + " List RPC without page_size, page_token, and"
                                    + " next_page_token, an Update RPC without update_mask, or a"
                                    + " mutation that clients retry without request_id. Report"
                                    + " these as \"suggestion\" unless the file's other RPCs"
                                    + " already follow the pattern.\n"
                                    + "A break on a message reachable from a service RPC or"
                                    + " serialized by code is type \"issue\", category"
                                    + " \"compatibility\", severity \"major\", or \"blocker\""
                                    + " when the message is persisted (a database, cache, queue,"
                                    + " or event log). A break that affects only JSON, text"
                                    + " format, or generated source needs the evidence the"
                                    + " protobuf rule above asks for. In an alpha package"
                                    + " (v1alpha1) breaks are allowed, so report them as"
                                    + " \"note\"; in a beta package, say that a removal needs a"
                                    + " deprecation period first. A removed field or enum value"
                                    + " left unreserved, or a deleted reserved entry, belongs to"
                                    + " the separate schema-evolution hygiene check; report it"
                                    + " here only when a number or name is actually reused.\n"),
                    new Checklist(
                            "Protobuf tooling config",
                            PROTO_TOOLING_PATH,
                            null,
                            "- A weaker breaking-change policy: breaking.use moved to a looser"
                                    + " category (FILE, then PACKAGE, WIRE_JSON, WIRE), new"
                                    + " except, ignore, or ignore_only entries,"
                                    + " ignore_unstable_packages turned on, or the breaking"
                                    + " section removed. Name the schema change in this pull"
                                    + " request that the loosening lets through.\n"
                                    + "- Lint exceptions added for files this pull request"
                                    + " changes.\n"
                                    + "- A changed module name or managed-mode prefix"
                                    + " (go_package_prefix, java_package_prefix), which moves the"
                                    + " generated import paths of every consumer.\n"
                                    + "- A buf.gen.yaml plugin or version change while neither"
                                    + " the diff nor <generated_code> shows regenerated"
                                    + " output.\n"
                                    + "- A buf.lock dependency change (googleapis,"
                                    + " protovalidate): review it as a dependency upgrade.\n"
                                    + "- proto.lock entries deleted or edited by hand: the file"
                                    + " records the field numbers already used, and editing it"
                                    + " hides their reuse.\n"),
                    new Checklist(
                            "Protobuf and gRPC code",
                            null,
                            PROTO_CODE_LINE,
                            GeneratedProtoCode::isGenerated,
                            "- A switch, when, or match over a protobuf enum read from"
                                    + " another process with no default branch: enums are open,"
                                    + " so a newer writer's value arrives as an unknown number."
                                    + " In Java, getNumber() throws on UNRECOGNIZED; use the"
                                    + " field's Value getter.\n"
                                    + "- A Java or Kotlin message getter compared with null:"
                                    + " getters return a default instance, never null; use"
                                    + " hasX().\n"
                                    + "- A partial update applied by merging, or by replacing the"
                                    + " stored message, without a FieldMask: an"
                                    + " implicit-presence field cannot be set to zero, empty, or"
                                    + " false that way, and merging appends to repeated fields"
                                    + " instead of replacing them.\n"
                                    + "- Serialized message bytes used as a cache key, hash,"
                                    + " signature input, ETag, or equality check: serialization"
                                    + " is not canonical across versions, languages, or map"
                                    + " order. In Go, reflect.DeepEqual or == on messages is"
                                    + " wrong too (use proto.Equal), as are copying a message by"
                                    + " value and encoding one with encoding/json (use"
                                    + " protojson).\n"
                                    + "- At a JSON boundary: a parser that rejects unknown fields"
                                    + " (JsonFormat.parser() without ignoringUnknownFields(),"
                                    + " json_format.Parse without ignore_unknown_fields) on data"
                                    + " from another deployable, so a newer writer's new field"
                                    + " fails the read; a message relayed through JSON or a"
                                    + " field-by-field copy, which drops unknown fields; or an"
                                    + " int64 read as a JSON number, although ProtoJSON writes"
                                    + " it as a string.\n"
                                    + "- A gRPC call with no deadline or context timeout (gRPC"
                                    + " sets none by default); a handler that calls downstream"
                                    + " with a fresh context instead of the incoming one, losing"
                                    + " its deadline and cancellation; or a channel created per"
                                    + " request.\n"
                                    + "- A gRPC handler that returns validation failures as"
                                    + " UNKNOWN or INTERNAL instead of INVALID_ARGUMENT, passes a"
                                    + " downstream error status straight to its own caller, or"
                                    + " returns NOT_FOUND before checking permission, which"
                                    + " leaks existence.\n"
                                    + "- REQUIRED field_behavior, protovalidate, or PGV"
                                    + " annotations relied on with no validator or interceptor"
                                    + " on the server path (annotations validate nothing on"
                                    + " their own), or an OUTPUT_ONLY field accepted from the"
                                    + " client and stored.\n"
                                    + "- From untrusted input: an Any unpacked without checking"
                                    + " its type (Python's Unpack() returns False and leaves the"
                                    + " message empty), a raised recursion or size limit, or a"
                                    + " page_token whose embedded filter or tenant is trusted"
                                    + " without authorizing each page.\n"
                                    + "Report a trap the diff shows firing as type \"issue\";"
                                    + " channel reuse, keepalive tuning, and similar design"
                                    + " advice are \"suggestion\".\n"),
                    new Checklist(
                            "LLM integrations",
                            PROMPT_PATH,
                            LLM_LINE,
                            "- Untrusted text (user input, documents, web pages, tool"
                                    + " results, repository content) placed into a prompt"
                                    + " without marking it as data, or into the system or"
                                    + " developer message.\n"
                                    + "- Model output used as SQL, a shell command, a file path,"
                                    + " a URL, HTML, or code, or as tool arguments, without"
                                    + " validation, escaping, or an allow-list.\n"
                                    + "- A tool or agent granted more than the task needs"
                                    + " (write, network, or shell access, or broad credentials),"
                                    + " or allowed to take destructive actions without"
                                    + " confirmation.\n"
                                    + "- A secret, credential, or private data placed in"
                                    + " model-visible context, logged with the prompt or"
                                    + " response, or sent to a provider it is not approved"
                                    + " for.\n"
                                    + "- No limit on output tokens, agent or tool-call"
                                    + " iterations, retries, input size, or per-user cost.\n"
                                    + "- A model response parsed without handling malformed,"
                                    + " truncated, or refused output.\n"),
                    new Checklist(
                            "Test changes",
                            TEST_PATH,
                            null,
                            "- A test deleted, skipped, or disabled (@Disabled, @Ignore,"
                                    + " skip, xit, t.Skip) while the behavior it covered still"
                                    + " exists, or a test focused (.only, fit, fdescribe) so the"
                                    + " rest of the suite stops running.\n"
                                    + "- An assertion removed or loosened (an exact value"
                                    + " replaced by a not-null, contains, or any() check), or an"
                                    + " expected value edited to match new output that"
                                    + " <pr_description> and <commits> do not describe as an"
                                    + " intended behavior change.\n"
                                    + "- A test that can no longer fail: no assertion, an"
                                    + " assertion on a mock's own stubbed value, an exception"
                                    + " caught and ignored, or an async result never awaited"
                                    + " or checked.\n"
                                    + "Report a removed or weakened test as type \"issue\","
                                    + " category \"tests\", and quote the removed or loosened"
                                    + " assertion in \"rationale\".\n"));

    private TriggeredChecklists() {}

    /**
     * The checklist section for the given changed files, or an empty string when none of them
     * triggers a checklist. Checklists appear in a fixed order so the prompt is deterministic.
     */
    static String sectionFor(Collection<InspectionManifest.FileTarget> files) {
        List<Checklist> triggered =
                CHECKLISTS.stream()
                        .filter(checklist -> files.stream().anyMatch(checklist::matches))
                        .toList();
        if (triggered.isEmpty()) return "";
        var section =
                new StringBuilder(
                        "\nChange-specific checks. During Pass B, also check the changed files"
                                + " of these kinds for:\n");
        for (Checklist checklist : triggered) {
            section.append("\n").append(checklist.title()).append(":\n").append(checklist.items());
        }
        return section.toString();
    }
}
