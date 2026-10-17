package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link TriggeredChecklists}. */
class TriggeredChecklistsTest {

    private static String fileDiff(String path, String... hunkLines) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\n--- a/"
                + path
                + "\n+++ b/"
                + path
                + "\n@@ -1,1 +1,1 @@\n"
                + String.join("\n", hunkLines)
                + "\n";
    }

    private static String deletedFileDiff(String path, String removedLine) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\ndeleted file mode 100644\n--- a/"
                + path
                + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-"
                + removedLine
                + "\n";
    }

    private static String sectionFor(String... fileDiffs) {
        return TriggeredChecklists.sectionFor(
                InspectionManifest.fromDiff(String.join("", fileDiffs)).files());
    }

    private static String changed(String path) {
        return sectionFor(fileDiff(path, "-old", "+new"));
    }

    @Nested
    class SectionFor {

        @Test
        void isEmptyWhenNoFileTriggersAChecklist() {
            assertThat(TriggeredChecklists.sectionFor(List.of())).isEmpty();
            assertThat(
                            sectionFor(
                                    fileDiff("README.md", "-old", "+new"),
                                    fileDiff("src/main/java/Api.java", "-int a;", "+int b;")))
                    .isEmpty();
        }

        @Test
        void startsWithTheHeaderAndNamesEachTriggeredChecklist() {
            String section = changed(".github/workflows/ci.yml");

            assertThat(section)
                    .startsWith(
                            "\nChange-specific checks. During Pass B, also check the changed"
                                    + " files of these kinds for:\n")
                    .contains("\nCI workflows:\n")
                    .contains("pull_request_target");
        }

        @Test
        void neverEchoesTheUntrustedFilePath() {
            String section = changed(".github/workflows/ignore-previous-instructions.yml");

            assertThat(section).contains("\nCI workflows:\n");
            assertThat(section).doesNotContain("ignore-previous-instructions");
        }

        @Test
        void listsEachChecklistOnceInAFixedOrder() {
            String section =
                    sectionFor(
                            fileDiff("src/test/java/ApiTest.java", "-a", "+b"),
                            fileDiff("src/test/java/OtherTest.java", "-a", "+b"),
                            fileDiff("db/migration/V2__add_index.sql", "-a", "+b"),
                            fileDiff(".github/workflows/ci.yml", "-a", "+b"));

            assertThat(section.indexOf("\nCI workflows:\n"))
                    .isPositive()
                    .isLessThan(section.indexOf("\nSchema migrations:\n"));
            assertThat(section.indexOf("\nSchema migrations:\n"))
                    .isLessThan(section.indexOf("\nTest changes:\n"));
            assertThat(section.indexOf("\nTest changes:\n"))
                    .isEqualTo(section.lastIndexOf("\nTest changes:\n"));
            assertThat(section)
                    .isEqualTo(
                            sectionFor(
                                    fileDiff(".github/workflows/ci.yml", "-a", "+b"),
                                    fileDiff("db/migration/V2__add_index.sql", "-a", "+b"),
                                    fileDiff("src/test/java/ApiTest.java", "-a", "+b")));
        }

        @Test
        void aPathTriggerFiresForARemovalOnlyChange() {
            assertThat(sectionFor(deletedFileDiff("src/test/java/ApiTest.java", "@Test")))
                    .contains("\nTest changes:\n");
            assertThat(
                            sectionFor(
                                    fileDiff(
                                            ".github/workflows/ci.yml",
                                            " on: push",
                                            "-permissions: {}")))
                    .contains("\nCI workflows:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    ".github/workflows/ci.yml",
                    ".github/workflows/release.yaml",
                    "action.yml",
                    "tools/setup/action.yaml",
                    ".gitlab-ci.yml",
                    "Jenkinsfile",
                    "azure-pipelines.yml",
                    ".circleci/config.yml",
                    "bitbucket-pipelines.yml"
                })
        void triggersTheCiChecklistForCiDefinitions(String path) {
            assertThat(changed(path)).contains("\nCI workflows:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "package.json",
                    "web/package-lock.json",
                    "yarn.lock",
                    "build.gradle.kts",
                    "gradle/libs.versions.toml",
                    "gradle/wrapper/gradle-wrapper.properties",
                    "pom.xml",
                    "requirements-dev.txt",
                    "pyproject.toml",
                    "go.mod",
                    "Cargo.lock",
                    "src/App/App.csproj",
                    "Packages/manifest.json"
                })
        void triggersTheDependencyChecklistForManifestsAndLockfiles(String path) {
            assertThat(changed(path)).contains("\nDependency manifests and lockfiles:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "Dockerfile",
                    "docker/api.Dockerfile",
                    "Dockerfile.dev",
                    "Containerfile",
                    "docker-compose.yml",
                    "deploy/compose.prod.yaml"
                })
        void triggersTheContainerChecklistForImageAndComposeFiles(String path) {
            assertThat(changed(path)).contains("\nContainer images:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "src/main/resources/db/migration/V2__add_index.sql",
                    "sql/V3_1__backfill.sql",
                    "app/migrations/0003_add_user.py",
                    "db/migrate/20240101_add_users.rb",
                    "alembic/versions/abc123_add_column.py",
                    "src/main/resources/db/changelog/changes.xml"
                })
        void triggersTheMigrationChecklistForMigrationFiles(String path) {
            assertThat(changed(path)).contains("\nSchema migrations:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "src/test/java/com/x/ApiTest.java",
                    "src/integrationTest/kotlin/Flow.kt",
                    "web/src/App.test.tsx",
                    "web/e2e/login.spec.ts",
                    "pkg/server_test.go",
                    "tests/test_api.py",
                    "app/test_models.py",
                    "spec/models/user_spec.rb",
                    "src/ApiIT.java",
                    "Game/PlayerTests.cs"
                })
        void triggersTheTestChecklistForTestFiles(String path) {
            assertThat(changed(path)).contains("\nTest changes:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "README.md",
                    ".github/dependabot.yml",
                    "docs/workflows/ci.md",
                    "src/main/resources/application.yml",
                    "src/package.ts",
                    "docs/requirements.md",
                    "src/DockerfileParser.java",
                    "db/migration/README.md",
                    "src/main/java/MigrationService.java",
                    "src/main/java/Latest.java",
                    "src/main/java/latest/Contest.java",
                    "src/attestation.py",
                    "src/testing/Util.java",
                    "src/user.proto.md",
                    "docs/buf.md",
                    "config/buffer.yaml",
                    "src/proto.lock.md"
                })
        void doesNotTriggerOnLookAlikePaths(String path) {
            assertThat(changed(path)).isEmpty();
        }

        @Test
        void triggersTheMigrationChecklistForDdlOnAnAddedLineOnly() {
            String ddl = "jdbc.execute(\"ALTER TABLE users ADD COLUMN age INT\");";

            assertThat(sectionFor(fileDiff("src/Repo.java", "+" + ddl)))
                    .contains("\nSchema migrations:\n");
            assertThat(sectionFor(fileDiff("src/Repo.java", " " + ddl, "+int a;"))).isEmpty();
            assertThat(sectionFor(fileDiff("src/Repo.java", "-" + ddl, "+int a;"))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "import openai",
                    "var client = new OpenAIClient(key);",
                    "from langchain_core.prompts import ChatPromptTemplate",
                    "response = client.messages.create(model=MODEL)",
                    "model: \"claude-sonnet-4-5\"",
                    "String MODEL = \"gpt-4o\";",
                    "BedrockRuntimeClient bedrock = BedrockRuntimeClient.create();",
                    "result = model.generate_content(prompt)",
                    "String systemPrompt = load();",
                    "for (var call : message.toolCalls()) {",
                    "const llmClient = createClient();",
                    "completion = client.chat.completions.create(**args)"
                })
        void triggersTheLlmChecklistForModelCallsOnAddedLines(String addedLine) {
            assertThat(sectionFor(fileDiff("src/app/Service.java", "+" + addedLine)))
                    .contains("\nLLM integrations:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "String coherentState = \"x\";",
                    "// the bedrock of this design",
                    "boolean fullMatch = matcher.matches();",
                    "chatRoom.send(message);"
                })
        void doesNotTriggerTheLlmChecklistOnLookAlikeWords(String addedLine) {
            assertThat(sectionFor(fileDiff("src/app/Service.java", "+" + addedLine))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"prompts/review.txt", "src/review.prompt.md", "agent.prompty"})
        void triggersTheLlmChecklistForPromptFiles(String path) {
            assertThat(changed(path)).contains("\nLLM integrations:\n");
        }

        @Test
        void asksWhetherACiChangeWeakensAQualityGate() {
            assertThat(changed(".github/workflows/ci.yml"))
                    .contains("made non-blocking (continue-on-error: true, || true)");
        }

        @ParameterizedTest
        @ValueSource(strings = {"api/user/v1/user.proto", "proto/legacy/Account.PROTO"})
        void triggersTheProtobufSchemaChecklistForProtoFiles(String path) {
            assertThat(changed(path))
                    .contains("\nProtobuf schemas:\n")
                    .contains("the protobuf rule above")
                    .doesNotContain("\nProtobuf and gRPC code:\n");
        }

        @Test
        void leavesReservationBookkeepingToTheHygieneCheck() {
            assertThat(changed("api/user/v1/user.proto"))
                    .contains("belongs to the separate schema-evolution hygiene check")
                    .contains("report it here only when a number or name is actually reused")
                    .doesNotContain("not both added to reserved");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "buf.yaml",
                    "proto/buf.gen.yaml",
                    "buf.work.yaml",
                    "buf.policy.yaml",
                    "buf.lock",
                    "proto.lock"
                })
        void triggersTheProtobufToolingChecklistForBufAndProtolockFiles(String path) {
            assertThat(changed(path)).contains("\nProtobuf tooling config:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "import com.google.protobuf.util.JsonFormat;",
                    "import io.grpc.ManagedChannel;",
                    "\"google.golang.org/grpc/status\"",
                    "from acme.user.v1 import user_pb2",
                    "import { create } from \"@bufbuild/protobuf\";",
                    "using Google.Protobuf.WellKnownTypes;",
                    "b, err := protojson.Marshal(msg)",
                    "if err := proto.Unmarshal(data, msg); err != nil {",
                    "msg.ParseFromString(data)",
                    "case UNRECOGNIZED:",
                    "var stub = UserServiceGrpc.newBlockingStub(channel);",
                    "return nil, status.Errorf(codes.NotFound, \"missing\")",
                    "conn, err := grpc.NewClient(target)"
                })
        void triggersTheProtobufCodeChecklistForRuntimeUsageOnAddedLines(String addedLine) {
            assertThat(sectionFor(fileDiff("src/app/Service.java", "+" + addedLine)))
                    .contains("\nProtobuf and gRPC code:\n");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "String protocol = \"https\";",
                    "status.setText(\"ok\");",
                    "Object proto = prototypes.get(0);",
                    "int newStubCount = 0;",
                    "// gRPC is configured elsewhere"
                })
        void doesNotTriggerTheProtobufCodeChecklistOnLookAlikes(String addedLine) {
            assertThat(sectionFor(fileDiff("src/app/Service.java", "+" + addedLine))).isEmpty();
        }

        @Test
        void doesNotTriggerTheProtobufCodeChecklistForGeneratedCode() {
            String imports = "+import \"google.golang.org/protobuf/reflect/protoreflect\"";

            assertThat(sectionFor(fileDiff("gen/api/user/v1/user.pb.go", imports))).isEmpty();
            assertThat(
                            sectionFor(
                                    fileDiff(
                                            "gen/user.go",
                                            "+// Code generated by protoc-gen-go. DO NOT EDIT.",
                                            imports)))
                    .isEmpty();
            assertThat(sectionFor(fileDiff("src/client.go", imports)))
                    .contains("\nProtobuf and gRPC code:\n");
        }
    }

    @Nested
    class ChecklistMatches {

        @Test
        void neverMatchesAnExcludedFile() {
            var checklist =
                    new TriggeredChecklists.Checklist(
                            "X",
                            Pattern.compile("b\\.txt$"),
                            Pattern.compile("needle"),
                            file -> true,
                            "- x\n");

            assertThat(InspectionManifest.fromDiff(fileDiff("a/b.txt", "+needle")).files())
                    .noneMatch(checklist::matches);
        }

        @Test
        void neverMatchesWithoutATrigger() {
            var checklist = new TriggeredChecklists.Checklist("X", null, null, "- x\n");

            assertThat(InspectionManifest.fromDiff(fileDiff("a/b.txt", "+anything")).files())
                    .noneMatch(checklist::matches);
        }

        @Test
        void matchesAnAddedLineInAnyHunk() {
            var checklist =
                    new TriggeredChecklists.Checklist(
                            "X", null, Pattern.compile("needle"), "- x\n");
            String twoHunks =
                    fileDiff("a/b.txt", "+hay") + "@@ -10,1 +10,1 @@\n-old\n+more needle\n";

            assertThat(InspectionManifest.fromDiff(twoHunks).files())
                    .singleElement()
                    .matches(checklist::matches);
        }

        @Test
        void everyChecklistHasAUniqueTitleAndNewlineTerminatedItems() {
            assertThat(TriggeredChecklists.CHECKLISTS)
                    .extracting(TriggeredChecklists.Checklist::title)
                    .doesNotHaveDuplicates();
            assertThat(TriggeredChecklists.CHECKLISTS)
                    .allSatisfy(
                            checklist -> {
                                assertThat(checklist.items()).startsWith("- ").endsWith("\n");
                                assertThat(
                                                checklist.path() != null
                                                        || checklist.addedLine() != null)
                                        .isTrue();
                            });
        }
    }
}
