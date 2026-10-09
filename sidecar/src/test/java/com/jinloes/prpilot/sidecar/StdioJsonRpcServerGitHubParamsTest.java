package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioJsonRpcServerGitHubParamsTest extends StdioJsonRpcServerTestBase {
    @Test
    void detectsARepoFromAGitConfig(@TempDir java.nio.file.Path tempDir) throws IOException {
        java.nio.file.Path gitDir = tempDir.resolve(".git");
        java.nio.file.Files.createDirectories(gitDir);
        java.nio.file.Files.writeString(
                gitDir.resolve("config"),
                "[remote \"origin\"]\n\turl = https://github.com/acme/widgets.git\n");

        com.fasterxml.jackson.databind.node.ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", "repo-1");
        request.put("method", "repo/detect");
        request.putObject("params").put("path", tempDir.toString());

        JsonNode response = server.handle(objectMapper.writeValueAsBytes(request));

        assertThat(response.path("id").asText()).isEqualTo("repo-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("found");
        assertThat(response.path("result").path("repository").path("owner").asText())
                .isEqualTo("acme");
        assertThat(response.path("result").path("repository").path("repo").asText())
                .isEqualTo("widgets");
    }

    @Test
    void rejectsInvalidRepoDetectParams() {
        JsonNode missingField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"repo/detect\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonTextField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"repo/detect\",\"params\":{\"path\":1}}"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingField.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonTextField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidGitHubBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"auth-1\",\"method\":\"github/checkAuth\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("auth-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("username").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidGitHubAuthParams() {
        JsonNode missingField =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"github/checkAuth\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"github/checkAuth\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"extra\":\"x\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingField.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void acceptsBoundedCommitIssueNumbersForLinkedIssueResolution() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"prs/getLinkedIssues\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\","
                                        + "\"owner\":\"acme\",\"repo\":\"widgets\",\"prBody\":\"\","
                                        + "\"commitIssueNumbers\":[7]}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("error").isMissingNode()).isTrue();
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsMalformedCommitIssueNumberArrays() {
        for (String commitIssueNumbers :
                java.util.List.of(
                        "null",
                        "\"7\"",
                        "[1,\"2\"]",
                        "[0]",
                        "[7,7]",
                        "[1,2,3,4]",
                        "[1000000000]",
                        "[2147483648]")) {
            JsonNode response =
                    server.handle(
                            ("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"prs/getLinkedIssues\","
                                            + "\"params\":{\"githubBaseUrl\":\"https://github.com\","
                                            + "\"owner\":\"acme\",\"repo\":\"widgets\",\"prBody\":\"\","
                                            + "\"commitIssueNumbers\":"
                                            + commitIssueNumbers
                                            + "}}")
                                    .getBytes(StandardCharsets.UTF_8));

            assertThat(response.path("error").path("code").asInt())
                    .as(commitIssueNumbers)
                    .isEqualTo(-32602);
        }

        JsonNode missing =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"prs/getLinkedIssues\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\","
                                        + "\"owner\":\"acme\",\"repo\":\"widgets\",\"prBody\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidPrListBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"list-1\",\"method\":\"prs/list\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("list-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("prs").isArray()).isTrue();
        assertThat(response.path("result").path("prs")).isEmpty();
    }

    @Test
    void rejectsInvalidPrListParams() {
        JsonNode missingBaseUrl =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"prs/list\",\"params\":{}}"
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonTextField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"prs/list\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"state\":true}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingBaseUrl.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonTextField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidPrDetailBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"detail-1\",\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":42}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("detail-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("detail").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidPrDetailParams() {
        JsonNode missingNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode nonIntegralNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"prs/getDetail\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":1.5}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingNumber.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(nonIntegralNumber.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"draft-1\",\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":42}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("draft-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("review").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidDraftReviewParams() {
        JsonNode missingNumber =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"prs/getDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\",\"repo\":\"widgets\",\"number\":1,\"extra\":\"x\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingNumber.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidSaveDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"save-1\",\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[]}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("save-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidSaveDraftReviewParams() {
        JsonNode missingLineComments =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\"}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode malformedComment =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[{\"file\":\"a.java\"}]}}")
                                .getBytes(StandardCharsets.UTF_8));
        JsonNode extraField =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"prs/saveDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"summary\":\"s\",\"verdict\":\"APPROVE\","
                                        + "\"lineComments\":[],\"extra\":true}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingLineComments.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(malformedComment.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(extraField.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void acceptsAndIgnoresReviewerSourcesOnSavedComments() throws IOException {
        ObjectNode comment = draftComment();
        comment.putArray("sources").add("claude-opus").add("gpt-5.5");

        JsonNode response = server.handle(saveDraftRequest("save-sources", comment));

        assertThat(response.has("error")).isFalse();
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void acceptsAStringSuggestedChangeOnSavedComments() throws IOException {
        ObjectNode comment = draftComment();
        comment.put("suggestedChange", "    return a;");

        JsonNode response = server.handle(saveDraftRequest("save-suggestion", comment));

        assertThat(response.has("error")).isFalse();
        assertThat(
                        new StdioJsonRpcServerSupport(objectMapper)
                                .parseComments(objectMapper.createArrayNode().add(comment)))
                .singleElement()
                .extracting(DraftReviewMutationService.CommentInput::suggestedChange)
                .isEqualTo("    return a;");
        assertThat(
                        new StdioJsonRpcServerSupport(objectMapper)
                                .parseComments(objectMapper.createArrayNode().add(draftComment())))
                .singleElement()
                .extracting(DraftReviewMutationService.CommentInput::suggestedChange)
                .isNull();
    }

    @Test
    void rejectsANonStringSuggestedChange() throws IOException {
        ObjectNode numeric = draftComment();
        numeric.put("suggestedChange", 3);
        ObjectNode nulled = draftComment();
        nulled.putNull("suggestedChange");

        for (ObjectNode comment : List.of(numeric, nulled)) {
            JsonNode response = server.handle(saveDraftRequest("save-bad-suggestion", comment));

            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        }
    }

    @Test
    void rejectsMalformedSourcesAndOtherUnknownCommentFields() throws IOException {
        ObjectNode nonArraySources = draftComment();
        nonArraySources.put("sources", "claude-opus");
        ObjectNode nonTextSource = draftComment();
        nonTextSource.putArray("sources").add(1);
        ObjectNode unknownField = draftComment();
        unknownField.put("corroborated", true);

        for (ObjectNode comment : List.of(nonArraySources, nonTextSource, unknownField)) {
            JsonNode response = server.handle(saveDraftRequest("save-bad", comment));

            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        }
    }

    private ObjectNode draftComment() {
        ObjectNode comment = objectMapper.createObjectNode();
        comment.put("file", "a.java");
        comment.put("line", 1);
        comment.put("type", "suggestion");
        comment.put("body", "Body.");
        return comment;
    }

    private byte[] saveDraftRequest(String id, ObjectNode comment) throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", "prs/saveDraftReview");
        ObjectNode params = request.putObject("params");
        params.put("githubBaseUrl", "http://github.com");
        params.put("owner", "acme");
        params.put("repo", "widgets");
        params.put("number", 42);
        params.put("summary", "s");
        params.put("verdict", "APPROVE");
        params.putArray("lineComments").add(comment);
        return objectMapper.writeValueAsBytes(request);
    }

    @Test
    void returnsAStructuredResultForAnInvalidSubmitReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"submit-1\",\"method\":\"prs/submitReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"reviewId\":\"7\",\"event\":\"APPROVE\",\"body\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("submit-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidSubmitReviewParams() {
        JsonNode missingReviewId =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"prs/submitReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1,\"event\":\"APPROVE\",\"body\":\"\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingReviewId.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidDeleteDraftReviewBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"delete-1\",\"method\":\"prs/deleteDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":42,\"reviewId\":\"7\"}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("delete-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
    }

    @Test
    void rejectsInvalidDeleteDraftReviewParams() {
        JsonNode missingReviewId =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"prs/deleteDraftReview\","
                                        + "\"params\":{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":1}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(missingReviewId.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void returnsAStructuredResultForAnInvalidIncrementalDiffBaseUrl() {
        JsonNode response =
                server.handle(
                        ("{\"jsonrpc\":\"2.0\",\"id\":\"inc-1\",\"method\":\"prs/getIncrementalDiff\","
                                        + "\"params\":{\"githubBaseUrl\":\"http://github.com\",\"owner\":\"acme\","
                                        + "\"repo\":\"widgets\",\"number\":7}}")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.path("id").asText()).isEqualTo("inc-1");
        assertThat(response.path("result").path("status").asText()).isEqualTo("invalid_base_url");
        assertThat(response.path("result").path("scope").isNull()).isTrue();
        assertThat(response.path("result").path("diff").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidIncrementalDiffParams() {
        String prefix =
                "{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"prs/getIncrementalDiff\",\"params\":";
        String[] invalid = {
            prefix + "{}}",
            prefix
                    + "{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                    + "\"repo\":\"widgets\",\"number\":\"7\"}}",
            prefix
                    + "{\"githubBaseUrl\":\"https://github.com\",\"owner\":\"acme\","
                    + "\"repo\":\"widgets\",\"number\":7,\"mode\":\"review\"}}",
            prefix + "[]}"
        };

        for (String body : invalid) {
            JsonNode response = server.handle(body.getBytes(StandardCharsets.UTF_8));
            assertThat(response.path("error").path("code").asInt()).as(body).isEqualTo(-32602);
        }
    }
}
