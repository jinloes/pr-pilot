package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.compare;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.edition2023;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.impacts;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.proto2;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.proto3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProtoSchemaDeltaFieldTest {
    @Nested
    class Presence {
        @Test
        void reportsAPresenceChange() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { int32 a = 1; }"),
                            proto3("message M { optional int32 a = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_SAME_PRESENCE");
                                assertThat(change.impacts()).containsExactly("json", "source");
                                assertThat(change.detail())
                                        .isEqualTo("M.a (1): implicit -> explicit presence");
                            });
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        optional int32 a = 1; | required int32 a = 1; | M.a became required
                        required int32 a = 1; | optional int32 a = 1; | M.a no longer required
                        """)
        void reportsARequiredLabelChangeAsARequiredFieldsChange(
                String baseField, String headField, String detail) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto2("message M { " + baseField + " }"),
                            proto2("message M { " + headField + " }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("MESSAGE_SAME_REQUIRED_FIELDS");
                                assertThat(change.impacts())
                                        .containsExactly("wire", "json", "source");
                                assertThat(change.detail()).isEqualTo(detail);
                            });
        }

        @Test
        void reportsAddedAndRemovedRequiredFields() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto2("message M { required int32 a = 1; }"),
                            proto2("message M { required int32 b = 2; }"));

            assertThat(changes)
                    .filteredOn(change -> change.rule().equals("MESSAGE_SAME_REQUIRED_FIELDS"))
                    .extracting(ProtoSchemaDelta.Change::detail)
                    .containsExactly("M.a required field removed", "M.b required field added");
        }
    }

    @Nested
    class Deletions {
        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        '' | FIELD_NO_DELETE_UNLESS_NUMBER_RESERVED | reuse-risk json source | number 2 and name b not reserved
                        reserved 2; | FIELD_NO_DELETE_UNLESS_NAME_RESERVED | json source | number 2 reserved and name b not reserved
                        reserved 2; reserved "b"; | FIELD_DELETED_RESERVED | json source | number 2 and name b reserved
                        reserved "b"; | FIELD_NO_DELETE_UNLESS_NUMBER_RESERVED | reuse-risk json source | number 2 not reserved and name b reserved
                        """)
        void reportsADeletedFieldByWhatItsMessageReserves(
                String reserved, String rule, String expectedImpacts, String reservation) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { int32 a = 1; int32 b = 2; }"),
                            proto3("message M { int32 a = 1; " + reserved + " }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo(rule);
                                assertThat(change.impacts()).isEqualTo(impacts(expectedImpacts));
                                assertThat(change.detail())
                                        .isEqualTo("M.b (2, int32) removed; " + reservation);
                            });
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        '' | ENUM_VALUE_NO_DELETE_UNLESS_NUMBER_RESERVED | reuse-risk json source | number 1 and name E_ONE not reserved
                        reserved 1; | ENUM_VALUE_NO_DELETE_UNLESS_NAME_RESERVED | json source | number 1 reserved and name E_ONE not reserved
                        reserved 1; reserved "E_ONE"; | ENUM_VALUE_DELETED_RESERVED | json source | number 1 and name E_ONE reserved
                        reserved "E_ONE"; | ENUM_VALUE_NO_DELETE_UNLESS_NUMBER_RESERVED | reuse-risk json source | number 1 not reserved and name E_ONE reserved
                        """)
        void reportsADeletedEnumValueByWhatItsEnumReserves(
                String reserved, String rule, String expectedImpacts, String reservation) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("enum E { E_UNSPECIFIED = 0; E_ONE = 1; }"),
                            proto3("enum E { E_UNSPECIFIED = 0; " + reserved + " }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo(rule);
                                assertThat(change.impacts()).isEqualTo(impacts(expectedImpacts));
                                assertThat(change.detail())
                                        .isEqualTo("E.E_ONE (1) removed; " + reservation);
                            });
        }
    }

    @Nested
    class Reuse {
        @Test
        void reportsAFieldThatTakesAReservedName() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { reserved \"old\"; }"),
                            proto3("message M { string old = 1; }"));

            assertThat(changes)
                    .extracting(
                            ProtoSchemaDelta.Change::rule,
                            ProtoSchemaDelta.Change::impacts,
                            ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            tuple(
                                    "RESERVED_MESSAGE_NO_DELETE",
                                    List.of("reuse-risk"),
                                    "M: reserved old removed"),
                            tuple(
                                    "FIELD_NAME_REUSED",
                                    List.of("json"),
                                    "M.old uses a reserved name"));
        }

        @Test
        void reportsAFieldThatTakesAReservedNumber() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { reserved 20; }"),
                            proto3("message M { reserved 20; string x = 20; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_NUMBER_REUSED");
                                assertThat(change.impacts())
                                        .containsExactly("wire", "json", "source");
                                assertThat(change.detail())
                                        .isEqualTo("M 20: reserved number now used by x (string)");
                            });
        }

        @Test
        void reportsANumberTakenOverByADifferentField() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { int32 a = 1; }"),
                            proto3("message M { string b = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_NUMBER_REUSED");
                                assertThat(change.impacts())
                                        .containsExactly("wire", "json", "source");
                                assertThat(change.detail())
                                        .isEqualTo("M 1: a (int32) -> b (string)");
                            });
        }
    }

    @Nested
    class Options {
        @Test
        void reportsAJsonNameChange() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { string first_name = 1; }"),
                            proto3("message M { string first_name = 1 [json_name = \"name\"]; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_SAME_JSON_NAME");
                                assertThat(change.impacts()).containsExactly("json");
                                assertThat(change.detail())
                                        .isEqualTo("M.first_name (1): json_name changed");
                            });
        }

        @Test
        void ignoresAJsonNameThatSpellsOutTheDefault() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { string first_name = 1; }"),
                            proto3(
                                    "message M { string first_name = 1 [json_name ="
                                            + " \"firstName\"]; }"));

            assertThat(changes).isEmpty();
        }

        @Test
        void reportsACtypeChange() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto2("message M { optional string s = 1; }"),
                            proto2("message M { optional string s = 1 [ctype = CORD]; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_SAME_CTYPE");
                                assertThat(change.impacts()).containsExactly("source");
                                assertThat(change.detail()).isEqualTo("M.s (1): ctype changed");
                            });
        }

        @Test
        void reportsFieldFeaturesThatHaveNoRuleOfTheirOwn() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            edition2023("message M { string s = 1; repeated int32 r = 2; }"),
                            edition2023(
                                    "message M { string s = 1 [features.utf8_validation = NONE,"
                                            + " features.field_presence = IMPLICIT]; repeated int32"
                                            + " r = 2 [features.repeated_field_encoding ="
                                            + " EXPANDED]; }"));

            assertThat(changes)
                    .extracting(
                            ProtoSchemaDelta.Change::rule,
                            ProtoSchemaDelta.Change::impacts,
                            ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            tuple(
                                    "FIELD_SAME_PRESENCE",
                                    List.of("json", "source"),
                                    "M.s (1): explicit -> implicit presence"),
                            tuple(
                                    "FIELD_SAME_FEATURES",
                                    List.of("semantic"),
                                    "M.s: features.utf8_validation unset -> NONE"));
        }

        @Test
        void reportsTightenedFieldBehaviorsInAFixedOrder() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(
                                    "message M { string a = 1 [(google.api.field_behavior) ="
                                            + " OUTPUT_ONLY]; string id = 2"
                                            + " [(google.api.field_behavior) = IDENTIFIER]; }"),
                            proto3(
                                    "message M { string a = 1 [(google.api.field_behavior) ="
                                            + " INPUT_ONLY, (google.api.field_behavior) ="
                                            + " IMMUTABLE, (google.api.field_behavior) ="
                                            + " REQUIRED]; string id = 2; }"));

            assertThat(changes)
                    .allSatisfy(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_BEHAVIOR_TIGHTENED");
                                assertThat(change.impacts()).containsExactly("semantic");
                            })
                    .extracting(ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            "M.a (1): field_behavior REQUIRED added",
                            "M.a (1): field_behavior IMMUTABLE added",
                            "M.a (1): field_behavior INPUT_ONLY added",
                            "M.id (2): field_behavior IDENTIFIER removed");
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        '' | [(buf.validate.field).string.min_len = 1]
                        [(buf.validate.field).string.min_len = 1] | [(buf.validate.field).string.min_len = 2]
                        [(validate.rules).string.email = true] | ''
                        [(buf.validate.field) = {string: {min_len: 1}}] | [(buf.validate.field) = {string: {min_len: 2}}]
                        '' | [(.buf.validate.field).string.max_len = 9]
                        [(buf.validate.field).string.in = "a", (buf.validate.field).string.in = "b"] | [(buf.validate.field).string.in = "a", (buf.validate.field).string.in = "c"]
                        """)
        void reportsValidationRuleChanges(String baseOptions, String headOptions) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { string s = 1 " + baseOptions + "; }"),
                            proto3("message M { string s = 1 " + headOptions + "; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_VALIDATION_CHANGED");
                                assertThat(change.impacts()).containsExactly("semantic");
                                assertThat(change.detail())
                                        .isEqualTo("M.s (1): validation rules changed");
                            });
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        [(buf.validate.field).string.min_len = 1] | [(buf.validate.field).string.min_len = 1]
                        [(buf.validate.field).string.min_len = 1] | [(.buf.validate.field).string.min_len = 1]
                        """)
        void ignoresUnchangedValidationRules(String baseOptions, String headOptions) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { string s = 1 " + baseOptions + "; }"),
                            proto3("message M { string s = 1 " + headOptions + "; }"));

            assertThat(changes).isEmpty();
        }
    }
}
