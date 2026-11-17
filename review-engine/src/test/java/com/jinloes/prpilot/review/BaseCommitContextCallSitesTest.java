package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link BaseCommitContext} call-site search and generated-symbol expansion. */
class BaseCommitContextCallSitesTest extends BaseCommitContextTestSupport {

    @Nested
    class CallSites {
        @Test
        void listsBaseCommitCallersOutsideTheChangedFiles() throws Exception {
            write("src/Billing.java", "class Billing {\n  public int chargeCard(String a) {}\n}\n");
            write(
                    "src/Checkout.java",
                    "class Checkout {\n  int x = billing.chargeCard(acct);\n}\n");
            write("src/Other.java", "class Other { int chargeCardLimit = 1; }\n");
            String base = commit("base");
            write("src/Refunds.java", "class Refunds { void r() { chargeCard(x); } }\n");
            commit("head only caller");

            String sites =
                    context.resolve(
                                    repo,
                                    base,
                                    changedDeclaration(
                                            "src/Billing.java",
                                            "  public int chargeCard(String a) {"),
                                    List.of(),
                                    () -> {})
                            .callSites();

            assertThat(sites)
                    .isEqualTo(
                            "## chargeCard (declaration changed in src/Billing.java)\n"
                                    + "src/Checkout.java:2: int x = billing.chargeCard(acct);");
        }

        @Test
        void findsProtoFieldConsumersByTheirGeneratedNames() throws Exception {
            write(
                    "api/user.proto",
                    "syntax = \"proto3\";\nmessage User {\n  string display_title = 3;\n}\n");
            write("py/user.py", "print(user.display_title)\n");
            write(
                    "svc/user.go",
                    "package svc\n\nfunc Title(req *pb.User) string {\n"
                            + "\treturn req.GetDisplayTitle()\n}\n");
            write("web/user.ts", "export const title = (user: User) => user.displayTitle;\n");
            write("web/other.ts", "const display_titles = [];\n");
            String base = commit("base");

            String sites =
                    context.resolve(
                                    repo,
                                    base,
                                    changedDeclaration(
                                            "api/user.proto", "  string display_title = 3;"),
                                    List.of(),
                                    () -> {})
                            .callSites();

            assertThat(sites)
                    .isEqualTo(
                            "## display_title (declaration changed in api/user.proto;"
                                    + " matches include the names generated code gives it)\n"
                                    + "py/user.py:1: print(user.display_title)\n"
                                    + "svc/user.go:4: return req.GetDisplayTitle()\n"
                                    + "web/user.ts:1: export const title = (user: User) =>"
                                    + " user.displayTitle;");
        }

        @Test
        void skipsCallSitesWhenDisabledByProperty() throws Exception {
            write("src/Billing.java", "class Billing {\n  public int chargeCard(String a) {}\n}\n");
            write(
                    "src/Checkout.java",
                    "class Checkout {\n  int x = billing.chargeCard(acct);\n}\n");
            String base = commit("base");

            String previous = System.getProperty(BaseCommitContext.CALL_SITES_PROPERTY);
            System.setProperty(BaseCommitContext.CALL_SITES_PROPERTY, "false");
            try {
                BaseCommitContext.Result result =
                        context.resolve(
                                repo,
                                base,
                                changedDeclaration(
                                        "src/Billing.java", "  public int chargeCard(String a) {"),
                                List.of(),
                                () -> {});

                assertThat(result.callSites()).isEmpty();
                assertThat(result.fileHistory()).isNotEmpty();
            } finally {
                if (previous == null) {
                    System.clearProperty(BaseCommitContext.CALL_SITES_PROPERTY);
                } else {
                    System.setProperty(BaseCommitContext.CALL_SITES_PROPERTY, previous);
                }
            }
        }

        @Test
        void callSitesEnabledUnlessPropertyIsFalse() {
            String previous = System.getProperty(BaseCommitContext.CALL_SITES_PROPERTY);
            try {
                System.clearProperty(BaseCommitContext.CALL_SITES_PROPERTY);
                assertThat(BaseCommitContext.callSitesEnabled()).isTrue();
                System.setProperty(BaseCommitContext.CALL_SITES_PROPERTY, "true");
                assertThat(BaseCommitContext.callSitesEnabled()).isTrue();
                System.setProperty(BaseCommitContext.CALL_SITES_PROPERTY, "garbage");
                assertThat(BaseCommitContext.callSitesEnabled()).isTrue();
                System.setProperty(BaseCommitContext.CALL_SITES_PROPERTY, " FALSE ");
                assertThat(BaseCommitContext.callSitesEnabled()).isFalse();
            } finally {
                if (previous == null) {
                    System.clearProperty(BaseCommitContext.CALL_SITES_PROPERTY);
                } else {
                    System.setProperty(BaseCommitContext.CALL_SITES_PROPERTY, previous);
                }
            }
        }

        @Test
        void omitsSymbolsWithNoCallersOutsideTheChangedFiles() throws Exception {
            write("src/Billing.java", "class Billing {\n  public int chargeCard(String a) {}\n}\n");
            String base = commit("base");

            assertThat(
                            context.resolve(
                                            repo,
                                            base,
                                            changedDeclaration(
                                                    "src/Billing.java",
                                                    "  public int chargeCard(String a) {"),
                                            List.of(),
                                            () -> {})
                                    .callSites())
                    .isEmpty();
        }

        @Test
        void summarisesSymbolsWithTooManyReferences() throws Exception {
            write("lib.py", "def resolve_path(p):\n    return p\n");
            StringBuilder calls = new StringBuilder();
            for (int i = 0; i <= BaseCommitContext.MAX_LISTED_REFERENCES; i++) {
                calls.append("resolve_path(").append(i).append(")\n");
            }
            write("a.py", calls.toString());
            write("b.py", "resolve_path(1)\n");
            String base = commit("base");

            String sites =
                    context.resolve(
                                    repo,
                                    base,
                                    changedDeclaration("lib.py", "def resolve_path(p):"),
                                    List.of(),
                                    () -> {})
                            .callSites();

            assertThat(sites)
                    .isEqualTo(
                            "## resolve_path (declaration changed in lib.py)\n"
                                    + (BaseCommitContext.MAX_LISTED_REFERENCES + 2)
                                    + " references in 2 files outside the changed files;"
                                    + " too many to list");
        }

        @Test
        void capsSitesPerSymbolAndLineLength() throws Exception {
            write("lib.py", "def resolve_path(p):\n    return p\n");
            StringBuilder calls = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                calls.append("resolve_path(").append("x".repeat(200)).append(")\n");
            }
            write("a.py", calls.toString());
            String base = commit("base");

            String sites =
                    context.resolve(
                                    repo,
                                    base,
                                    changedDeclaration("lib.py", "def resolve_path(p):"),
                                    List.of(),
                                    () -> {})
                            .callSites();

            List<String> lines = sites.lines().toList();
            assertThat(lines).hasSize(1 + BaseCommitContext.MAX_CALL_SITES_PER_SYMBOL);
            assertThat(lines.get(1))
                    .startsWith("a.py:1: resolve_path(")
                    .endsWith("...")
                    .hasSize("a.py:1: ".length() + BaseCommitContext.MAX_CALL_SITE_LINE_CHARS + 3);
        }

        @Test
        void aFailingSearchLeavesGuidanceAndHistoryIntact() throws Exception {
            write("AGENTS.md", "rules");
            write("lib.py", "def resolve_path(p):\n    return p\n");
            write("a.py", "resolve_path(1)\n");
            String base = commit("base");
            BaseCommitContext failing =
                    new BaseCommitContext(
                            new BoundedProcessRunner(
                                    builder -> {
                                        if (builder.command().contains("grep")) {
                                            throw new IOException("git grep failed");
                                        }
                                        return builder.start();
                                    }));

            BaseCommitContext.Result result =
                    failing.resolve(
                            repo,
                            base,
                            changedDeclaration("lib.py", "def resolve_path(p):"),
                            List.of(),
                            () -> {});

            assertThat(result.guidelines()).isEqualTo("## AGENTS.md\nrules");
            assertThat(result.fileHistory()).contains("base");
            assertThat(result.callSites()).isEmpty();
        }
    }
}
