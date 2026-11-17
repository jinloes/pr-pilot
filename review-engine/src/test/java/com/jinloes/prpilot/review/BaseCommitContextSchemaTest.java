package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BaseCommitContext} protobuf schema delta and generated-code staleness checks.
 */
class BaseCommitContextSchemaTest extends BaseCommitContextTestSupport {

    @Nested
    class SchemaChanges {
        private static final String USER_PROTO = "api/user.proto";
        private static final String USER =
                """
                syntax = "proto3";
                package api.v1;
                message User {
                  string name = 1;
                  int32 age = 2;
                }
                """;
        private static final String WIDENED_AGE = USER.replace("int32 age", "int64 age");
        private static final String WIDEN_AGE =
                """
                diff --git a/api/user.proto b/api/user.proto
                --- a/api/user.proto
                +++ b/api/user.proto
                @@ -3,4 +3,4 @@
                 message User {
                   string name = 1;
                -  int32 age = 2;
                +  int64 age = 2;
                 }
                """;
        private static final String AGE_WIDENED =
                "- api/user.proto:5 FIELD_WIRE_COMPATIBLE_TYPE [json, source] User.age";

        private String schemaChanges(
                BaseCommitContext resolver,
                String baseSha,
                String diffBaseSha,
                String diff,
                Set<String> omittedPaths)
                throws InterruptedException {
            return resolve(resolver, baseSha, diffBaseSha, diff, omittedPaths).protoSchemaChanges();
        }

        private BaseCommitContext.Result resolve(
                BaseCommitContext resolver,
                String baseSha,
                String diffBaseSha,
                String diff,
                Set<String> omittedPaths)
                throws InterruptedException {
            return resolver.resolve(
                    repo,
                    new BaseCommitContext.Scope(
                            baseSha,
                            diffBaseSha,
                            InspectionManifest.fromDiff(diff),
                            omittedPaths,
                            List.of()),
                    () -> {});
        }

        private static BaseCommitContext failingWhen(Predicate<List<String>> fails) {
            return new BaseCommitContext(
                    new BoundedProcessRunner(
                            builder -> {
                                if (fails.test(builder.command())) {
                                    throw new IOException("git failed");
                                }
                                return builder.start();
                            }));
        }

        @Test
        void comparesAPullRequestDiffWithTheMergeBase() throws Exception {
            write(USER_PROTO, USER);
            commit("base");
            git("checkout", "-q", "-b", "feature");
            write(USER_PROTO, WIDENED_AGE);
            commit("widen age");
            git("checkout", "-q", "main");
            write(
                    USER_PROTO,
                    USER.replace("message User {\n", "message User {\n  bool active = 3;\n"));
            String movedBase = commit("main moves on");
            git("checkout", "-q", "feature");

            assertThat(schemaChanges(context, movedBase, null, WIDEN_AGE, Set.of()))
                    .startsWith(AGE_WIDENED)
                    .doesNotContain("unparsed");
        }

        @Test
        void comparesAnIncrementalDiffWithItsBaseline() throws Exception {
            write(USER_PROTO, USER);
            String base = commit("base");
            write(USER_PROTO, WIDENED_AGE);
            String reviewed = commit("widen age");
            write(USER_PROTO, WIDENED_AGE.replace("string name", "string full_name"));
            commit("rename name");
            String sinceReviewed =
                    """
                    diff --git a/api/user.proto b/api/user.proto
                    --- a/api/user.proto
                    +++ b/api/user.proto
                    @@ -3,4 +3,4 @@
                     message User {
                    -  string name = 1;
                    +  string full_name = 1;
                       int64 age = 2;
                     }
                    """;

            assertThat(schemaChanges(context, base, reviewed, sinceReviewed, Set.of()))
                    .contains("- api/user.proto:4 FIELD_SAME_NAME")
                    .doesNotContain("FIELD_WIRE_COMPATIBLE_TYPE")
                    .doesNotContain("unparsed");
        }

        @Test
        void fallsBackToTheMergeBaseWhenTheBaselineIsUnavailable() throws Exception {
            write(USER_PROTO, USER);
            String base = commit("base");
            write(USER_PROTO, WIDENED_AGE);
            commit("widen age");

            assertThat(schemaChanges(context, base, "e".repeat(40), WIDEN_AGE, Set.of()))
                    .startsWith(AGE_WIDENED);
        }

        @Test
        void reportsASchemaMissingFromTheOldSideAsUnparsed() throws Exception {
            write("README.md", "readme");
            String base = commit("base");

            assertThat(
                            schemaChanges(
                                    context,
                                    base,
                                    null,
                                    WIDEN_AGE.replace(USER_PROTO, "api/ghost.proto"),
                                    null))
                    .isEqualTo(
                            "- api/ghost.proto unparsed (base version unavailable or larger than"
                                    + " 64 KB)");
        }

        @Test
        void listsCommittedGeneratedCodeTheDiffLeavesAsItWas() throws Exception {
            write(USER_PROTO, USER);
            write(
                    "gen/user.pb.go",
                    "// Code generated by protoc-gen-go. DO NOT EDIT.\n"
                            + "// source: api/user.proto\n\npackage api\n");
            write(
                    "py/user_pb2.py",
                    "# Generated by the protocol buffer compiler.  DO NOT EDIT!\n"
                            + "# source: api/user.proto\n");
            write(
                    "ts/user_pb.ts",
                    "// @generated from file api/user.proto (package api.v1, syntax proto3)\n");
            write("gen/account.pb.go", "// source: api/account.proto\n");
            write("deploy/values.yaml", "source: api/user.proto\n");
            write("third_party/gen/user.pb.go", "// source: api/user.proto\n");
            write("gen/user_grpc.pb.go", "// source: api/user.proto\n");
            write("gen/user.pb.gw.go", "// source: api/user.proto\n");
            String base = commit("base");
            write(USER_PROTO, WIDENED_AGE);
            commit("widen age");
            String diff =
                    WIDEN_AGE
                            + "diff --git a/gen/user_grpc.pb.go b/gen/user_grpc.pb.go\n"
                            + "--- a/gen/user_grpc.pb.go\n"
                            + "+++ b/gen/user_grpc.pb.go\n"
                            + "@@ -1,1 +1,1 @@\n-old\n+new\n";

            assertThat(schemaChanges(context, base, null, diff, Set.of("gen/user.pb.gw.go")))
                    .startsWith(AGE_WIDENED)
                    .endsWith(
                            "\n- api/user.proto:5 GENERATED_CODE_NOT_UPDATED [generated] committed"
                                    + " generated files not in this diff: gen/user.pb.go,"
                                    + " py/user_pb2.py, ts/user_pb.ts");
        }

        @Test
        void aFailingGeneratedCodeSearchKeepsTheDeltaAndOtherContext() throws Exception {
            write("AGENTS.md", "rules");
            write(USER_PROTO, USER);
            write("gen/user.pb.go", "// source: api/user.proto\n");
            String base = commit("base");
            write(USER_PROTO, WIDENED_AGE);
            commit("widen age");

            BaseCommitContext.Result result =
                    resolve(
                            failingWhen(command -> command.contains("grep")),
                            base,
                            null,
                            WIDEN_AGE,
                            Set.of());

            assertThat(result.guidelines()).isEqualTo("## AGENTS.md\nrules");
            assertThat(result.fileHistory()).contains("base");
            assertThat(result.protoSchemaChanges())
                    .startsWith(AGE_WIDENED)
                    .doesNotContain("GENERATED_CODE_NOT_UPDATED");
        }

        @Test
        void anUnreadableSchemaIsUnparsedWithoutLosingOtherContext() throws Exception {
            write("AGENTS.md", "rules");
            write(USER_PROTO, USER);
            String base = commit("base");
            write(USER_PROTO, WIDENED_AGE);
            commit("widen age");

            BaseCommitContext.Result result =
                    resolve(
                            failingWhen(
                                    command ->
                                            command.contains("ls-tree")
                                                    && command.contains(USER_PROTO)),
                            base,
                            null,
                            WIDEN_AGE,
                            Set.of());

            assertThat(result.guidelines()).isEqualTo("## AGENTS.md\nrules");
            assertThat(result.protoSchemaChanges())
                    .isEqualTo(
                            "- api/user.proto unparsed (base version unavailable or larger than"
                                    + " 64 KB)");
        }

        @Test
        void runsNoSchemaCommandsWhenNoSchemaChanged() throws Exception {
            write("src/App.java", "class App {}\n");
            String base = commit("base");
            List<List<String>> commands = new CopyOnWriteArrayList<>();
            BaseCommitContext recording =
                    new BaseCommitContext(
                            new BoundedProcessRunner(
                                    builder -> {
                                        commands.add(List.copyOf(builder.command()));
                                        return builder.start();
                                    }));

            BaseCommitContext.Result result =
                    recording.resolve(
                            repo,
                            new BaseCommitContext.Scope(
                                    base, "c".repeat(40), manifest("src/App.java"), null, null),
                            () -> {});

            assertThat(result.fileHistory()).contains("base");
            assertThat(result.protoSchemaChanges()).isEmpty();
            assertThat(commands)
                    .isNotEmpty()
                    .noneMatch(command -> command.contains("merge-base"))
                    .noneMatch(command -> command.contains("c".repeat(40)));
        }
    }
}
