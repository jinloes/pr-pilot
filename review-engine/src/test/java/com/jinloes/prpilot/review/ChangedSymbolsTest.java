package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.review.ChangedSymbols.Symbol;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChangedSymbolsTest {

    private static String fileDiff(String path, String body) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\n--- a/"
                + path
                + "\n+++ b/"
                + path
                + "\n"
                + body;
    }

    private static List<Symbol> extract(String... fileDiffs) {
        return ChangedSymbols.extract(InspectionManifest.fromDiff(String.join("", fileDiffs)));
    }

    @Nested
    class Extract {
        @Test
        void removedDeclarationIsASignatureChange() {
            List<Symbol> symbols =
                    extract(
                            fileDiff(
                                    "src/Billing.java",
                                    "@@ -10,2 +10,2 @@ class Billing {\n"
                                            + "-    public int charge(String account) {\n"
                                            + "+    public int charge(String account, int cents) {\n"
                                            + "         return 0;\n"));

            assertThat(symbols)
                    .containsExactly(
                            new Symbol("charge", "src/Billing.java", true),
                            new Symbol("Billing", "src/Billing.java", false));
        }

        @Test
        void hunkHeaderContextIsABodyChange() {
            List<Symbol> symbols =
                    extract(
                            fileDiff(
                                    "pkg/rates.py",
                                    "@@ -5,3 +5,3 @@ def apply_discount(order, pct):\n"
                                            + "-    total = order.total\n"
                                            + "+    total = order.subtotal\n"));

            assertThat(symbols)
                    .containsExactly(new Symbol("apply_discount", "pkg/rates.py", false));
        }

        @Test
        void declarationsOnlyAddedAreSkipped() {
            List<Symbol> symbols =
                    extract(
                            fileDiff(
                                    "src/util.ts",
                                    "@@ -1,0 +1,3 @@\n"
                                            + "+export function formatAmount(n) {\n"
                                            + "+  return n;\n"
                                            + "+}\n"));

            assertThat(symbols).isEmpty();
        }

        @Test
        void signatureChangeWinsOverBodyChangeForTheSameName() {
            List<Symbol> symbols =
                    extract(
                            fileDiff(
                                    "a.go",
                                    "@@ -1,2 +1,2 @@ func (s *Server) handleRequest(w http.ResponseWriter) {\n"
                                            + "-\tx := 1\n"
                                            + "+\tx := 2\n"),
                            fileDiff(
                                    "b.go",
                                    "@@ -3,1 +3,1 @@\n"
                                            + "-func (s *Server) handleRequest(w http.ResponseWriter) {\n"
                                            + "+func (s *Server) handleRequest(w http.ResponseWriter, r int) {\n"));

            assertThat(symbols).containsExactly(new Symbol("handleRequest", "b.go", true));
        }

        @Test
        void testFilesAreSkipped() {
            List<Symbol> symbols =
                    extract(
                            fileDiff(
                                    "src/test/java/BillingTest.java",
                                    "@@ -1,1 +1,1 @@\n-    public void chargesAccount() {\n+x\n"),
                            fileDiff(
                                    "web/cart.spec.ts",
                                    "@@ -1,1 +1,1 @@\n-function renderCart() {\n+x\n"));

            assertThat(symbols).isEmpty();
        }

        @Test
        void capsTheNumberOfSymbols() {
            StringBuilder body = new StringBuilder("@@ -1,20 +1,0 @@\n");
            for (int i = 0; i < 20; i++) {
                body.append("-def handler_").append(i).append("(x):\n");
            }

            assertThat(extract(fileDiff("h.py", body.toString())))
                    .hasSize(ChangedSymbols.MAX_SYMBOLS)
                    .first()
                    .isEqualTo(new Symbol("handler_0", "h.py", true));
        }

        @Test
        void nullManifestYieldsNothing() {
            assertThat(ChangedSymbols.extract(null)).isEmpty();
        }
    }

    @Nested
    class DeclaredName {
        @Test
        void recognisesCommonDeclarationForms() {
            assertThat(ChangedSymbols.declaredName("def parse_order(raw):"))
                    .isEqualTo("parse_order");
            assertThat(ChangedSymbols.declaredName("fun String.toSlug(): String"))
                    .isEqualTo("toSlug");
            assertThat(ChangedSymbols.declaredName("pub fn load_config(path: &str) {"))
                    .isEqualTo("load_config");
            assertThat(ChangedSymbols.declaredName("public final class Ledger {"))
                    .isEqualTo("Ledger");
            assertThat(ChangedSymbols.declaredName("export interface CartItem {"))
                    .isEqualTo("CartItem");
            assertThat(
                            ChangedSymbols.declaredName(
                                    "  @Override public List<String> listNames(int max) {"))
                    .isEqualTo("listNames");
            assertThat(
                            ChangedSymbols.declaredName(
                                    "    private static Map<String, Integer> countBy(List<X> xs) {"))
                    .isEqualTo("countBy");
            assertThat(ChangedSymbols.declaredName("const fetchUser = async (id) => {"))
                    .isEqualTo("fetchUser");
            assertThat(ChangedSymbols.declaredName("export const pick = item => item.id;"))
                    .isEqualTo("pick");
        }

        @Test
        void ignoresNonDeclarations() {
            assertThat(ChangedSymbols.declaredName("    return charge(account);")).isNull();
            assertThat(ChangedSymbols.declaredName("    private final int count = 0;")).isNull();
            assertThat(ChangedSymbols.declaredName("const limit = 10;")).isNull();
            assertThat(ChangedSymbols.declaredName("// the class that owns this")).isNull();
            assertThat(ChangedSymbols.declaredName("  * Returns the class name")).isNull();
            assertThat(ChangedSymbols.declaredName("")).isNull();
        }

        @Test
        void ignoresStoplistedAndShortNames() {
            assertThat(ChangedSymbols.declaredName("public String toString() {")).isNull();
            assertThat(ChangedSymbols.declaredName("def __init__(self):")).isNull();
            assertThat(ChangedSymbols.declaredName("func Of(x int) {")).isNull();
            assertThat(ChangedSymbols.declaredName("def ab(x):")).isNull();
        }
    }

    @Nested
    class IsTestPath {
        @Test
        void recognisesTestLayouts() {
            assertThat(ChangedSymbols.isTestPath("src/test/java/a/FooTest.java")).isTrue();
            assertThat(ChangedSymbols.isTestPath("tests/test_rates.py")).isTrue();
            assertThat(ChangedSymbols.isTestPath("pkg/server_test.go")).isTrue();
            assertThat(ChangedSymbols.isTestPath("web/cart.test.tsx")).isTrue();
            assertThat(ChangedSymbols.isTestPath("app/models/UserSpec.scala")).isTrue();
        }

        @Test
        void leavesProductionPathsAlone() {
            assertThat(ChangedSymbols.isTestPath("src/main/java/Contest.java")).isFalse();
            assertThat(ChangedSymbols.isTestPath("lib/latest.py")).isFalse();
            assertThat(ChangedSymbols.isTestPath("src/testing/Harness.java")).isFalse();
        }
    }
}
