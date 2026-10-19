package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.LineComment;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FindingSimilarityTest {
    static final String FIRST_WORDING =
            "`toMaybeCommerceContract` is another private copy of `error.getGrpcCode() =="
                    + " Code.NOT_FOUND ? Either.right(Optional.empty()) : Either.left(error)` on"
                    + " top of `entityOrError`. The same logic already appears in"
                    + " `UCCustomerContactsClient.getContact` and"
                    + " `UCBillingAccountClient.lookupBillingAccountUrn`. Add a sibling of"
                    + " `AbstractUCClient.entityOrError`, such as"
                    + " `entityOrEmptyIfNotFound(ProtoAccessor<P>)` returning `Either<UniversalClientError,"
                    + " Optional<P>>`, and call it here and in those clients.";
    static final String SECOND_WORDING =
            "This is another private copy of `error.getGrpcCode() == Code.NOT_FOUND ?"
                    + " Either.right(Optional.empty()) : Either.left(error)` layered on"
                    + " `entityOrError`. Add a sibling helper to `AbstractUCClient.entityOrError`,"
                    + " such as `entityOrEmptyIfNotFound(ProtoAccessor<P>)` returning"
                    + " `Either<UniversalClientError, Optional<P>>`, and use it here and in the"
                    + " existing copies.";

    static LineComment comment(int line, String body) {
        return comment("A.java", line, "suggestion", "maintainability", body);
    }

    static LineComment comment(String file, int line, String type, String category, String body) {
        LineComment comment = new LineComment(file, line, type, body);
        comment.setSeverity("minor");
        comment.setCategory(category);
        comment.setConfidence("medium");
        return comment;
    }

    @Nested
    class Duplicates {
        @Test
        void matchesTwoWordingsThatNameTheSameIdentifiers() {
            assertThat(
                            FindingSimilarity.duplicates(
                                    comment(237, FIRST_WORDING), comment(241, SECOND_WORDING)))
                    .isTrue();
        }

        @Test
        void matchesIdenticalBodiesIgnoringCaseAndWhitespace() {
            assertThat(
                            FindingSimilarity.duplicates(
                                    comment(1, "Close the  stream."),
                                    comment(5, "close the stream.")))
                    .isTrue();
        }

        @Test
        void matchesProseRewordingsWithMostlySharedWords() {
            assertThat(
                            FindingSimilarity.duplicates(
                                    comment(1, "The retry loop never sleeps between attempts."),
                                    comment(3, "Retry loop never sleeps between the attempts")))
                    .isTrue();
        }

        @Test
        void rejectsCommentsBeyondTheLineWindow() {
            assertThat(
                            FindingSimilarity.duplicates(
                                    comment(1, FIRST_WORDING),
                                    comment(2 + FindingSimilarity.NEAR_LINES, SECOND_WORDING)))
                    .isFalse();
        }

        @Test
        void rejectsDifferentFileTypeOrCategory() {
            LineComment base = comment(1, FIRST_WORDING);

            assertThat(
                            FindingSimilarity.duplicates(
                                    base,
                                    comment(
                                            "B.java",
                                            1,
                                            "suggestion",
                                            "maintainability",
                                            FIRST_WORDING)))
                    .isFalse();
            assertThat(
                            FindingSimilarity.duplicates(
                                    base,
                                    comment(
                                            "A.java",
                                            1,
                                            "issue",
                                            "maintainability",
                                            FIRST_WORDING)))
                    .isFalse();
            assertThat(
                            FindingSimilarity.duplicates(
                                    base,
                                    comment(
                                            "A.java",
                                            1,
                                            "suggestion",
                                            "correctness",
                                            FIRST_WORDING)))
                    .isFalse();
        }

        @Test
        void rejectsDifferentFindingsThatShareFewIdentifiers() {
            assertThat(
                            FindingSimilarity.duplicates(
                                    comment(
                                            1,
                                            "`fetchUser` swallows `IOException` from `userClient`."),
                                    comment(
                                            4,
                                            "`fetchUser` builds `pageToken` without validating"
                                                    + " `pageSize`.")))
                    .isFalse();
        }

        @Test
        void rejectsEmptyBodiesOnDifferentLines() {
            assertThat(FindingSimilarity.duplicates(comment(1, ""), comment(2, " "))).isFalse();
            assertThat(FindingSimilarity.duplicates(comment(1, ""), comment(1, null))).isTrue();
        }
    }

    @Nested
    class Identifiers {
        @Test
        void keepsCamelCaseAndUnderscoredTokensOnly() {
            assertThat(
                            FindingSimilarity.identifiers(
                                    "This Add Either toMaybe AbstractUCClient NOT_FOUND URL a_b"
                                            + " plain _x"))
                    .containsExactlyInAnyOrder("toMaybe", "AbstractUCClient", "NOT_FOUND", "a_b");
        }

        @Test
        void handlesNull() {
            assertThat(FindingSimilarity.identifiers(null)).isEmpty();
        }
    }

    @Nested
    class Words {
        @Test
        void keepsWordsOfFourOrMoreCharacters() {
            assertThat(FindingSimilarity.words("the retry loop is slow2 ok"))
                    .containsExactlyInAnyOrder("retry", "loop", "slow2");
        }
    }
}
