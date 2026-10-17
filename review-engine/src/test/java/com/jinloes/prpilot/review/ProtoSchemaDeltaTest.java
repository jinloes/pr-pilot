package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProtoSchemaDeltaTest {
    @Nested
    class Compare {
        @Test
        void reportsFieldCompatibilityRulesAndReservations() {
            ProtoSchema base =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto3";
                                    package api.user.v1;
                                    message User {
                                      string name = 1;
                                      int32 age = 2;
                                      string email = 3;
                                      string nickname = 4;
                                      optional int32 score = 5 [default = 1, jstype = JS_NORMAL];
                                      repeated int32 nums = 6 [packed = true];
                                      string moved = 7;
                                      reserved 20 to 22, old_reserved;
                                    }
                                    """)
                            .orElseThrow();
            ProtoSchema head =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto3";
                                    package api.user.v1;
                                    message User {
                                      string full_name = 1 [json_name = "fullName"];
                                      int64 age = 2;
                                      string email = 30;
                                      optional int32 score = 5 [default = 2, jstype = JS_STRING];
                                      int32 nums = 6;
                                      oneof identity { string moved = 7; }
                                      string reused = 20;
                                      reserved old_reserved;
                                    }
                                    """)
                            .orElseThrow();

            List<ProtoSchemaDelta.Change> changes = ProtoSchemaDelta.compare(base, head);

            assertThat(rules(changes))
                    .contains(
                            "FIELD_SAME_NAME",
                            "FIELD_WIRE_COMPATIBLE_TYPE",
                            "FIELD_NUMBER_CHANGED",
                            "FIELD_NO_DELETE_UNLESS_NUMBER_RESERVED",
                            "FIELD_SAME_DEFAULT",
                            "FIELD_SAME_JSTYPE",
                            "FIELD_SAME_CARDINALITY",
                            "FIELD_SAME_ONEOF",
                            "FIELD_NUMBER_REUSED",
                            "RESERVED_MESSAGE_NO_DELETE");
            assertThat(changes)
                    .anySatisfy(
                            change -> {
                                assertThat(change.rule()).isEqualTo("FIELD_NUMBER_CHANGED");
                                assertThat(change.impacts()).containsExactly("wire", "reuse-risk");
                                assertThat(change.detail()).contains("email: 3 -> 30");
                            });
        }

        @Test
        void reportsEnumServiceAndFileChanges() {
            ProtoSchema base =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto2";
                                    package api.user.v1beta1;
                                    option go_package = "base";
                                    message User {}
                                    enum Status {
                                      UNKNOWN = 0;
                                      ACTIVE = 1;
                                      OLD = 2;
                                      reserved 9 to 10;
                                    }
                                    service UserService {
                                      rpc Get (GetRequest) returns (User);
                                      rpc Delete (DeleteRequest) returns (User);
                                    }
                                    """)
                            .orElseThrow();
            ProtoSchema head =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto3";
                                    package api.user.v1beta1;
                                    option go_package = "head";
                                    message User {}
                                    enum Status {
                                      option features.enum_type = CLOSED;
                                      ACTIVE = 0;
                                      OLD = 7;
                                    }
                                    service UserService {
                                      rpc Get (GetRequest) returns (GetResponse) {
                                        option idempotency_level = NO_SIDE_EFFECTS;
                                        option (google.api.http) = { get: "/v1/users/{name}" };
                                      }
                                    }
                                    """)
                            .orElseThrow();

            List<ProtoSchemaDelta.Change> changes = ProtoSchemaDelta.compare(base, head);

            assertThat(rules(changes))
                    .contains(
                            "FILE_SAME_SYNTAX",
                            "FILE_SAME_GO_PACKAGE",
                            "ENUM_ZERO_VALUE_CHANGED",
                            "ENUM_VALUE_NUMBER_CHANGED",
                            "RESERVED_ENUM_NO_DELETE",
                            "RPC_SAME_RESPONSE_TYPE",
                            "RPC_SAME_IDEMPOTENCY_LEVEL",
                            "RPC_SAME_HTTP_RULE",
                            "RPC_NO_DELETE");
            assertThat(changes).allSatisfy(change -> assertThat(change.impacts()).contains("beta"));
        }

        @Test
        void reportsNewMeaningfulEnumDefaultAndRemovedTypes() {
            ProtoSchema base =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto3";
                                    package api.user.v1;
                                    message Gone {}
                                    enum Old { OLD_UNSPECIFIED = 0; }
                                    service OldService { rpc Get (A) returns (B); }
                                    """)
                            .orElseThrow();
            ProtoSchema head =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto3";
                                    package api.user.v1;
                                    enum Status { ACTIVE = 0; }
                                    """)
                            .orElseThrow();

            List<ProtoSchemaDelta.Change> changes = ProtoSchemaDelta.compare(base, head);

            assertThat(rules(changes))
                    .contains(
                            "MESSAGE_NO_DELETE",
                            "ENUM_NO_DELETE",
                            "SERVICE_NO_DELETE",
                            "ENUM_ZERO_VALUE_NOT_UNSPECIFIED");
        }
    }

    @Nested
    class TypeChanges {
        @Test
        void exposesAddedAndRemovedTypeNamesForMoveDetection() {
            ProtoSchema base =
                    ProtoSchema.parse("syntax = \"proto3\"; package a.v1; message User {}")
                            .orElseThrow();
            ProtoSchema head =
                    ProtoSchema.parse("syntax = \"proto3\"; package a.v1; message Account {}")
                            .orElseThrow();

            ProtoSchemaDelta.TypeChanges changes = ProtoSchemaDelta.typeChanges(base, head);

            assertThat(changes.removedMessages()).containsExactly("a.v1.User");
            assertThat(changes.addedMessages()).containsExactly("a.v1.Account");
        }
    }

    private static List<String> rules(List<ProtoSchemaDelta.Change> changes) {
        return changes.stream().map(ProtoSchemaDelta.Change::rule).toList();
    }
}
