package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.jinloes.prpilot.review.ChangedLogStatements.Statement;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChangedLogStatementsTest {

    private static String file(String path, String... hunkLines) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\n--- a/"
                + path
                + "\n+++ b/"
                + path
                + "\n"
                + String.join("\n", hunkLines)
                + "\n";
    }

    private static List<Statement> extract(String diff) {
        return ChangedLogStatements.extract(InspectionManifest.fromDiff(diff));
    }

    @Nested
    class Extract {
        @Test
        void listsAnAddedSingleLineCall() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -10,2 +10,3 @@",
                            " int x = 1;",
                            "+logger.error(\"failed in phase {}\", phase, e);",
                            " return x;");

            assertThat(extract(diff))
                    .containsExactly(
                            new Statement(
                                    "src/A.java",
                                    11,
                                    "error",
                                    "logger.error(\"failed in phase {}\", phase, e);"));
        }

        @Test
        void anchorsAMultiLineCallWithAnUnchangedOpeningLineOnItsFirstAddedLine() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -40,5 +40,6 @@",
                            "     logger.info(",
                            "-        \"finished with status {}\",",
                            "+        \"finished with status {} (reconcile: {})\",",
                            "         item.getStatus(),",
                            "-        item.getPhase());",
                            "+        item.getPhase(),",
                            "+        outcome.reconcile());",
                            "     return item;");

            assertThat(extract(diff))
                    .containsExactly(
                            new Statement(
                                    "src/A.java",
                                    41,
                                    "info",
                                    "logger.info( \"finished with status {} (reconcile: {})\","
                                            + " item.getStatus(), item.getPhase(),"
                                            + " outcome.reconcile());"));
        }

        @Test
        void skipsCallsOnUnchangedLinesOnly() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -1,3 +1,4 @@",
                            " logger.info(\"unchanged\");",
                            "+int y = 2;",
                            " logger.warn(",
                            "     \"also unchanged\");");

            assertThat(extract(diff)).isEmpty();
        }

        @Test
        void recognizesLoggerReceiversAndLevelsAcrossLanguages() {
            String diff =
                    file(
                            "src/a.py",
                            "@@ -1,0 +1,7 @@",
                            "+LOG.warn(\"a\");",
                            "+this._logger.error(\"b\");",
                            "+logging.info(\"c\")",
                            "+console.error(\"d\");",
                            "+logger.debug { \"e\" }",
                            "+self.logger.exception(\"f\")",
                            "+log.log(Level.INFO, \"g\");");

            assertThat(extract(diff))
                    .extracting(Statement::line, Statement::level)
                    .containsExactly(
                            tuple(1, "warn"),
                            tuple(2, "error"),
                            tuple(3, "info"),
                            tuple(4, "error"),
                            tuple(5, "debug"),
                            tuple(6, "exception"),
                            tuple(7, "log"));
        }

        @Test
        void ignoresReceiversThatOnlyContainLogAndNonLoggingMethods() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -1,0 +1,4 @@",
                            "+catalog.info(\"a\");",
                            "+blog.error(\"b\");",
                            "+if (logger.isDebugEnabled()) {",
                            "+loggerFactory.getLogger(A.class);");

            assertThat(extract(diff)).isEmpty();
        }

        @Test
        void skipsCommentedOutCalls() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -1,0 +1,3 @@",
                            "+// logger.info(\"old\");",
                            "+# logging.info(\"old\")",
                            "+ * logger.warn(\"doc\")");

            assertThat(extract(diff)).isEmpty();
        }

        @Test
        void keepsReadingPastBracketsInsideStringLiterals() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -1,0 +1,3 @@",
                            "+logger.warn(\"closing ) and {} inside\",",
                            "+    id);",
                            "+int unrelated = 1;");

            assertThat(extract(diff))
                    .singleElement()
                    .extracting(Statement::text)
                    .isEqualTo("logger.warn(\"closing ) and {} inside\", id);");
        }

        @Test
        void skipsRemovedLinesWhenNumberingNewLines() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -5,3 +5,2 @@",
                            "-logger.info(\"removed\");",
                            "-int gone = 0;",
                            " int kept = 1;",
                            "+logger.info(\"added\");");

            assertThat(extract(diff)).extracting(Statement::line).containsExactly(6);
        }

        @Test
        void skipsTestFiles() {
            String diff =
                    file(
                            "src/test/java/ATest.java",
                            "@@ -1,0 +1,1 @@",
                            "+logger.info(\"in a test\");");

            assertThat(extract(diff)).isEmpty();
        }

        @Test
        void stopsAStatementThatNeverClosesAtTheLineLimit() {
            String[] lines = new String[ChangedLogStatements.MAX_STATEMENT_LINES + 2];
            lines[0] = "@@ -1,0 +1," + (lines.length - 1) + " @@";
            lines[1] = "+logger.info(";
            for (int i = 2; i < lines.length; i++) lines[i] = "+    arg" + i + ",";

            Statement statement = extract(file("src/A.java", lines)).get(0);

            assertThat(statement.text()).contains("arg" + ChangedLogStatements.MAX_STATEMENT_LINES);
            assertThat(statement.text())
                    .doesNotContain("arg" + (ChangedLogStatements.MAX_STATEMENT_LINES + 1));
        }

        @Test
        void abbreviatesLongStatements() {
            String diff =
                    file(
                            "src/A.java",
                            "@@ -1,0 +1,1 @@",
                            "+logger.info(\"" + "x".repeat(500) + "\");");

            assertThat(extract(diff).get(0).text())
                    .hasSize(ChangedLogStatements.MAX_TEXT_CHARS)
                    .endsWith("...");
        }

        @Test
        void capsTheInventory() {
            int count = ChangedLogStatements.MAX_STATEMENTS + 5;
            String added =
                    IntStream.range(0, count)
                            .mapToObj(i -> "+logger.info(\"" + i + "\");")
                            .collect(Collectors.joining("\n"));
            String diff = file("src/A.java", "@@ -1,0 +1," + count + " @@", added);

            assertThat(extract(diff)).hasSize(ChangedLogStatements.MAX_STATEMENTS);
        }

        @Test
        void returnsNothingWithoutAManifest() {
            assertThat(ChangedLogStatements.extract(null)).isEmpty();
        }
    }
}
