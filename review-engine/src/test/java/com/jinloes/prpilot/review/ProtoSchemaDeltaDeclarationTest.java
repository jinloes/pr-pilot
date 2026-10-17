package com.jinloes.prpilot.review;

import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.compare;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.edition2023;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.proto2;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.proto3;
import static com.jinloes.prpilot.review.ProtoSchemaDeltaTestSupport.rules;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProtoSchemaDeltaDeclarationTest {
    @Nested
    class FileLevel {
        @Test
        void reportsAPackageChangeAndTheTypesItRemoves() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            "syntax = \"proto3\";\npackage api.v1;\nmessage M {}\n",
                            "syntax = \"proto3\";\npackage api.v2;\nmessage M {}\n");

            assertThat(changes)
                    .extracting(
                            ProtoSchemaDelta.Change::rule,
                            ProtoSchemaDelta.Change::impacts,
                            ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            tuple(
                                    "FILE_SAME_PACKAGE",
                                    List.of("wire", "json", "source"),
                                    "package api.v1 -> api.v2; gRPC method paths and Any type"
                                            + " URLs change"),
                            tuple("MESSAGE_NO_DELETE", List.of("json", "source"), "M removed"));
        }

        @Test
        void reportsAnEditionChange() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            "edition = \"2023\";\npackage api.v1;\n",
                            "edition = \"2024\";\npackage api.v1;\n");

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FILE_SAME_SYNTAX");
                                assertThat(change.impacts()).containsExactly("source", "semantic");
                                assertThat(change.detail()).isEqualTo("edition 2023 -> 2024");
                            });
        }

        @Test
        void reportsAFileFeatureChange() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            edition2023("option features.utf8_validation = NONE;\n"),
                            edition2023(""));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FILE_SAME_FEATURES");
                                assertThat(change.impacts()).containsExactly("semantic");
                                assertThat(change.detail())
                                        .isEqualTo("file: features.utf8_validation NONE -> unset");
                            });
        }

        @Test
        void reportsLanguageOptionChangesInAFixedOrder() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("option java_package = \"a\";\noption go_package = \"a\";\n"),
                            proto3("option java_package = \"b\";\noption go_package = \"b\";\n"));

            assertThat(changes)
                    .extracting(
                            ProtoSchemaDelta.Change::rule,
                            ProtoSchemaDelta.Change::impacts,
                            ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            tuple(
                                    "FILE_SAME_GO_PACKAGE",
                                    List.of("source"),
                                    "option go_package changed"),
                            tuple(
                                    "FILE_SAME_JAVA_PACKAGE",
                                    List.of("source"),
                                    "option java_package changed"));
        }
    }

    @Nested
    class Enums {
        @Test
        void reportsAnEnumThatOpens() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            edition2023(
                                    "enum E { option features.enum_type = CLOSED; E_UNSPECIFIED ="
                                            + " 0; }"),
                            edition2023("enum E { E_UNSPECIFIED = 0; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("ENUM_SAME_TYPE");
                                assertThat(change.impacts()).containsExactly("semantic");
                                assertThat(change.detail()).isEqualTo("E: closed -> open");
                            });
        }

        @Test
        void reportsAValueAddedToAClosedEnum() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto2("enum E { E_UNSPECIFIED = 0; }"),
                            proto2("enum E { E_UNSPECIFIED = 0; E_ONE = 1; }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule())
                                        .isEqualTo("ENUM_VALUE_ADDED_TO_CLOSED_ENUM");
                                assertThat(change.impacts()).containsExactly("semantic");
                                assertThat(change.detail())
                                        .isEqualTo("E.E_ONE added to closed enum");
                            });
        }

        @Test
        void ignoresAValueAddedToAnOpenEnum() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("enum E { E_UNSPECIFIED = 0; }"),
                            proto3("enum E { E_UNSPECIFIED = 0; E_ONE = 1; }"));

            assertThat(changes).isEmpty();
        }
    }

    @Nested
    class Rpcs {
        @Test
        void reportsRequestTypeAndStreamingChanges() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(
                                    "service S { rpc Get (GetRequest) returns (GetResponse); rpc"
                                            + " Watch (WatchRequest) returns (WatchResponse); }"),
                            proto3(
                                    "service S { rpc Get (FetchRequest) returns (GetResponse);"
                                            + " rpc Watch (stream WatchRequest) returns (stream"
                                            + " WatchResponse); }"));

            assertThat(changes)
                    .extracting(ProtoSchemaDelta.Change::rule, ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            tuple("RPC_SAME_REQUEST_TYPE", "S.Get: GetRequest -> FetchRequest"),
                            tuple("RPC_SAME_CLIENT_STREAMING", "S.Watch: client streaming changed"),
                            tuple(
                                    "RPC_SAME_SERVER_STREAMING",
                                    "S.Watch: server streaming changed"));
        }
    }

    @Nested
    class Oneofs {
        @Test
        void reportsOnlyTheRenameWhenAOneofIsRenamed() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { oneof id { string a = 1; int32 b = 2; } }"),
                            proto3("message M { oneof key { string a = 1; int32 b = 2; } }"));

            assertThat(changes)
                    .singleElement()
                    .satisfies(
                            change -> {
                                assertThat(change.rule()).isEqualTo("ONEOF_RENAMED");
                                assertThat(change.impacts()).containsExactly("source");
                                assertThat(change.detail()).isEqualTo("M.id -> key");
                            });
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "oneof key { string a = 1; int32 b = 2; string c = 3; }",
                    "oneof key { string a = 1; }"
                })
        void reportsTheRenameWhenMembersAreAlsoAddedOrRemoved(String headOneof) {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3("message M { oneof id { string a = 1; int32 b = 2; } }"),
                            proto3("message M { " + headOneof + " }"));

            assertThat(rules(changes)).contains("ONEOF_RENAMED").doesNotContain("FIELD_SAME_ONEOF");
        }

        @Test
        void reportsBothRenamesWhenTwoOneofsSwapNames() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(
                                    "message M { oneof x { string a = 1; } oneof y { string b ="
                                            + " 2; } }"),
                            proto3(
                                    "message M { oneof y { string a = 1; } oneof x { string b ="
                                            + " 2; } }"));

            assertThat(changes)
                    .extracting(ProtoSchemaDelta.Change::rule, ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            tuple("ONEOF_RENAMED", "M.x -> y"), tuple("ONEOF_RENAMED", "M.y -> x"));
        }

        @Test
        void reportsEachMovedFieldWhenOneofsAreMerged() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(
                                    "message M { oneof x { string a = 1; } oneof y { string b ="
                                            + " 2; } }"),
                            proto3("message M { oneof z { string a = 1; string b = 2; } }"));

            assertThat(changes)
                    .extracting(
                            ProtoSchemaDelta.Change::rule,
                            ProtoSchemaDelta.Change::impacts,
                            ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            tuple(
                                    "FIELD_SAME_ONEOF",
                                    List.of("wire", "json", "source"),
                                    "M.a (1): oneof x -> z"),
                            tuple(
                                    "FIELD_SAME_ONEOF",
                                    List.of("wire", "json", "source"),
                                    "M.b (2): oneof y -> z"));
        }
    }

    @Nested
    class Determinism {
        @Test
        void reportsRemovedTypesInDeclarationOrder() {
            List<ProtoSchemaDelta.Change> changes =
                    compare(
                            proto3(
                                    "message Zebra {} message Apple {} message Mango {} enum Zed {"
                                            + " ZED_UNSPECIFIED = 0; } enum Ant { ANT_UNSPECIFIED ="
                                            + " 0; } enum Mole { MOLE_UNSPECIFIED = 0; }"),
                            proto3(""));

            assertThat(changes)
                    .extracting(ProtoSchemaDelta.Change::detail)
                    .containsExactly(
                            "Zebra removed",
                            "Apple removed",
                            "Mango removed",
                            "Zed removed",
                            "Ant removed",
                            "Mole removed");
        }
    }
}
