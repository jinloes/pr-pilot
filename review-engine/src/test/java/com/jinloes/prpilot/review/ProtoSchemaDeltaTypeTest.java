package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.compare;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.impacts;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.proto3;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.schema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProtoSchemaDeltaTypeTest {
    @Nested
    class FieldTypes {
        @ParameterizedTest
        @CsvSource({
            "int32, uint32, FIELD_WIRE_JSON_COMPATIBLE_TYPE, source",
            "fixed64, sfixed64, FIELD_WIRE_JSON_COMPATIBLE_TYPE, source",
            "int32, int64, FIELD_WIRE_COMPATIBLE_TYPE, json source",
            "sint64, sint32, FIELD_WIRE_COMPATIBLE_TYPE, json source",
            "string, bytes, FIELD_WIRE_COMPATIBLE_TYPE, json source",
            "bool, int32, FIELD_WIRE_COMPATIBLE_TYPE, json source",
            "bytes, string, FIELD_SAME_TYPE, wire json source",
            "int32, string, FIELD_SAME_TYPE, wire json source",
            "int32, sint32, FIELD_SAME_TYPE, wire json source",
            "float, double, FIELD_SAME_TYPE, wire json source"
        })
        void classifiesScalarTypeChanges(
                String oldType, String newType, String rule, String expectedImpacts) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { " + oldType + " f = 1; }"),
                            proto3("message M { " + newType + " f = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo(rule);
                                assertThat(change.impacts()).isEqualTo(impacts(expectedImpacts));
                                assertThat(change.detail())
                                        .startsWith("M.f (1): " + oldType + " -> " + newType);
                            });
        }

        @Test
        void notesHowValuesReadAfterACompatibleChange() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { int32 a = 1; int64 b = 2; string c = 3; }"),
                            proto3("message M { uint32 a = 1; int32 b = 2; bytes c = 3; }"));

            assertThat(changes)
                    .extracting(ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            "M.a (1): int32 -> uint32 (values outside the shared range are"
                                    + " reinterpreted)",
                            "M.b (2): int64 -> int32 (out-of-range values are truncated)",
                            "M.c (3): string -> bytes");
        }

        @Test
        void treatsAnEnumAsWireCompatibleWithAnInteger() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("enum E { E_UNSPECIFIED = 0; } message M { E f = 1; }"),
                            proto3("enum E { E_UNSPECIFIED = 0; } message M { int32 f = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_WIRE_COMPATIBLE_TYPE");
                                assertThat(change.impacts()).containsExactly("json", "source");
                                assertThat(change.detail()).isEqualTo("M.f (1): E -> int32");
                            });
        }

        @Test
        void reportsOnlySourceImpactForAMessageOfTheSameShape() {
            String types = "message A { string id = 1; } message B { string id = 1; } ";

            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(types + "message M { A f = 1; }"),
                            proto3(types + "message M { B f = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_SAME_TYPE");
                                assertThat(change.impacts()).containsExactly("source");
                                assertThat(change.detail()).isEqualTo("M.f (1): A -> B");
                            });
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        message A { string id = 1; } message B { int64 id = 1; } | A | B
                        message A { map<string, int32> m = 1; } message B { map<string, string> m = 1; } | A | B
                        '' | other.v1.Money | other.v1.Cash
                        '' | .other.v1.Money | .other.v1.Cash
                        """)
        void reportsAWireBreakForAMessageItCannotProveCompatible(
                String types, String oldType, String newType) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(types + " message M { " + oldType + " f = 1; }"),
                            proto3(types + " message M { " + newType + " f = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_SAME_TYPE");
                                assertThat(change.impacts())
                                        .containsExactly("wire", "json", "source");
                                assertThat(change.detail())
                                        .isEqualTo("M.f (1): " + oldType + " -> " + newType);
                            });
        }
    }

    @Nested
    class MapFields {
        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        map<string, int32> | map<int64, int32> | FIELD_SAME_TYPE | wire json source
                        map<string, int32> | map<int64, int64> | FIELD_SAME_TYPE | wire json source
                        map<string, int32> | map<string, int64> | FIELD_WIRE_COMPATIBLE_TYPE | json source
                        map<int32, string> | map<uint32, string> | FIELD_WIRE_JSON_COMPATIBLE_TYPE | source
                        """)
        void comparesKeyAndValueTypesSeparately(
                String oldType, String newType, String rule, String expectedImpacts) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { " + oldType + " m = 1; }"),
                            proto3("message M { " + newType + " m = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo(rule);
                                assertThat(change.impacts()).isEqualTo(impacts(expectedImpacts));
                                assertThat(change.detail())
                                        .startsWith("M.m (1): " + oldType + " -> " + newType);
                            });
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        map<string, int32> m = 1; | repeated Entry m = 1; | map -> repeated
                        repeated Entry m = 1; | map<string, int32> m = 1; | repeated -> map
                        """)
        void treatsARepeatedEntryMessageAsWireCompatibleWithAMap(
                String baseField, String headField, String cardinality) {
            String entry = "message Entry { string key = 1; int32 value = 2; } ";

            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(entry + "message M { " + baseField + " }"),
                            proto3(entry + "message M { " + headField + " }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule())
                                        .isEqualTo("FIELD_WIRE_COMPATIBLE_CARDINALITY");
                                assertThat(change.impacts()).containsExactly("json", "source");
                                assertThat(change.detail()).isEqualTo("M.m (1): " + cardinality);
                            });
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "repeated int32 m = 1;",
                    "repeated Other m = 1;",
                    "repeated Swapped m = 1;",
                    "Entry m = 1;"
                })
        void reportsAWireBreakWhenAMapBecomesAnythingButItsEntryMessage(String headField) {
            String types =
                    "message Entry { string key = 1; int32 value = 2; }"
                            + " message Other { string key = 1; }"
                            + " message Swapped { int32 key = 1; string value = 2; } ";

            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(types + "message M { map<string, int32> m = 1; }"),
                            proto3(types + "message M { " + headField + " }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_SAME_CARDINALITY");
                                assertThat(change.impacts())
                                        .containsExactly("wire", "json", "source");
                            });
        }
    }

    @Nested
    class Cardinality {
        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                textBlock =
                        """
                        proto3 | repeated string f = 1; | string f = 1; | only the last value is kept
                        proto3 | repeated A f = 1; | A f = 1; | elements are merged into one message
                        proto3 | repeated int32 f = 1 [packed = false]; | int32 f = 1; | only the last value is kept
                        proto2 | repeated int32 f = 1; | optional int32 f = 1; | only the last value is kept
                        proto3 | repeated other.v1.Money f = 1; | other.v1.Money f = 1; | only the last value is kept, or elements are merged for a message type
                        """)
        void notesWhatSurvivesWhenRepeatedValuesAreReadAsOne(
                String syntax, String baseField, String headField, String note) {
            String types = "message A { string id = 1; } ";

            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            schema(syntax, types + "message M { " + baseField + " }"),
                            schema(syntax, types + "message M { " + headField + " }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule())
                                        .isEqualTo("FIELD_WIRE_COMPATIBLE_CARDINALITY");
                                assertThat(change.impacts()).containsExactly("json", "source");
                                assertThat(change.detail())
                                        .isEqualTo("M.f (1): repeated -> singular (" + note + ")");
                            });
        }

        @Test
        void reportsAWireBreakWhenAPackedScalarBecomesSingular() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { repeated int32 f = 1; }"),
                            proto3("message M { int32 f = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_SAME_CARDINALITY");
                                assertThat(change.impacts())
                                        .containsExactly("wire", "json", "source");
                                assertThat(change.detail())
                                        .isEqualTo("M.f (1): repeated -> singular");
                            });
        }

        @Test
        void addsNoNoteWhenASingularFieldBecomesRepeated() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { string f = 1; }"),
                            proto3("message M { repeated string f = 1; }"));

            assertThat(changes)
                    .extracting(ProtoSchemaDelta.Change::detail)
                    .containsExactly("M.f (1): singular -> repeated");
        }

        @Test
        void reportsATypeChangeMadeAlongsideACardinalityChange() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { repeated string f = 1; }"),
                            proto3("message M { int32 f = 1; }"));

            assertThat(changes)
                    .extracting(ProtoSchemaDelta.Change::rule, ProtoSchemaDelta.Change::impacts)
                    .containsExactly(
                            tuple("FIELD_SAME_TYPE", List.of("wire", "json", "source")),
                            tuple("FIELD_WIRE_COMPATIBLE_CARDINALITY", List.of("json", "source")));
        }
    }
}
