package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProtoSchemaTest {
    @Nested
    class Parse {
        @Test
        void parsesProto2Proto3AndEditionsFixtures() {
            String proto2 =
                    """
                    syntax = "proto2";
                    package api.user.v1;
                    option java_package = "unsafe string is internal only";
                    message User {
                      option features.field_presence = EXPLICIT;
                      required int64 id = 1 [default = 7, (google.api.field_behavior) = REQUIRED];
                      optional group Address = 2 { optional string city = 1; }
                      reserved 7, 9 to 11, "old_name";
                    }
                    enum Status {
                      option allow_alias = true;
                      UNKNOWN = 0;
                      ACTIVE = 1;
                    }
                    """;
            String proto3 =
                    """
                    syntax = "proto3";
                    package api.user.v1;
                    message Search {
                      string message = 1;
                      int32 option = 2;
                      bool optional = 3;
                      optional int32 count = 4 [json_name = "totalCount", packed = false];
                      map<string, User> users = 5;
                      oneof key {
                        string email = 6;
                      }
                    }
                    service UserService {
                      rpc GetUser (GetUserRequest) returns (stream User) {
                        option idempotency_level = NO_SIDE_EFFECTS;
                        option (google.api.http) = { get: "/v1/{name=users/*}" };
                      };
                    }
                    """;
            String editions =
                    """
                    edition = "2023";
                    package api.user.v1alpha1;
                    option features.enum_type = CLOSED;
                    export message Event {
                      repeated int32 ids = 1 [features.repeated_field_encoding = EXPANDED];
                      string name = 2 [(buf.validate.field) = { string: { min_len: 1 } }];
                      reserved deleted_name;
                    }
                    local enum Kind {
                      option features.enum_type = OPEN;
                      KIND_UNSPECIFIED = 0;
                    }
                    """;

            ProtoSchema parsedProto2 = ProtoSchema.parse(proto2).orElseThrow();
            ProtoSchema parsedProto3 = ProtoSchema.parse(proto3).orElseThrow();
            ProtoSchema parsedEditions = ProtoSchema.parse(editions).orElseThrow();

            assertThat(parsedProto2.messages()).containsKey("api.user.v1.User");
            assertThat(
                            parsedProto2
                                    .messages()
                                    .get("api.user.v1.User")
                                    .reserved()
                                    .reservesNumber(10))
                    .isTrue();
            assertThat(
                            parsedProto2
                                    .messages()
                                    .get("api.user.v1.User")
                                    .reserved()
                                    .reservesName("old_name"))
                    .isTrue();
            assertThat(parsedProto2.enums().get("api.user.v1.Status").allowAlias()).isTrue();

            ProtoSchema.Field keywordField =
                    parsedProto3
                            .messages()
                            .get("api.user.v1.Search")
                            .fieldByName("optional")
                            .orElseThrow();
            assertThat(keywordField.type()).isEqualTo("bool");
            assertThat(parsedProto3.services().get("api.user.v1.UserService").rpcs())
                    .singleElement()
                    .satisfies(
                            rpc -> {
                                assertThat(rpc.serverStreaming()).isTrue();
                                assertThat(rpc.idempotencyLevel()).isEqualTo("NO_SIDE_EFFECTS");
                                assertThat(rpc.httpOption()).isNotBlank();
                            });

            ProtoSchema.Field ids =
                    parsedEditions
                            .messages()
                            .get("api.user.v1alpha1.Event")
                            .fieldByName("ids")
                            .orElseThrow();
            assertThat(parsedEditions.edition()).isEqualTo("2023");
            assertThat(
                            parsedEditions.effectiveRepeatedEncoding(
                                    parsedEditions.messages().get("api.user.v1alpha1.Event"), ids))
                    .isEqualTo(ProtoSchema.RepeatedEncoding.EXPANDED);
        }

        @Test
        void keysValidationRulesByOptionPathAndMergesRepeatedOnes() {
            ProtoSchema schema =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto3";
                                    package api.v1;
                                    message M {
                                      string s = 1 [
                                        (.buf.validate.field).string.min_len = 1,
                                        (buf.validate.field).string.not_in = "a",
                                        (buf.validate.field).string.not_in = "b",
                                        (validate.rules).string.email = true
                                      ];
                                    }
                                    """)
                            .orElseThrow();

            ProtoSchema.FieldOptions options =
                    schema.messages().get("api.v1.M").fieldByName("s").orElseThrow().options();

            assertThat(options.validations())
                    .containsExactly(
                            entry("(buf.validate.field).string.min_len", "1"),
                            entry("(buf.validate.field).string.not_in", "STRING:a, STRING:b"),
                            entry("(validate.rules).string.email", "true"));
            assertThat(options.features()).isEmpty();
        }

        @Test
        void keepsDeclarationOrder() {
            ProtoSchema schema =
                    ProtoSchema.parse(
                                    """
                                    edition = "2023";
                                    package api.v1;
                                    option features.utf8_validation = NONE;
                                    option features.enum_type = CLOSED;
                                    option java_package = "x";
                                    option go_package = "y";
                                    message Zebra {}
                                    message Apple {}
                                    enum Zed { ZED_UNSPECIFIED = 0; }
                                    enum Ant { ANT_UNSPECIFIED = 0; }
                                    service Zoo {}
                                    service Aviary {}
                                    """)
                            .orElseThrow();

            assertThat(List.copyOf(schema.messages().keySet()))
                    .containsExactly("api.v1.Zebra", "api.v1.Apple");
            assertThat(List.copyOf(schema.enums().keySet()))
                    .containsExactly("api.v1.Zed", "api.v1.Ant");
            assertThat(List.copyOf(schema.services().keySet()))
                    .containsExactly("api.v1.Zoo", "api.v1.Aviary");
            assertThat(List.copyOf(schema.fileFeatures().keySet()))
                    .containsExactly("utf8_validation", "enum_type");
            assertThat(List.copyOf(schema.fileOptions().keySet()))
                    .containsExactly(
                            "features.utf8_validation",
                            "features.enum_type",
                            "java_package",
                            "go_package");
        }

        @Test
        void failsClosedForMalformedInput() {
            assertThat(ProtoSchema.parse("message MissingBrace { string name = 1;")).isEmpty();
            assertThat(ProtoSchema.parse("syntax = \"proto3\"; nonsense;")).isEmpty();
            assertThat(ProtoSchema.parse("syntax = \"proto3\"; /* unterminated")).isEmpty();
            assertThat(ProtoSchema.parse("syntax = \"proto3\"; message User { string name; }"))
                    .isEmpty();
        }
    }

    @Nested
    class DerivedProperties {
        @Test
        void resolvesPresenceEnumOpennessScalarsAndPacking() {
            ProtoSchema schema =
                    ProtoSchema.parse(
                                    """
                                    syntax = "proto3";
                                    package api.user.v1;
                                    message Child { string value = 1; }
                                    message User {
                                      int32 age = 1;
                                      optional int32 score = 2;
                                      Child child = 3;
                                      repeated int32 nums = 4;
                                    }
                                    enum Status { STATUS_UNSPECIFIED = 0; ACTIVE = 1; }
                                    """)
                            .orElseThrow();

            ProtoSchema.Message user = schema.messages().get("api.user.v1.User");

            assertThat(schema.effectivePresence(user, user.fieldByName("age").orElseThrow()))
                    .isEqualTo(ProtoSchema.Presence.IMPLICIT);
            assertThat(schema.effectivePresence(user, user.fieldByName("score").orElseThrow()))
                    .isEqualTo(ProtoSchema.Presence.EXPLICIT);
            assertThat(schema.effectivePresence(user, user.fieldByName("child").orElseThrow()))
                    .isEqualTo(ProtoSchema.Presence.EXPLICIT);
            assertThat(
                            schema.effectiveRepeatedEncoding(
                                    user, user.fieldByName("nums").orElseThrow()))
                    .isEqualTo(ProtoSchema.RepeatedEncoding.PACKED);
            assertThat(schema.effectiveEnumOpenness(schema.enums().get("api.user.v1.Status")))
                    .isEqualTo(ProtoSchema.EnumOpenness.OPEN);
            assertThat(schema.isScalar("int32")).isTrue();
        }
    }
}
