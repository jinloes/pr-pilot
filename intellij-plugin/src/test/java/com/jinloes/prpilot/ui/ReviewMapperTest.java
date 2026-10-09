package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsAndSerializesAllLineCommentFields() throws Exception {
        LineComment comment = new LineComment("src/Main.kt", 42, "issue", "Null dereference");
        comment.setSeverity("blocker");
        comment.setCategory("correctness");
        comment.setConfidence("high");
        comment.setRationale("The value is nullable on this path.");
        comment.setSources(List.of("claude-opus", "gpt-5.5"));
        ReviewResult result = new ReviewResult("Summary", "REQUEST_CHANGES", List.of(comment));

        ReviewResultDto dto = ReviewMapper.INSTANCE.toDto(result);
        JsonNode json = objectMapper.valueToTree(dto);

        assertThat(json.path("summary").asText()).isEqualTo("Summary");
        assertThat(json.path("verdict").asText()).isEqualTo("REQUEST_CHANGES");
        assertThat(json.path("lineComments")).hasSize(1);
        JsonNode commentJson = json.path("lineComments").get(0);
        assertThat(commentJson.path("file").asText()).isEqualTo("src/Main.kt");
        assertThat(commentJson.path("line").asInt()).isEqualTo(42);
        assertThat(commentJson.path("type").asText()).isEqualTo("issue");
        assertThat(commentJson.path("body").asText()).isEqualTo("Null dereference");
        assertThat(commentJson.path("severity").asText()).isEqualTo("blocker");
        assertThat(commentJson.path("category").asText()).isEqualTo("correctness");
        assertThat(commentJson.path("confidence").asText()).isEqualTo("high");
        assertThat(commentJson.path("rationale").asText())
                .isEqualTo("The value is nullable on this path.");
        assertThat(commentJson.path("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("claude-opus", "gpt-5.5");
    }

    @Test
    void omitsAbsentOptionalLineCommentFields() {
        LineComment comment = new LineComment("src/Main.kt", 7, "note", "Legacy comment");

        JsonNode json = objectMapper.valueToTree(ReviewMapper.INSTANCE.toDto(comment));

        assertThat(json.path("file").asText()).isEqualTo("src/Main.kt");
        assertThat(json.has("severity")).isFalse();
        assertThat(json.has("category")).isFalse();
        assertThat(json.has("confidence")).isFalse();
        assertThat(json.has("rationale")).isFalse();
        assertThat(json.has("sources")).isFalse();
    }
}
