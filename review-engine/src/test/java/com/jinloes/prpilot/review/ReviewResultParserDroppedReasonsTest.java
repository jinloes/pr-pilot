package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link ReviewResultParser#parseDroppedReasons}. */
class ReviewResultParserDroppedReasonsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode review() {
        ObjectNode root = JSON.createObjectNode();
        root.put("summary", "s");
        root.putArray("lineComments");
        return root;
    }

    private static void addDropped(ArrayNode dropped, String file, Object line, String reason) {
        ObjectNode entry = dropped.addObject();
        if (file != null) entry.put("file", file);
        if (line instanceof Integer number) entry.put("line", number);
        if (line instanceof String text) entry.put("line", text);
        if (reason != null) entry.put("reason", reason);
    }

    @Nested
    class ParseDroppedReasons {
        @Test
        void keysReasonsByFileAndLineAndNormalizesWhitespace() throws Exception {
            ObjectNode root = review();
            ArrayNode dropped = root.putArray("droppedFindings");
            addDropped(dropped, "src/A.java", 4, "The load\n  already\tsucceeded.");
            addDropped(dropped, "src/B.java", 9, "Duplicates A.java:4.");

            Map<String, String> reasons =
                    ReviewResultParser.parseDroppedReasons(
                            "```json\n" + JSON.writeValueAsString(root) + "\n```");

            assertThat(reasons)
                    .containsExactly(
                            Map.entry("src/A.java:4", "The load already succeeded."),
                            Map.entry("src/B.java:9", "Duplicates A.java:4."));
        }

        @Test
        void skipsIncompleteEntriesAndKeepsTheFirstReasonPerLocation() throws Exception {
            ObjectNode root = review();
            ArrayNode dropped = root.putArray("droppedFindings");
            addDropped(dropped, null, 1, "No file.");
            addDropped(dropped, "src/A.java", null, "No line.");
            addDropped(dropped, "src/A.java", "two", "Line is not a number.");
            addDropped(dropped, "src/A.java", 3, "  ");
            addDropped(dropped, "src/A.java", 5, "First.");
            addDropped(dropped, "src/A.java", 5, "Second.");

            assertThat(ReviewResultParser.parseDroppedReasons(JSON.writeValueAsString(root)))
                    .containsExactly(Map.entry("src/A.java:5", "First."));
        }

        @Test
        void abbreviatesLongReasons() throws Exception {
            ObjectNode root = review();
            addDropped(root.putArray("droppedFindings"), "src/A.java", 1, "x".repeat(500));

            assertThat(ReviewResultParser.parseDroppedReasons(JSON.writeValueAsString(root)))
                    .hasEntrySatisfying(
                            "src/A.java:1",
                            reason -> assertThat(reason).hasSize(300).endsWith("..."));
        }

        @Test
        void isEmptyWhenTheFieldIsMissingOrNotAnArray() throws Exception {
            ObjectNode root = review();
            assertThat(ReviewResultParser.parseDroppedReasons(JSON.writeValueAsString(root)))
                    .isEmpty();

            root.put("droppedFindings", "none");
            assertThat(ReviewResultParser.parseDroppedReasons(JSON.writeValueAsString(root)))
                    .isEmpty();
        }

        @Test
        void isEmptyForNullOrMalformedOutput() {
            assertThat(ReviewResultParser.parseDroppedReasons(null)).isEmpty();
            assertThat(ReviewResultParser.parseDroppedReasons("not json")).isEmpty();
            assertThat(ReviewResultParser.parseDroppedReasons("[1, 2]")).isEmpty();
        }
    }
}
