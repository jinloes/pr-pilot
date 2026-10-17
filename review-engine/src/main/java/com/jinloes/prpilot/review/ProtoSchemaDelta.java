package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compares a base and head {@link ProtoSchema} and lists the compatibility-relevant changes under
 * Buf-style rule IDs ({@code FIELD_SAME_TYPE}, {@code RPC_NO_DELETE}, ...), each tagged with the
 * channels it can break: {@code wire}, {@code reuse-risk}, {@code json}, {@code source} and {@code
 * semantic}, plus {@code alpha} or {@code beta} for unstable packages.
 */
final class ProtoSchemaDelta {
    private static final List<String> IMPACT_ORDER =
            List.of("wire", "reuse-risk", "json", "source", "semantic");
    private static final Pattern ALPHA = Pattern.compile("v\\d+alpha\\d*");
    private static final Pattern BETA = Pattern.compile("v\\d+beta\\d*");
    private static final List<String> LANGUAGE_OPTIONS =
            List.of(
                    "go_package",
                    "java_package",
                    "java_outer_classname",
                    "java_multiple_files",
                    "java_string_check_utf8",
                    "java_generic_services",
                    "csharp_namespace",
                    "objc_class_prefix",
                    "php_namespace",
                    "php_metadata_namespace",
                    "php_class_prefix",
                    "php_generic_services",
                    "ruby_package",
                    "swift_prefix",
                    "optimize_for",
                    "cc_enable_arenas",
                    "cc_generic_services",
                    "py_generic_services");
    private static final Map<String, String> WIRE_JSON_PEERS =
            Map.of(
                    "int32", "uint32",
                    "uint32", "int32",
                    "int64", "uint64",
                    "uint64", "int64",
                    "fixed32", "sfixed32",
                    "sfixed32", "fixed32",
                    "fixed64", "sfixed64",
                    "sfixed64", "fixed64");
    private static final Set<String> VARINT_INTEGERS =
            Set.of("int32", "uint32", "int64", "uint64", "bool");
    private static final Set<String> ENUM_COMPATIBLE_INTEGERS =
            Set.of("int32", "uint32", "int64", "uint64");
    private static final List<String> TIGHTENING_BEHAVIORS =
            List.of("REQUIRED", "IMMUTABLE", "OUTPUT_ONLY", "INPUT_ONLY");

    private ProtoSchemaDelta() {}

    record Change(String rule, List<String> impacts, String detail, int headLine, int baseLine) {}

    record TypeChanges(
            Set<String> removedMessages,
            Set<String> removedEnums,
            Set<String> removedServices,
            Set<String> addedMessages,
            Set<String> addedEnums,
            Set<String> addedServices) {}

    static List<Change> compare(ProtoSchema base, ProtoSchema head) {
        List<Change> changes = new ArrayList<>();
        compareFile(base, head, changes);
        compareMessages(base, head, changes);
        compareEnums(base, head, changes);
        compareServices(base, head, changes);
        appendStabilityTags(
                head.packageName().isBlank() ? base.packageName() : head.packageName(), changes);
        return List.copyOf(changes);
    }

    static TypeChanges typeChanges(ProtoSchema base, ProtoSchema head) {
        return new TypeChanges(
                removed(base.messages().keySet(), head.messages().keySet()),
                removed(base.enums().keySet(), head.enums().keySet()),
                removed(base.services().keySet(), head.services().keySet()),
                removed(head.messages().keySet(), base.messages().keySet()),
                removed(head.enums().keySet(), base.enums().keySet()),
                removed(head.services().keySet(), base.services().keySet()));
    }

    private static void compareFile(ProtoSchema base, ProtoSchema head, List<Change> changes) {
        if (!Objects.equals(base.packageName(), head.packageName())) {
            add(
                    changes,
                    "FILE_SAME_PACKAGE",
                    impacts("wire", "json", "source"),
                    "package "
                            + safeDotted(base.packageName())
                            + " -> "
                            + safeDotted(head.packageName())
                            + "; gRPC method paths and Any type URLs change",
                    head.packageLine(),
                    base.packageLine());
        }
        if (!Objects.equals(base.syntax(), head.syntax())) {
            add(
                    changes,
                    "FILE_SAME_SYNTAX",
                    impacts("source", "semantic"),
                    "syntax "
                            + safeIdentifier(base.syntax())
                            + " -> "
                            + safeIdentifier(head.syntax()),
                    firstPositive(head.syntaxLine(), head.editionLine()),
                    firstPositive(base.syntaxLine(), base.editionLine()));
        } else if (!Objects.equals(base.edition(), head.edition())) {
            add(
                    changes,
                    "FILE_SAME_SYNTAX",
                    impacts("source", "semantic"),
                    "edition " + safeNumber(base.edition()) + " -> " + safeNumber(head.edition()),
                    head.editionLine(),
                    base.editionLine());
        }
        compareFeatureMaps(
                "FILE_SAME_FEATURES",
                "file",
                base.fileFeatures(),
                head.fileFeatures(),
                firstPositive(head.syntaxLine(), head.editionLine(), head.packageLine()),
                firstPositive(base.syntaxLine(), base.editionLine(), base.packageLine()),
                changes);
        for (String option : LANGUAGE_OPTIONS) {
            if (!Objects.equals(base.fileOptions().get(option), head.fileOptions().get(option))) {
                add(
                        changes,
                        "FILE_SAME_" + option.toUpperCase(Locale.ROOT),
                        impacts("source"),
                        "option " + option + " changed",
                        firstPositive(head.syntaxLine(), head.editionLine(), head.packageLine()),
                        firstPositive(base.syntaxLine(), base.editionLine(), base.packageLine()));
            }
        }
    }

    private static void compareMessages(ProtoSchema base, ProtoSchema head, List<Change> changes) {
        TypeChanges typeChanges = typeChanges(base, head);
        for (String removed : typeChanges.removedMessages()) {
            ProtoSchema.Message message = base.messages().get(removed);
            add(
                    changes,
                    "MESSAGE_NO_DELETE",
                    impacts("json", "source"),
                    base.relativeName(removed) + " removed",
                    0,
                    message.line());
        }
        for (String messageName :
                intersection(base.messages().keySet(), head.messages().keySet())) {
            ProtoSchema.Message baseMessage = base.messages().get(messageName);
            ProtoSchema.Message headMessage = head.messages().get(messageName);
            compareReserved(
                    "RESERVED_MESSAGE_NO_DELETE",
                    base.relativeName(messageName),
                    baseMessage.reserved(),
                    headMessage.reserved(),
                    headMessage.line(),
                    baseMessage.line(),
                    536870911,
                    changes);
            compareFields(base, head, baseMessage, headMessage, changes);
            compareOneofs(base, baseMessage, headMessage, changes);
        }
    }

    private static void compareFields(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            List<Change> changes) {
        Set<ProtoSchema.Field> pairedBase = new LinkedHashSet<>();
        Set<ProtoSchema.Field> pairedHead = new LinkedHashSet<>();
        for (ProtoSchema.Field baseField : baseMessage.fields()) {
            headMessage
                    .fieldByNumber(baseField.number())
                    .ifPresent(
                            headField -> {
                                pairedBase.add(baseField);
                                pairedHead.add(headField);
                                compareNumberPair(
                                        base,
                                        head,
                                        baseMessage,
                                        headMessage,
                                        baseField,
                                        headField,
                                        changes);
                            });
        }
        for (ProtoSchema.Field baseField : baseMessage.fields()) {
            if (pairedBase.contains(baseField)) {
                continue;
            }
            headMessage
                    .fieldByName(baseField.name())
                    .filter(headField -> !pairedHead.contains(headField))
                    .ifPresent(
                            headField -> {
                                pairedBase.add(baseField);
                                pairedHead.add(headField);
                                compareNamePair(
                                        base,
                                        head,
                                        baseMessage,
                                        headMessage,
                                        baseField,
                                        headField,
                                        changes);
                            });
        }
        for (ProtoSchema.Field baseField : baseMessage.fields()) {
            if (!pairedBase.contains(baseField)) {
                compareDeletedField(base, headMessage, baseMessage, baseField, changes);
            }
        }
        for (ProtoSchema.Field headField : headMessage.fields()) {
            if (pairedHead.contains(headField)) {
                continue;
            }
            if (baseMessage.reserved().reservesNumber(headField.number())) {
                add(
                        changes,
                        "FIELD_NUMBER_REUSED",
                        impacts("wire", "json", "source"),
                        base.relativeName(baseMessage.fqn())
                                + " "
                                + headField.number()
                                + ": reserved number now used by "
                                + headField.name()
                                + " ("
                                + renderType(headField)
                                + ")",
                        headField.line(),
                        baseMessage.line());
            }
            if (baseMessage.reserved().reservesName(headField.name())) {
                add(
                        changes,
                        "FIELD_NAME_REUSED",
                        impacts("json"),
                        base.relativeName(baseMessage.fqn())
                                + "."
                                + headField.name()
                                + " uses a reserved name",
                        headField.line(),
                        baseMessage.line());
            }
            if (head.effectivePresence(headMessage, headField)
                    == ProtoSchema.Presence.LEGACY_REQUIRED) {
                add(
                        changes,
                        "MESSAGE_SAME_REQUIRED_FIELDS",
                        impacts("wire", "json", "source"),
                        fieldPath(head, headMessage, headField) + " required field added",
                        headField.line(),
                        0);
            }
        }
    }

    private static void compareNumberPair(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            ProtoSchema.Field baseField,
            ProtoSchema.Field headField,
            List<Change> changes) {
        if (!baseField.name().equals(headField.name()) && !sameType(baseField, headField)) {
            add(
                    changes,
                    "FIELD_NUMBER_REUSED",
                    impacts("wire", "json", "source"),
                    base.relativeName(baseMessage.fqn())
                            + " "
                            + baseField.number()
                            + ": "
                            + baseField.name()
                            + " ("
                            + renderType(baseField)
                            + ") -> "
                            + headField.name()
                            + " ("
                            + renderType(headField)
                            + ")",
                    headField.line(),
                    baseField.line());
            return;
        }
        if (!baseField.name().equals(headField.name())) {
            add(
                    changes,
                    "FIELD_SAME_NAME",
                    impacts("json", "source"),
                    base.relativeName(baseMessage.fqn())
                            + " "
                            + baseField.number()
                            + ": "
                            + baseField.name()
                            + " -> "
                            + headField.name(),
                    headField.line(),
                    baseField.line());
        }
        compareCommonField(base, head, baseMessage, headMessage, baseField, headField, changes);
    }

    private static void compareNamePair(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            ProtoSchema.Field baseField,
            ProtoSchema.Field headField,
            List<Change> changes) {
        StringBuilder detail =
                new StringBuilder(fieldPath(base, baseMessage, baseField))
                        .append(": ")
                        .append(baseField.number())
                        .append(" -> ")
                        .append(headField.number());
        List<String> impacts = new ArrayList<>(impacts("wire"));
        if (!headMessage.reserved().reservesNumber(baseField.number())) {
            impacts.add("reuse-risk");
            detail.append("; ").append(baseField.number()).append(" is not reserved");
        }
        add(
                changes,
                "FIELD_NUMBER_CHANGED",
                impacts,
                detail.toString(),
                headField.line(),
                baseField.line());
        compareCommonField(base, head, baseMessage, headMessage, baseField, headField, changes);
    }

    private static void compareCommonField(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            ProtoSchema.Field baseField,
            ProtoSchema.Field headField,
            List<Change> changes) {
        String path = fieldPath(head, headMessage, headField);
        if (baseField.name().equals(headField.name())
                && !Objects.equals(jsonName(baseField), jsonName(headField))) {
            add(
                    changes,
                    "FIELD_SAME_JSON_NAME",
                    impacts("json"),
                    path + " (" + headField.number() + "): json_name changed",
                    headField.line(),
                    baseField.line());
        }
        if (sameCardinality(baseField, headField) && !sameType(baseField, headField)) {
            compareTypeChange(base, head, baseMessage, headMessage, baseField, headField, changes);
        } else if (!baseField.isMap()
                && !headField.isMap()
                && !sameTypeName(baseField.type(), headField.type())) {
            // A cardinality change does not make a type change safe, so check both.
            compareTypeChange(base, head, baseMessage, headMessage, baseField, headField, changes);
        }
        if (!sameCardinality(baseField, headField)) {
            compareCardinalityChange(
                    base, head, baseMessage, headMessage, baseField, headField, changes);
        }
        ProtoSchema.Presence basePresence = base.effectivePresence(baseMessage, baseField);
        ProtoSchema.Presence headPresence = head.effectivePresence(headMessage, headField);
        if (basePresence != headPresence
                && sameType(baseField, headField)
                && sameCardinality(baseField, headField)) {
            if (basePresence == ProtoSchema.Presence.LEGACY_REQUIRED
                    || headPresence == ProtoSchema.Presence.LEGACY_REQUIRED) {
                add(
                        changes,
                        "MESSAGE_SAME_REQUIRED_FIELDS",
                        impacts("wire", "json", "source"),
                        path
                                + " "
                                + (headPresence == ProtoSchema.Presence.LEGACY_REQUIRED
                                        ? "became required"
                                        : "no longer required"),
                        headField.line(),
                        baseField.line());
            } else {
                add(
                        changes,
                        "FIELD_SAME_PRESENCE",
                        impacts("json", "source"),
                        path
                                + " ("
                                + headField.number()
                                + "): "
                                + presenceWord(basePresence)
                                + " -> "
                                + presenceWord(headPresence)
                                + " presence",
                        headField.line(),
                        baseField.line());
            }
        }
        compareOneofMembership(base, baseMessage, headMessage, baseField, headField, changes);
        if (!Objects.equals(
                baseField.options().defaultValue(), headField.options().defaultValue())) {
            add(
                    changes,
                    "FIELD_SAME_DEFAULT",
                    impacts("semantic"),
                    path + " (" + headField.number() + "): default changed",
                    headField.line(),
                    baseField.line());
        }
        compareOptionIdentifier(
                "FIELD_SAME_JSTYPE",
                baseField.options().jstype(),
                headField.options().jstype(),
                "jstype",
                path,
                headField,
                baseField,
                changes);
        compareOptionIdentifier(
                "FIELD_SAME_CTYPE",
                baseField.options().ctype(),
                headField.options().ctype(),
                "ctype",
                path,
                headField,
                baseField,
                changes);
        compareFeatureMaps(
                "FIELD_SAME_FEATURES",
                path,
                filteredFieldFeatures(baseField.options().features()),
                filteredFieldFeatures(headField.options().features()),
                headField.line(),
                baseField.line(),
                changes);
        compareFieldBehaviors(path, baseField, headField, changes);
        if (!Objects.equals(baseField.options().validations(), headField.options().validations())) {
            add(
                    changes,
                    "FIELD_VALIDATION_CHANGED",
                    impacts("semantic"),
                    path + " (" + headField.number() + "): validation rules changed",
                    headField.line(),
                    baseField.line());
        }
    }

    private static void compareTypeChange(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            ProtoSchema.Field baseField,
            ProtoSchema.Field headField,
            List<Change> changes) {
        List<TypePair> pairs =
                baseField.isMap() && headField.isMap()
                        ? List.of(
                                new TypePair(baseField.mapKeyType(), headField.mapKeyType()),
                                new TypePair(baseField.mapValueType(), headField.mapValueType()))
                        : List.of(new TypePair(baseField.type(), headField.type()));
        TypeCompatibility compatibility = TypeCompatibility.SAME;
        boolean truncates = false;
        for (TypePair pair : pairs) {
            TypeCompatibility pairCompatibility = typeCompatibility(pair, base, head);
            if (pairCompatibility.compareTo(compatibility) > 0) {
                compatibility = pairCompatibility;
            }
            truncates |= pairCompatibility == TypeCompatibility.WIRE && pair.narrowsVarint();
        }
        String detail =
                fieldPath(head, headMessage, headField)
                        + " ("
                        + headField.number()
                        + "): "
                        + renderType(baseField)
                        + " -> "
                        + renderType(headField);
        switch (compatibility) {
            case SAME -> {}
            case SAME_SHAPE ->
                    add(
                            changes,
                            "FIELD_SAME_TYPE",
                            impacts("source"),
                            detail,
                            headField.line(),
                            baseField.line());
            case WIRE_JSON ->
                    add(
                            changes,
                            "FIELD_WIRE_JSON_COMPATIBLE_TYPE",
                            impacts("source"),
                            detail + " (values outside the shared range are reinterpreted)",
                            headField.line(),
                            baseField.line());
            case WIRE ->
                    add(
                            changes,
                            "FIELD_WIRE_COMPATIBLE_TYPE",
                            impacts("json", "source"),
                            truncates ? detail + " (out-of-range values are truncated)" : detail,
                            headField.line(),
                            baseField.line());
            case NONE ->
                    add(
                            changes,
                            "FIELD_SAME_TYPE",
                            impacts("wire", "json", "source"),
                            detail,
                            headField.line(),
                            baseField.line());
        }
    }

    /** Ordered from no change to an incompatible one, so a map takes its worse half. */
    private enum TypeCompatibility {
        SAME,
        SAME_SHAPE,
        WIRE_JSON,
        WIRE,
        NONE
    }

    private record TypePair(String base, String head) {
        String baseLast() {
            return ProtoSchema.lastComponent(base);
        }

        String headLast() {
            return ProtoSchema.lastComponent(head);
        }

        boolean narrowsVarint() {
            return VARINT_INTEGERS.contains(baseLast())
                    && VARINT_INTEGERS.contains(headLast())
                    && !baseLast().equals(headLast());
        }
    }

    private static TypeCompatibility typeCompatibility(
            TypePair pair, ProtoSchema base, ProtoSchema head) {
        String oldType = pair.baseLast();
        String newType = pair.headLast();
        if (oldType.equals(newType)) {
            return TypeCompatibility.SAME;
        }
        if (newType.equals(WIRE_JSON_PEERS.get(oldType))) {
            return TypeCompatibility.WIRE_JSON;
        }
        if ((VARINT_INTEGERS.contains(oldType) && VARINT_INTEGERS.contains(newType))
                || Set.of(oldType, newType).equals(Set.of("sint32", "sint64"))
                || ("string".equals(oldType) && "bytes".equals(newType))
                || (base.isEnumType(pair.base()) && ENUM_COMPATIBLE_INTEGERS.contains(newType))
                || (head.isEnumType(pair.head()) && ENUM_COMPATIBLE_INTEGERS.contains(oldType))) {
            return TypeCompatibility.WIRE;
        }
        Optional<ProtoSchema.Message> oldMessage = messageNamed(base, pair.base());
        Optional<ProtoSchema.Message> newMessage = messageNamed(head, pair.head());
        if (oldMessage.isPresent()
                && newMessage.isPresent()
                && messageShape(oldMessage.get()).equals(messageShape(newMessage.get()))) {
            return TypeCompatibility.SAME_SHAPE;
        }
        return TypeCompatibility.NONE;
    }

    private static void compareCardinalityChange(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            ProtoSchema.Field baseField,
            ProtoSchema.Field headField,
            List<Change> changes) {
        String detail =
                fieldPath(head, headMessage, headField)
                        + " ("
                        + headField.number()
                        + "): "
                        + baseField.cardinality()
                        + " -> "
                        + headField.cardinality();
        boolean compatible = false;
        if (baseField.isMap() != headField.isMap()) {
            ProtoSchema.Field mapField = baseField.isMap() ? baseField : headField;
            ProtoSchema.Field other = baseField.isMap() ? headField : baseField;
            ProtoSchema otherSchema = baseField.isMap() ? head : base;
            compatible = other.isRepeated() && isMapEntryShape(otherSchema, other.type(), mapField);
        } else if (baseField.isRepeated() != headField.isRepeated()) {
            String type = (baseField.isRepeated() ? baseField : headField).type();
            compatible =
                    "string".equals(ProtoSchema.lastComponent(type))
                            || "bytes".equals(ProtoSchema.lastComponent(type))
                            || base.isMessageType(type)
                            || head.isMessageType(type)
                            || base.effectiveRepeatedEncoding(baseMessage, baseField)
                                    == ProtoSchema.RepeatedEncoding.EXPANDED
                            || head.effectiveRepeatedEncoding(headMessage, headField)
                                    == ProtoSchema.RepeatedEncoding.EXPANDED;
        }
        if (compatible) {
            if (baseField.isRepeated() && !headField.isRepeated()) {
                detail += " (" + collapseNote(base, head, baseField.type()) + ")";
            }
            add(
                    changes,
                    "FIELD_WIRE_COMPATIBLE_CARDINALITY",
                    impacts("json", "source"),
                    detail,
                    headField.line(),
                    baseField.line());
        } else {
            add(
                    changes,
                    "FIELD_SAME_CARDINALITY",
                    impacts("wire", "json", "source"),
                    detail,
                    headField.line(),
                    baseField.line());
        }
    }

    private static void compareDeletedField(
            ProtoSchema base,
            ProtoSchema.Message headMessage,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Field baseField,
            List<Change> changes) {
        boolean numberReserved = headMessage.reserved().reservesNumber(baseField.number());
        boolean nameReserved = headMessage.reserved().reservesName(baseField.name());
        List<String> impacts = new ArrayList<>(impacts("json", "source"));
        if (!numberReserved) {
            impacts.add("reuse-risk");
        }
        String rule =
                !numberReserved
                        ? "FIELD_NO_DELETE_UNLESS_NUMBER_RESERVED"
                        : !nameReserved
                                ? "FIELD_NO_DELETE_UNLESS_NAME_RESERVED"
                                : "FIELD_DELETED_RESERVED";
        String detail =
                fieldPath(base, baseMessage, baseField)
                        + " ("
                        + baseField.number()
                        + ", "
                        + renderType(baseField)
                        + ") removed; "
                        + reservationDetail(
                                baseField.name(), baseField.number(), numberReserved, nameReserved);
        add(changes, rule, impacts, detail, 0, baseField.line());
        if (base.effectivePresence(baseMessage, baseField)
                == ProtoSchema.Presence.LEGACY_REQUIRED) {
            add(
                    changes,
                    "MESSAGE_SAME_REQUIRED_FIELDS",
                    impacts("wire", "json", "source"),
                    fieldPath(base, baseMessage, baseField) + " required field removed",
                    0,
                    baseField.line());
        }
    }

    private static void compareEnums(ProtoSchema base, ProtoSchema head, List<Change> changes) {
        TypeChanges typeChanges = typeChanges(base, head);
        for (String removed : typeChanges.removedEnums()) {
            ProtoSchema.EnumType enumType = base.enums().get(removed);
            add(
                    changes,
                    "ENUM_NO_DELETE",
                    impacts("json", "source"),
                    base.relativeName(removed) + " removed",
                    0,
                    enumType.line());
        }
        for (String added : typeChanges.addedEnums()) {
            ProtoSchema.EnumType enumType = head.enums().get(added);
            checkNewEnumDefault(head, enumType, changes);
        }
        for (String enumName : intersection(base.enums().keySet(), head.enums().keySet())) {
            ProtoSchema.EnumType baseEnum = base.enums().get(enumName);
            ProtoSchema.EnumType headEnum = head.enums().get(enumName);
            compareReserved(
                    "RESERVED_ENUM_NO_DELETE",
                    base.relativeName(enumName),
                    baseEnum.reserved(),
                    headEnum.reserved(),
                    headEnum.line(),
                    baseEnum.line(),
                    2147483647,
                    changes);
            compareEnumValues(base, head, baseEnum, headEnum, changes);
            ProtoSchema.EnumOpenness baseOpen = base.effectiveEnumOpenness(baseEnum);
            ProtoSchema.EnumOpenness headOpen = head.effectiveEnumOpenness(headEnum);
            if (baseOpen != headOpen) {
                add(
                        changes,
                        "ENUM_SAME_TYPE",
                        impacts("semantic"),
                        base.relativeName(enumName)
                                + ": "
                                + baseOpen.name().toLowerCase(Locale.ROOT)
                                + " -> "
                                + headOpen.name().toLowerCase(Locale.ROOT),
                        headEnum.line(),
                        baseEnum.line());
            }
        }
    }

    private static void compareEnumValues(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.EnumType baseEnum,
            ProtoSchema.EnumType headEnum,
            List<Change> changes) {
        Set<ProtoSchema.EnumValue> pairedBase = new LinkedHashSet<>();
        Set<ProtoSchema.EnumValue> pairedHead = new LinkedHashSet<>();
        for (ProtoSchema.EnumValue baseValue : baseEnum.values()) {
            headEnum.values().stream()
                    .filter(headValue -> headValue.number() == baseValue.number())
                    .findFirst()
                    .ifPresent(
                            headValue -> {
                                pairedBase.add(baseValue);
                                pairedHead.add(headValue);
                                if (!baseValue.name().equals(headValue.name())) {
                                    String rule =
                                            baseValue.number() == 0
                                                            || firstValueChanged(
                                                                    base, baseEnum, baseValue)
                                                    ? "ENUM_ZERO_VALUE_CHANGED"
                                                    : "ENUM_VALUE_SAME_NAME";
                                    add(
                                            changes,
                                            rule,
                                            rule.equals("ENUM_ZERO_VALUE_CHANGED")
                                                    ? impacts("json", "source", "semantic")
                                                    : impacts("json", "source"),
                                            base.relativeName(baseEnum.fqn())
                                                    + " "
                                                    + baseValue.number()
                                                    + ": "
                                                    + baseValue.name()
                                                    + " -> "
                                                    + headValue.name(),
                                            headValue.line(),
                                            baseValue.line());
                                }
                            });
        }
        for (ProtoSchema.EnumValue baseValue : baseEnum.values()) {
            if (pairedBase.contains(baseValue)) {
                continue;
            }
            headEnum.values().stream()
                    .filter(headValue -> headValue.name().equals(baseValue.name()))
                    .filter(headValue -> !pairedHead.contains(headValue))
                    .findFirst()
                    .ifPresent(
                            headValue -> {
                                pairedBase.add(baseValue);
                                pairedHead.add(headValue);
                                add(
                                        changes,
                                        "ENUM_VALUE_NUMBER_CHANGED",
                                        impacts("wire"),
                                        base.relativeName(baseEnum.fqn())
                                                + "."
                                                + baseValue.name()
                                                + ": "
                                                + baseValue.number()
                                                + " -> "
                                                + headValue.number(),
                                        headValue.line(),
                                        baseValue.line());
                            });
        }
        for (ProtoSchema.EnumValue baseValue : baseEnum.values()) {
            if (!pairedBase.contains(baseValue)) {
                boolean numberReserved = headEnum.reserved().reservesNumber(baseValue.number());
                boolean nameReserved = headEnum.reserved().reservesName(baseValue.name());
                List<String> impacts = new ArrayList<>(impacts("json", "source"));
                if (!numberReserved) {
                    impacts.add("reuse-risk");
                }
                String rule =
                        !numberReserved
                                ? "ENUM_VALUE_NO_DELETE_UNLESS_NUMBER_RESERVED"
                                : !nameReserved
                                        ? "ENUM_VALUE_NO_DELETE_UNLESS_NAME_RESERVED"
                                        : "ENUM_VALUE_DELETED_RESERVED";
                add(
                        changes,
                        rule,
                        impacts,
                        base.relativeName(baseEnum.fqn())
                                + "."
                                + baseValue.name()
                                + " ("
                                + baseValue.number()
                                + ") removed; "
                                + reservationDetail(
                                        baseValue.name(),
                                        baseValue.number(),
                                        numberReserved,
                                        nameReserved),
                        0,
                        baseValue.line());
            }
        }
        if (head.effectiveEnumOpenness(headEnum) == ProtoSchema.EnumOpenness.CLOSED) {
            for (ProtoSchema.EnumValue headValue : headEnum.values()) {
                if (!pairedHead.contains(headValue)) {
                    add(
                            changes,
                            "ENUM_VALUE_ADDED_TO_CLOSED_ENUM",
                            impacts("semantic"),
                            head.relativeName(headEnum.fqn())
                                    + "."
                                    + headValue.name()
                                    + " added to closed enum",
                            headValue.line(),
                            0);
                }
            }
        }
    }

    private static void compareServices(ProtoSchema base, ProtoSchema head, List<Change> changes) {
        TypeChanges typeChanges = typeChanges(base, head);
        for (String removed : typeChanges.removedServices()) {
            ProtoSchema.Service service = base.services().get(removed);
            add(
                    changes,
                    "SERVICE_NO_DELETE",
                    impacts("wire", "json", "source"),
                    base.relativeName(removed) + " removed",
                    0,
                    service.line());
        }
        for (String serviceName :
                intersection(base.services().keySet(), head.services().keySet())) {
            ProtoSchema.Service baseService = base.services().get(serviceName);
            ProtoSchema.Service headService = head.services().get(serviceName);
            for (ProtoSchema.Rpc baseRpc : baseService.rpcs()) {
                ProtoSchema.Rpc headRpc = headService.rpcByName(baseRpc.name()).orElse(null);
                if (headRpc == null) {
                    add(
                            changes,
                            "RPC_NO_DELETE",
                            impacts("wire", "json", "source"),
                            base.relativeName(baseService.fqn())
                                    + "."
                                    + baseRpc.name()
                                    + " removed",
                            0,
                            baseRpc.line());
                    continue;
                }
                compareRpc(base, head, baseService, baseRpc, headRpc, changes);
            }
        }
    }

    private static void compareRpc(
            ProtoSchema base,
            ProtoSchema head,
            ProtoSchema.Service baseService,
            ProtoSchema.Rpc baseRpc,
            ProtoSchema.Rpc headRpc,
            List<Change> changes) {
        String path = head.relativeName(baseService.fqn()) + "." + headRpc.name();
        if (!sameTypeName(baseRpc.inputType(), headRpc.inputType())) {
            add(
                    changes,
                    "RPC_SAME_REQUEST_TYPE",
                    impacts("wire", "json", "source"),
                    path
                            + ": "
                            + safeType(baseRpc.inputType())
                            + " -> "
                            + safeType(headRpc.inputType()),
                    headRpc.line(),
                    baseRpc.line());
        }
        if (!sameTypeName(baseRpc.outputType(), headRpc.outputType())) {
            add(
                    changes,
                    "RPC_SAME_RESPONSE_TYPE",
                    impacts("wire", "json", "source"),
                    path
                            + ": "
                            + safeType(baseRpc.outputType())
                            + " -> "
                            + safeType(headRpc.outputType()),
                    headRpc.line(),
                    baseRpc.line());
        }
        if (baseRpc.clientStreaming() != headRpc.clientStreaming()) {
            add(
                    changes,
                    "RPC_SAME_CLIENT_STREAMING",
                    impacts("wire", "json", "source"),
                    path + ": client streaming changed",
                    headRpc.line(),
                    baseRpc.line());
        }
        if (baseRpc.serverStreaming() != headRpc.serverStreaming()) {
            add(
                    changes,
                    "RPC_SAME_SERVER_STREAMING",
                    impacts("wire", "json", "source"),
                    path + ": server streaming changed",
                    headRpc.line(),
                    baseRpc.line());
        }
        if (!Objects.equals(baseRpc.idempotencyLevel(), headRpc.idempotencyLevel())) {
            add(
                    changes,
                    "RPC_SAME_IDEMPOTENCY_LEVEL",
                    impacts("semantic"),
                    path + ": idempotency_level changed",
                    headRpc.line(),
                    baseRpc.line());
        }
        if (!Objects.equals(baseRpc.httpOption(), headRpc.httpOption())) {
            add(
                    changes,
                    "RPC_SAME_HTTP_RULE",
                    impacts("json"),
                    path + ": google.api.http changed",
                    headRpc.line(),
                    baseRpc.line());
        }
    }

    /**
     * Matches oneofs by the fields they kept rather than by name, so a rename survives a member
     * being added or removed in the same change.
     */
    private static void compareOneofs(
            ProtoSchema schema,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            List<Change> changes) {
        Map<String, Set<Integer>> headOneofs = oneofNumbers(headMessage);
        for (Map.Entry<String, Set<Integer>> baseEntry : oneofNumbers(baseMessage).entrySet()) {
            for (Map.Entry<String, Set<Integer>> headEntry : headOneofs.entrySet()) {
                if (!headEntry.getKey().equals(baseEntry.getKey())
                        && sameOneofGroup(
                                baseMessage,
                                headMessage,
                                baseEntry.getValue(),
                                headEntry.getValue())) {
                    add(
                            changes,
                            "ONEOF_RENAMED",
                            impacts("source"),
                            schema.relativeName(baseMessage.fqn())
                                    + "."
                                    + baseEntry.getKey()
                                    + " -> "
                                    + headEntry.getKey(),
                            headMessage.line(),
                            baseMessage.line());
                }
            }
        }
    }

    private static boolean sameOneofGroup(
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            Set<Integer> baseNumbers,
            Set<Integer> headNumbers) {
        Set<Integer> kept = new LinkedHashSet<>();
        for (Integer number : baseNumbers) {
            if (headMessage.fieldByNumber(number).isPresent()) {
                kept.add(number);
            }
        }
        Set<Integer> existing = new LinkedHashSet<>();
        for (Integer number : headNumbers) {
            if (baseMessage.fieldByNumber(number).isPresent()) {
                existing.add(number);
            }
        }
        return !kept.isEmpty() && kept.equals(existing);
    }

    private static void compareOneofMembership(
            ProtoSchema schema,
            ProtoSchema.Message baseMessage,
            ProtoSchema.Message headMessage,
            ProtoSchema.Field baseField,
            ProtoSchema.Field headField,
            List<Change> changes) {
        if (Objects.equals(baseField.oneof(), headField.oneof())) {
            return;
        }
        if (baseField.oneof() != null
                && headField.oneof() != null
                && sameOneofGroup(
                        baseMessage,
                        headMessage,
                        oneofNumbers(baseMessage).get(baseField.oneof()),
                        oneofNumbers(headMessage).get(headField.oneof()))) {
            return;
        }
        List<String> impacts = new ArrayList<>(impacts("wire", "json", "source"));
        String detail;
        if (baseField.oneof() == null && headField.oneof() != null) {
            long existingMembers =
                    headMessage.fields().stream()
                            .filter(field -> headField.oneof().equals(field.oneof()))
                            .filter(field -> baseMessage.fieldByName(field.name()).isPresent())
                            .count();
            boolean newOneof = !baseMessage.oneofs().contains(headField.oneof());
            if (newOneof && existingMembers == 1) {
                impacts = new ArrayList<>(impacts("source"));
            }
            detail =
                    fieldPath(schema, headMessage, headField)
                            + " ("
                            + headField.number()
                            + "): moved into new oneof "
                            + headField.oneof();
        } else if (baseField.oneof() != null && headField.oneof() == null) {
            detail =
                    fieldPath(schema, headMessage, headField)
                            + " ("
                            + headField.number()
                            + "): moved out of oneof "
                            + baseField.oneof();
        } else {
            detail =
                    fieldPath(schema, headMessage, headField)
                            + " ("
                            + headField.number()
                            + "): oneof "
                            + baseField.oneof()
                            + " -> "
                            + headField.oneof();
        }
        add(changes, "FIELD_SAME_ONEOF", impacts, detail, headField.line(), baseField.line());
    }

    private static Map<String, Set<Integer>> oneofNumbers(ProtoSchema.Message message) {
        Map<String, Set<Integer>> byOneof = new LinkedHashMap<>();
        for (ProtoSchema.Field field : message.fields()) {
            if (field.oneof() != null) {
                byOneof.computeIfAbsent(field.oneof(), ignored -> new LinkedHashSet<>())
                        .add(field.number());
            }
        }
        return byOneof;
    }

    private static void compareFieldBehaviors(
            String path,
            ProtoSchema.Field baseField,
            ProtoSchema.Field headField,
            List<Change> changes) {
        for (String behavior : TIGHTENING_BEHAVIORS) {
            if (!baseField.options().fieldBehaviors().contains(behavior)
                    && headField.options().fieldBehaviors().contains(behavior)) {
                add(
                        changes,
                        "FIELD_BEHAVIOR_TIGHTENED",
                        impacts("semantic"),
                        path
                                + " ("
                                + headField.number()
                                + "): field_behavior "
                                + behavior
                                + " added",
                        headField.line(),
                        baseField.line());
            }
        }
        if (baseField.options().fieldBehaviors().contains("IDENTIFIER")
                && !headField.options().fieldBehaviors().contains("IDENTIFIER")) {
            add(
                    changes,
                    "FIELD_BEHAVIOR_TIGHTENED",
                    impacts("semantic"),
                    path + " (" + headField.number() + "): field_behavior IDENTIFIER removed",
                    headField.line(),
                    baseField.line());
        }
    }

    private static void compareOptionIdentifier(
            String rule,
            String baseValue,
            String headValue,
            String optionName,
            String path,
            ProtoSchema.Field headField,
            ProtoSchema.Field baseField,
            List<Change> changes) {
        if (!Objects.equals(baseValue, headValue)) {
            add(
                    changes,
                    rule,
                    impacts("source"),
                    path + " (" + headField.number() + "): " + optionName + " changed",
                    headField.line(),
                    baseField.line());
        }
    }

    private static void compareFeatureMaps(
            String rule,
            String element,
            Map<String, String> base,
            Map<String, String> head,
            int headLine,
            int baseLine,
            List<Change> changes) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(base.keySet());
        names.addAll(head.keySet());
        for (String name : names) {
            if (!Objects.equals(base.get(name), head.get(name))) {
                add(
                        changes,
                        rule,
                        impacts("semantic"),
                        element
                                + ": features."
                                + name
                                + " "
                                + safeIdentifier(base.get(name))
                                + " -> "
                                + safeIdentifier(head.get(name)),
                        headLine,
                        baseLine);
            }
        }
    }

    private static Map<String, String> filteredFieldFeatures(Map<String, String> features) {
        Map<String, String> filtered = new LinkedHashMap<>(features);
        filtered.remove("field_presence");
        filtered.remove("repeated_field_encoding");
        return filtered;
    }

    private static void compareReserved(
            String rule,
            String element,
            ProtoSchema.Reserved base,
            ProtoSchema.Reserved head,
            int headLine,
            int baseLine,
            int max,
            List<Change> changes) {
        List<ProtoSchema.Range> removedRanges = subtractRanges(base.ranges(), head.ranges());
        Set<String> removedNames = removed(base.names(), head.names());
        if (removedRanges.isEmpty() && removedNames.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (!removedRanges.isEmpty()) {
            parts.add(renderRanges(removedRanges, max));
        }
        List<String> safeNames =
                removedNames.stream().filter(ProtoSchema::isIdentifier).sorted().toList();
        long unsafeCount = removedNames.size() - safeNames.size();
        if (!safeNames.isEmpty()) {
            parts.add(String.join(", ", safeNames));
        }
        if (unsafeCount > 0) {
            parts.add(unsafeCount + (unsafeCount == 1 ? " name" : " names"));
        }
        add(
                changes,
                rule,
                impacts("reuse-risk"),
                element + ": reserved " + String.join(", ", parts) + " removed",
                headLine,
                baseLine);
    }

    private static void checkNewEnumDefault(
            ProtoSchema schema, ProtoSchema.EnumType enumType, List<Change> changes) {
        if (enumType.values().isEmpty()) {
            return;
        }
        ProtoSchema.EnumValue defaultValue =
                schema.effectiveEnumOpenness(enumType) == ProtoSchema.EnumOpenness.OPEN
                        ? enumType.values().stream()
                                .filter(value -> value.number() == 0)
                                .findFirst()
                                .orElse(enumType.values().get(0))
                        : enumType.values().get(0);
        String name = defaultValue.name();
        if (!(name.endsWith("_UNSPECIFIED")
                || "UNKNOWN".equals(name)
                || name.endsWith("_UNKNOWN"))) {
            add(
                    changes,
                    "ENUM_ZERO_VALUE_NOT_UNSPECIFIED",
                    impacts("semantic"),
                    schema.relativeName(enumType.fqn())
                            + ": default value "
                            + name
                            + " is meaningful; unset fields read as "
                            + name,
                    defaultValue.line(),
                    0);
        }
    }

    private static boolean firstValueChanged(
            ProtoSchema schema, ProtoSchema.EnumType enumType, ProtoSchema.EnumValue value) {
        return schema.effectiveEnumOpenness(enumType) == ProtoSchema.EnumOpenness.CLOSED
                && !enumType.values().isEmpty()
                && enumType.values().get(0).equals(value);
    }

    private static List<ProtoSchema.Range> subtractRanges(
            List<ProtoSchema.Range> base, List<ProtoSchema.Range> head) {
        List<ProtoSchema.Range> remaining = new ArrayList<>(base);
        for (ProtoSchema.Range remove : head) {
            List<ProtoSchema.Range> next = new ArrayList<>();
            for (ProtoSchema.Range range : remaining) {
                if (remove.end() < range.start() || remove.start() > range.end()) {
                    next.add(range);
                } else {
                    if (range.start() < remove.start()) {
                        next.add(new ProtoSchema.Range(range.start(), remove.start() - 1));
                    }
                    if (range.end() > remove.end()) {
                        next.add(new ProtoSchema.Range(remove.end() + 1, range.end()));
                    }
                }
            }
            remaining = next;
        }
        return remaining;
    }

    private static String renderRanges(List<ProtoSchema.Range> ranges, int max) {
        return ranges.stream()
                .sorted(Comparator.comparingInt(ProtoSchema.Range::start))
                .map(range -> renderRange(range, max))
                .toList()
                .stream()
                .reduce((first, second) -> first + ", " + second)
                .orElse("");
    }

    private static String renderRange(ProtoSchema.Range range, int max) {
        if (range.start() == range.end()) {
            return Integer.toString(range.start());
        }
        return range.start()
                + " to "
                + (range.end() == max ? "max" : Integer.toString(range.end()));
    }

    private static String reservationDetail(
            String name, int number, boolean numberReserved, boolean nameReserved) {
        if (numberReserved && nameReserved) {
            return "number " + number + " and name " + name + " reserved";
        }
        if (numberReserved) {
            return "number " + number + " reserved and name " + name + " not reserved";
        }
        if (nameReserved) {
            return "number " + number + " not reserved and name " + name + " reserved";
        }
        return "number " + number + " and name " + name + " not reserved";
    }

    private static boolean sameCardinality(
            ProtoSchema.Field baseField, ProtoSchema.Field headField) {
        return baseField.cardinality().equals(headField.cardinality());
    }

    private static boolean sameType(ProtoSchema.Field baseField, ProtoSchema.Field headField) {
        if (baseField.isMap() || headField.isMap()) {
            return baseField.isMap()
                    && headField.isMap()
                    && sameTypeName(baseField.mapKeyType(), headField.mapKeyType())
                    && sameTypeName(baseField.mapValueType(), headField.mapValueType());
        }
        return sameTypeName(baseField.type(), headField.type());
    }

    private static boolean sameTypeName(String baseType, String headType) {
        return ProtoSchema.lastComponent(baseType).equals(ProtoSchema.lastComponent(headType));
    }

    private static String renderType(ProtoSchema.Field field) {
        return field.isMap()
                ? "map<"
                        + safeType(field.mapKeyType())
                        + ", "
                        + safeType(field.mapValueType())
                        + ">"
                : safeType(field.type());
    }

    private static String jsonName(ProtoSchema.Field field) {
        if (field.options().jsonName() != null) {
            return field.options().jsonName();
        }
        StringBuilder builder = new StringBuilder();
        boolean upper = false;
        for (char c : field.name().toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                builder.append(Character.toUpperCase(c));
                upper = false;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String fieldPath(
            ProtoSchema schema, ProtoSchema.Message message, ProtoSchema.Field field) {
        return schema.relativeName(message.fqn()) + "." + field.name();
    }

    private static Optional<ProtoSchema.Message> messageNamed(ProtoSchema schema, String type) {
        String last = ProtoSchema.lastComponent(type);
        return schema.messages().values().stream()
                .filter(message -> ProtoSchema.lastComponent(message.fqn()).equals(last))
                .findFirst();
    }

    private static Set<String> messageShape(ProtoSchema.Message message) {
        Set<String> shape = new LinkedHashSet<>();
        for (ProtoSchema.Field field : message.fields()) {
            String type =
                    field.isMap()
                            ? "map<"
                                    + ProtoSchema.lastComponent(field.mapKeyType())
                                    + ","
                                    + ProtoSchema.lastComponent(field.mapValueType())
                                    + ">"
                            : ProtoSchema.lastComponent(field.type());
            shape.add(field.number() + ":" + field.name() + ":" + type + ":" + field.label());
        }
        return shape;
    }

    /**
     * A map is encoded as a repeated entry message with key field 1 and value field 2, so only a
     * repeated field of exactly that shape reads the same bytes.
     */
    private static boolean isMapEntryShape(
            ProtoSchema schema, String entryType, ProtoSchema.Field mapField) {
        return messageNamed(schema, entryType)
                .filter(entry -> entry.fields().size() == 2)
                .filter(
                        entry ->
                                isEntryField(entry, 1, mapField.mapKeyType())
                                        && isEntryField(entry, 2, mapField.mapValueType()))
                .isPresent();
    }

    private static boolean isEntryField(ProtoSchema.Message entry, int number, String type) {
        return entry.fields().stream()
                .anyMatch(
                        field ->
                                field.number() == number
                                        && !field.isRepeated()
                                        && sameTypeName(field.type(), type));
    }

    private static String collapseNote(ProtoSchema base, ProtoSchema head, String elementType) {
        if (base.isMessageType(elementType) || head.isMessageType(elementType)) {
            return "elements are merged into one message";
        }
        if (base.isScalar(elementType)
                || base.isEnumType(elementType)
                || head.isEnumType(elementType)) {
            return "only the last value is kept";
        }
        return "only the last value is kept, or elements are merged for a message type";
    }

    private static String presenceWord(ProtoSchema.Presence presence) {
        return presence.name().toLowerCase(Locale.ROOT);
    }

    private static List<String> impacts(String... impacts) {
        List<String> ordered = new ArrayList<>();
        for (String impact : IMPACT_ORDER) {
            for (String requested : impacts) {
                if (impact.equals(requested) && !ordered.contains(impact)) {
                    ordered.add(impact);
                }
            }
        }
        for (String impact : impacts) {
            if (!ordered.contains(impact)) {
                ordered.add(impact);
            }
        }
        return ordered;
    }

    private static void add(
            List<Change> changes,
            String rule,
            List<String> impacts,
            String detail,
            int headLine,
            int baseLine) {
        changes.add(new Change(rule, orderImpacts(impacts), detail, headLine, baseLine));
    }

    private static List<String> orderImpacts(List<String> impacts) {
        List<String> ordered = new ArrayList<>();
        for (String impact : IMPACT_ORDER) {
            if (impacts.contains(impact)) {
                ordered.add(impact);
            }
        }
        for (String impact : impacts) {
            if (!ordered.contains(impact)) {
                ordered.add(impact);
            }
        }
        return List.copyOf(ordered);
    }

    private static void appendStabilityTags(String packageName, List<Change> changes) {
        String last = ProtoSchema.lastComponent(packageName);
        String tag =
                ALPHA.matcher(last).matches()
                        ? "alpha"
                        : BETA.matcher(last).matches() ? "beta" : null;
        if (tag == null) {
            return;
        }
        for (int index = 0; index < changes.size(); index++) {
            Change change = changes.get(index);
            List<String> impacts = new ArrayList<>(change.impacts());
            if (!impacts.contains(tag)) {
                impacts.add(tag);
            }
            changes.set(
                    index,
                    new Change(
                            change.rule(),
                            List.copyOf(impacts),
                            change.detail(),
                            change.headLine(),
                            change.baseLine()));
        }
    }

    private static Set<String> intersection(Set<String> first, Set<String> second) {
        Set<String> values = new LinkedHashSet<>(first);
        values.retainAll(second);
        return values;
    }

    private static Set<String> removed(Set<String> base, Set<String> head) {
        Set<String> values = new LinkedHashSet<>(base);
        values.removeAll(head);
        return values;
    }

    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static String safeIdentifier(String value) {
        return ProtoSchema.isIdentifier(value) ? value : "unset";
    }

    private static String safeNumber(String value) {
        return value != null && value.matches("\\d+") ? value : "unset";
    }

    private static String safeDotted(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_.]*") ? value : "unset";
    }

    private static String safeType(String value) {
        return value != null && value.matches("\\.?[A-Za-z_][A-Za-z0-9_.]*") ? value : "unknown";
    }
}
