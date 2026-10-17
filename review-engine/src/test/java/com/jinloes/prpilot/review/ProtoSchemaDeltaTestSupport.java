package com.jinloes.prpilot.review;

import java.util.List;

final class ProtoSchemaDeltaTestSupport {
    private ProtoSchemaDeltaTestSupport() {}

    static List<ProtoSchemaDelta.Change> compare(String base, String head) {
        return ProtoSchemaDelta.compare(
                ProtoSchema.parse(base).orElseThrow(), ProtoSchema.parse(head).orElseThrow());
    }

    static String proto2(String body) {
        return schema("proto2", body);
    }

    static String proto3(String body) {
        return schema("proto3", body);
    }

    static String schema(String syntax, String body) {
        return "syntax = \"" + syntax + "\";\npackage api.v1;\n" + body;
    }

    static String edition2023(String body) {
        return "edition = \"2023\";\npackage api.v1;\n" + body;
    }

    static List<String> impacts(String spaceSeparated) {
        return List.of(spaceSeparated.split(" "));
    }

    static List<String> rules(List<ProtoSchemaDelta.Change> changes) {
        return changes.stream().map(ProtoSchemaDelta.Change::rule).toList();
    }
}
