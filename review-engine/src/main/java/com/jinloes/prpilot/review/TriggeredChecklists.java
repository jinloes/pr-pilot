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
            Pattern.compile(
                    "(?i)(?:^|/)(?:sub_?)?prompts?/|(?:^|/)agents?/(?:definitions?|instructions?)/"
                            + "|\\.prompty?$|\\.prompt\\.[a-z0-9]+$");

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
                            + "|generate_?content\\b|messages\\.create\\b"
                            + "|tool_?calls?\\b|chatlanguagemodel|llms?\\b|llm(?-i:[A-Z_])"
                            + "|gpt-[0-9o]|claude-(?:[0-9]|opus|sonnet|haiku)|gemini-[0-9])"
                            + "|(?<![a-z0-9])(?:system_?prompt|sub_?prompts?"
                            + "|(?:agent|review)_?instructions?)(?![a-z0-9])"
                            + "|(?-i:\\b(?:System|Human|AI|Tool)Message\\b"
                            + "|\\bAgent(?:Action|Finish|Executor)\\b)"
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
                            PromptResources.load("checklists/ci-workflows.md")),
                    new Checklist(
                            "Dependency manifests and lockfiles",
                            DEPENDENCY_PATH,
                            null,
                            PromptResources.load("checklists/dependency-manifests.md")),
                    new Checklist(
                            "Container images",
                            CONTAINER_PATH,
                            null,
                            PromptResources.load("checklists/container-images.md")),
                    new Checklist(
                            "Schema migrations",
                            MIGRATION_PATH,
                            DDL_LINE,
                            PromptResources.load("checklists/schema-migrations.md")),
                    new Checklist(
                            "Protobuf schemas",
                            PROTO_PATH,
                            null,
                            PromptResources.load("checklists/protobuf-schemas.md")),
                    new Checklist(
                            "Protobuf tooling config",
                            PROTO_TOOLING_PATH,
                            null,
                            PromptResources.load("checklists/protobuf-tooling.md")),
                    new Checklist(
                            "Protobuf and gRPC code",
                            null,
                            PROTO_CODE_LINE,
                            GeneratedProtoCode::isGenerated,
                            PromptResources.load("checklists/protobuf-code.md")),
                    new Checklist(
                            "LLM integrations",
                            PROMPT_PATH,
                            LLM_LINE,
                            PromptResources.load("checklists/llm-integrations.md")),
                    new Checklist(
                            "Test changes",
                            TEST_PATH,
                            null,
                            PromptResources.load("checklists/test-changes.md")));

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
