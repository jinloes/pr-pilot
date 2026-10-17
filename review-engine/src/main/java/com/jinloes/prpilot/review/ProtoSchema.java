package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * A structural model of one {@code .proto} file (proto2, proto3 or editions): its package, file
 * options, messages with their fields and reservations, enums and services, keyed by fully
 * qualified name. {@link #parse} is a strict tokenizer and recursive-descent parser that returns
 * empty for anything it does not understand, so callers report the file as unparsed rather than
 * comparing a half-read schema.
 */
final class ProtoSchema {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> SCALARS =
            Set.of(
                    "double",
                    "float",
                    "int32",
                    "int64",
                    "uint32",
                    "uint64",
                    "sint32",
                    "sint64",
                    "fixed32",
                    "fixed64",
                    "sfixed32",
                    "sfixed64",
                    "bool",
                    "string",
                    "bytes");
    private static final Set<String> NUMERIC_PACKABLE =
            Set.of(
                    "double",
                    "float",
                    "int32",
                    "int64",
                    "uint32",
                    "uint64",
                    "sint32",
                    "sint64",
                    "fixed32",
                    "fixed64",
                    "sfixed32",
                    "sfixed64",
                    "bool");
    private static final List<String> VALIDATION_OPTIONS =
            List.of("(buf.validate.field)", "(validate.rules)", "(buf.validate.predefined)");

    private final String syntax;
    private final String edition;
    private final String packageName;
    private final Map<String, String> fileOptions;
    private final Map<String, String> fileFeatures;
    private final Map<String, Message> messages;
    private final Map<String, EnumType> enums;
    private final Map<String, Service> services;
    private final int syntaxLine;
    private final int editionLine;
    private final int packageLine;

    private ProtoSchema(
            String syntax,
            String edition,
            String packageName,
            Map<String, String> fileOptions,
            Map<String, String> fileFeatures,
            Map<String, Message> messages,
            Map<String, EnumType> enums,
            Map<String, Service> services,
            int syntaxLine,
            int editionLine,
            int packageLine) {
        this.syntax = syntax;
        this.edition = edition;
        this.packageName = packageName;
        this.fileOptions = ordered(fileOptions);
        this.fileFeatures = ordered(fileFeatures);
        this.messages = ordered(messages);
        this.enums = ordered(enums);
        this.services = ordered(services);
        this.syntaxLine = syntaxLine;
        this.editionLine = editionLine;
        this.packageLine = packageLine;
    }

    static Optional<ProtoSchema> parse(String text) {
        try {
            Parser parser = new Parser(Tokenizer.tokenize(text == null ? "" : text));
            return Optional.of(parser.parse());
        } catch (ParseException exception) {
            return Optional.empty();
        }
    }

    String syntax() {
        return syntax;
    }

    String edition() {
        return edition;
    }

    String packageName() {
        return packageName;
    }

    Map<String, String> fileOptions() {
        return fileOptions;
    }

    Map<String, String> fileFeatures() {
        return fileFeatures;
    }

    Map<String, Message> messages() {
        return messages;
    }

    Map<String, EnumType> enums() {
        return enums;
    }

    Map<String, Service> services() {
        return services;
    }

    int syntaxLine() {
        return syntaxLine;
    }

    int editionLine() {
        return editionLine;
    }

    int packageLine() {
        return packageLine;
    }

    boolean isEditions() {
        return edition != null;
    }

    boolean isScalar(String type) {
        return SCALARS.contains(lastComponent(type));
    }

    boolean isMessageType(String type) {
        String last = lastComponent(type);
        return messages.keySet().stream().map(ProtoSchema::lastComponent).anyMatch(last::equals);
    }

    boolean isEnumType(String type) {
        String last = lastComponent(type);
        return enums.keySet().stream().map(ProtoSchema::lastComponent).anyMatch(last::equals);
    }

    Presence effectivePresence(Message message, Field field) {
        if (field.isRepeated() || field.isMap()) {
            return Presence.NONE;
        }
        String fieldPresence = field.options().features().get("field_presence");
        if (fieldPresence != null) {
            return presenceFromFeature(fieldPresence);
        }
        if ("required".equals(field.label())) {
            return Presence.LEGACY_REQUIRED;
        }
        String messagePresence = message.features().get("field_presence");
        if (messagePresence != null) {
            return presenceFromFeature(messagePresence);
        }
        String filePresence = fileFeatures.get("field_presence");
        if (filePresence != null) {
            return presenceFromFeature(filePresence);
        }
        if (isEditions()) {
            return Presence.EXPLICIT;
        }
        if ("proto2".equals(syntax)) {
            return Presence.EXPLICIT;
        }
        if ("optional".equals(field.label())
                || field.oneof() != null
                || isMessageType(field.type())) {
            return Presence.EXPLICIT;
        }
        return Presence.IMPLICIT;
    }

    EnumOpenness effectiveEnumOpenness(EnumType enumType) {
        String enumFeature = enumType.features().get("enum_type");
        if (enumFeature != null) {
            return enumOpennessFromFeature(enumFeature);
        }
        String fileFeature = fileFeatures.get("enum_type");
        if (fileFeature != null) {
            return enumOpennessFromFeature(fileFeature);
        }
        if ("proto2".equals(syntax)) {
            return EnumOpenness.CLOSED;
        }
        return EnumOpenness.OPEN;
    }

    RepeatedEncoding effectiveRepeatedEncoding(Message message, Field field) {
        if (!field.isRepeated() || field.isMap()) {
            return RepeatedEncoding.NONE;
        }
        if (field.options().packed() != null) {
            return field.options().packed() ? RepeatedEncoding.PACKED : RepeatedEncoding.EXPANDED;
        }
        String fieldEncoding = field.options().features().get("repeated_field_encoding");
        if (fieldEncoding != null) {
            return repeatedEncodingFromFeature(fieldEncoding);
        }
        String messageEncoding = message.features().get("repeated_field_encoding");
        if (messageEncoding != null) {
            return repeatedEncodingFromFeature(messageEncoding);
        }
        String fileEncoding = fileFeatures.get("repeated_field_encoding");
        if (fileEncoding != null) {
            return repeatedEncodingFromFeature(fileEncoding);
        }
        if ("proto2".equals(syntax)) {
            return RepeatedEncoding.EXPANDED;
        }
        return isPackable(field) ? RepeatedEncoding.PACKED : RepeatedEncoding.EXPANDED;
    }

    static String lastComponent(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.startsWith(".") ? type.substring(1) : type;
        int index = value.lastIndexOf('.');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    /** Immutable copy that keeps declaration order, so rendered findings are deterministic. */
    private static <K, V> Map<K, V> ordered(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static <T> Set<T> ordered(Set<T> set) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }

    String relativeName(String fqn) {
        if (StringUtils.isBlank(packageName)) {
            return fqn;
        }
        String prefix = packageName + ".";
        return fqn.startsWith(prefix) ? fqn.substring(prefix.length()) : fqn;
    }

    private static Presence presenceFromFeature(String feature) {
        if ("LEGACY_REQUIRED".equals(feature)) {
            return Presence.LEGACY_REQUIRED;
        }
        if ("IMPLICIT".equals(feature)) {
            return Presence.IMPLICIT;
        }
        return Presence.EXPLICIT;
    }

    private static EnumOpenness enumOpennessFromFeature(String feature) {
        return "CLOSED".equals(feature) ? EnumOpenness.CLOSED : EnumOpenness.OPEN;
    }

    private static RepeatedEncoding repeatedEncodingFromFeature(String feature) {
        return "EXPANDED".equals(feature) ? RepeatedEncoding.EXPANDED : RepeatedEncoding.PACKED;
    }

    private boolean isPackable(Field field) {
        String last = lastComponent(field.type());
        return NUMERIC_PACKABLE.contains(last) || isEnumType(field.type());
    }

    enum Presence {
        NONE,
        IMPLICIT,
        EXPLICIT,
        LEGACY_REQUIRED
    }

    enum EnumOpenness {
        OPEN,
        CLOSED
    }

    enum RepeatedEncoding {
        NONE,
        EXPANDED,
        PACKED
    }

    record Range(int start, int end) {
        boolean contains(int value) {
            return value >= start && value <= end;
        }
    }

    record Reserved(List<Range> ranges, Set<String> names) {
        boolean reservesNumber(int number) {
            return ranges.stream().anyMatch(range -> range.contains(number));
        }

        boolean reservesName(String name) {
            return names.contains(name);
        }
    }

    record Message(
            String fqn,
            String name,
            int line,
            List<Field> fields,
            Reserved reserved,
            Map<String, String> features,
            Set<String> oneofs) {
        Optional<Field> fieldByNumber(int number) {
            return fields.stream().filter(field -> field.number() == number).findFirst();
        }

        Optional<Field> fieldByName(String name) {
            return fields.stream().filter(field -> field.name().equals(name)).findFirst();
        }
    }

    record Field(
            String name,
            int number,
            String label,
            String type,
            String mapKeyType,
            String mapValueType,
            String oneof,
            int line,
            FieldOptions options) {
        boolean isMap() {
            return mapKeyType != null;
        }

        boolean isRepeated() {
            return "repeated".equals(label) || isMap();
        }

        String cardinality() {
            if (isMap()) {
                return "map";
            }
            return isRepeated() ? "repeated" : "singular";
        }
    }

    record FieldOptions(
            String jsonName,
            String defaultValue,
            Boolean deprecated,
            Boolean packed,
            String jstype,
            String ctype,
            Map<String, String> features,
            Set<String> fieldBehaviors,
            Map<String, String> validations) {}

    record EnumType(
            String fqn,
            String name,
            int line,
            List<EnumValue> values,
            Reserved reserved,
            boolean allowAlias,
            Map<String, String> features) {}

    record EnumValue(String name, int number, int line) {}

    record Service(String fqn, String name, int line, List<Rpc> rpcs) {
        Optional<Rpc> rpcByName(String name) {
            return rpcs.stream().filter(rpc -> rpc.name().equals(name)).findFirst();
        }
    }

    record Rpc(
            String name,
            String inputType,
            String outputType,
            boolean clientStreaming,
            boolean serverStreaming,
            String idempotencyLevel,
            String httpOption,
            int line) {}

    private enum TokenKind {
        IDENT,
        NUMBER,
        STRING,
        SYMBOL,
        EOF
    }

    private record Token(TokenKind kind, String text, String stringValue, int line) {}

    private static final class Tokenizer {
        private final String text;
        private final List<Token> tokens = new ArrayList<>();
        private int index;
        private int line = 1;

        private Tokenizer(String text) {
            this.text = text;
        }

        static List<Token> tokenize(String text) {
            Tokenizer tokenizer = new Tokenizer(text);
            tokenizer.scan();
            return tokenizer.tokens;
        }

        private void scan() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (Character.isWhitespace(c)) {
                    consumeWhitespace(c);
                } else if (c == '/' && peekChar(1) == '/') {
                    consumeLineComment();
                } else if (c == '/' && peekChar(1) == '*') {
                    consumeBlockComment();
                } else if (c == '"' || c == '\'') {
                    consumeString(c);
                } else if (isIdentifierStart(c)) {
                    consumeIdentifier();
                } else if (isNumberStart(c)) {
                    consumeNumber();
                } else {
                    tokens.add(new Token(TokenKind.SYMBOL, Character.toString(c), null, line));
                    index++;
                }
            }

            tokens.add(new Token(TokenKind.EOF, "", null, line));
        }

        private void consumeWhitespace(char c) {
            if (c == '\n') {
                line++;
            }
            index++;
        }

        private void consumeLineComment() {
            index += 2;
            while (index < text.length() && text.charAt(index) != '\n') {
                index++;
            }
        }

        private void consumeBlockComment() {
            index += 2;
            while (index + 1 < text.length()) {
                if (text.charAt(index) == '\n') {
                    line++;
                }
                if (text.charAt(index) == '*' && text.charAt(index + 1) == '/') {
                    index += 2;
                    return;
                }
                index++;
            }
            throw new ParseException();
        }

        private void consumeString(char quote) {
            int startLine = line;
            index++;
            StringBuilder decoded = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '\n') {
                    line++;
                }
                if (c == quote) {
                    tokens.add(new Token(TokenKind.STRING, "\"\"", decoded.toString(), startLine));
                    return;
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        throw new ParseException();
                    }
                    char escaped = text.charAt(index++);
                    if (escaped == '\n') {
                        line++;
                    }
                    decoded.append(escaped);
                } else {
                    decoded.append(c);
                }
            }
            throw new ParseException();
        }

        private void consumeIdentifier() {
            int start = index;
            int startLine = line;
            index++;
            while (index < text.length() && isIdentifierPart(text.charAt(index))) {
                index++;
            }
            tokens.add(new Token(TokenKind.IDENT, text.substring(start, index), null, startLine));
        }

        private void consumeNumber() {
            int start = index;
            int startLine = line;
            if ((text.charAt(index) == '+' || text.charAt(index) == '-')
                    && index + 1 < text.length()) {
                index++;
            }
            while (index < text.length()) {
                char c = text.charAt(index);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '+' || c == '-') {
                    index++;
                } else {
                    break;
                }
            }
            tokens.add(new Token(TokenKind.NUMBER, text.substring(start, index), null, startLine));
        }

        private char peekChar(int offset) {
            int target = index + offset;
            return target < text.length() ? text.charAt(target) : '\0';
        }

        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        private static boolean isNumberStart(char c) {
            return Character.isDigit(c) || c == '-' || c == '+';
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int index;
        private String syntax = "proto2";
        private String edition;
        private String packageName = "";
        private final Map<String, String> fileOptions = new LinkedHashMap<>();
        private final Map<String, String> fileFeatures = new LinkedHashMap<>();
        private final Map<String, Message> messages = new LinkedHashMap<>();
        private final Map<String, EnumType> enums = new LinkedHashMap<>();
        private final Map<String, Service> services = new LinkedHashMap<>();
        private int syntaxLine;
        private int editionLine;
        private int packageLine;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private ProtoSchema parse() {
            while (!isEof()) {
                if (accept(";")) {
                    continue;
                }
                if (accept("syntax")) {
                    syntaxLine = previous().line();
                    expect("=");
                    Token value = expectKind(TokenKind.STRING);
                    if (!"proto2".equals(value.stringValue())
                            && !"proto3".equals(value.stringValue())) {
                        throw new ParseException();
                    }
                    syntax = value.stringValue();
                    expect(";");
                } else if (accept("edition")) {
                    editionLine = previous().line();
                    expect("=");
                    edition = expectKind(TokenKind.STRING).stringValue();
                    syntax = "editions";
                    expect(";");
                } else if (accept("package")) {
                    packageLine = previous().line();
                    packageName = parseQualifiedName();
                    expect(";");
                } else if (accept("import")) {
                    skipImport();
                } else if (accept("option")) {
                    OptionAssignment option = parseOptionAssignment();
                    fileOptions.put(option.name(), option.value());
                    putFeature(fileFeatures, option.name(), option.valueIdentifier());
                    expect(";");
                } else if (isVisibility()) {
                    next();
                } else if (isDeclaration("message")) {
                    parseMessage(List.of());
                } else if (isDeclaration("enum")) {
                    parseEnum(List.of());
                } else if (isDeclaration("service")) {
                    parseService();
                } else if (isDeclaration("extend")) {
                    parseExtend();
                } else {
                    throw new ParseException();
                }
            }
            return new ProtoSchema(
                    syntax,
                    edition,
                    packageName,
                    fileOptions,
                    fileFeatures,
                    messages,
                    enums,
                    services,
                    syntaxLine,
                    editionLine,
                    packageLine);
        }

        private void parseMessage(List<String> parents) {
            Token keyword = expect("message");
            String name = expectIdentifierLike().text();
            List<String> scope = append(parents, name);
            String fqn = qualify(scope);
            expect("{");
            List<Field> fields = new ArrayList<>();
            List<Range> ranges = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            Map<String, String> features = new LinkedHashMap<>();
            Set<String> oneofs = new LinkedHashSet<>();
            while (!accept("}")) {
                if (isEof()) {
                    throw new ParseException();
                }
                if (accept(";")) {
                    continue;
                }
                if (accept("option")) {
                    OptionAssignment option = parseOptionAssignment();
                    putFeature(features, option.name(), option.valueIdentifier());
                    expect(";");
                } else if (accept("reserved")) {
                    parseReserved(ranges, names, 536870911);
                } else if (accept("extensions")) {
                    skipUntilStatementEnd();
                } else if (isVisibility()) {
                    next();
                } else if (isDeclaration("message")) {
                    parseMessage(scope);
                } else if (isDeclaration("enum")) {
                    parseEnum(scope);
                } else if (accept("oneof")) {
                    parseOneof(fields, oneofs);
                } else if (isDeclaration("extend")) {
                    parseExtend();
                } else {
                    fields.add(parseField(null));
                }
            }
            messages.put(
                    fqn,
                    new Message(
                            fqn,
                            name,
                            keyword.line(),
                            List.copyOf(fields),
                            new Reserved(List.copyOf(ranges), ordered(names)),
                            ordered(features),
                            ordered(oneofs)));
        }

        private void parseOneof(List<Field> fields, Set<String> oneofs) {
            String name = expectIdentifierLike().text();
            oneofs.add(name);
            expect("{");
            while (!accept("}")) {
                if (isEof()) {
                    throw new ParseException();
                }
                if (accept(";")) {
                    continue;
                }
                if (accept("option")) {
                    parseOptionAssignment();
                    expect(";");
                } else {
                    fields.add(parseField(name));
                }
            }
        }

        private Field parseField(String oneof) {
            String label = null;
            if (isLabel(peek().text()) && !"=".equals(peek(2).text())) {
                label = next().text();
            }
            if ("group".equals(peek().text()) && label != null) {
                return parseGroup(label, oneof);
            }
            String mapKey = null;
            String mapValue = null;
            String type;
            if (accept("map")) {
                expect("<");
                mapKey = parseTypeName();
                expect(",");
                mapValue = parseTypeName();
                expect(">");
                type = "map";
                label = "repeated";
            } else {
                type = parseTypeName();
            }
            Token name = expectIdentifierLike();
            expect("=");
            int number = parseSignedInt();
            FieldOptions options = accept("[") ? parseFieldOptions() : emptyFieldOptions();
            expect(";");
            return new Field(
                    name.text(),
                    number,
                    label,
                    type,
                    mapKey,
                    mapValue,
                    oneof,
                    name.line(),
                    options);
        }

        private Field parseGroup(String label, String oneof) {
            expect("group");
            Token name = expectIdentifierLike();
            expect("=");
            int number = parseSignedInt();
            FieldOptions options = accept("[") ? parseFieldOptions() : emptyFieldOptions();
            expect("{");
            int depth = 1;
            while (depth > 0) {
                if (isEof()) {
                    throw new ParseException();
                }
                if (accept("{")) {
                    depth++;
                } else if (accept("}")) {
                    depth--;
                } else {
                    next();
                }
            }
            return new Field(
                    lowerFirst(name.text()),
                    number,
                    label,
                    name.text(),
                    null,
                    null,
                    oneof,
                    name.line(),
                    options);
        }

        private void parseEnum(List<String> parents) {
            Token keyword = expect("enum");
            String name = expectIdentifierLike().text();
            String fqn = qualify(append(parents, name));
            expect("{");
            List<EnumValue> values = new ArrayList<>();
            List<Range> ranges = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            Map<String, String> features = new LinkedHashMap<>();
            boolean allowAlias = false;
            while (!accept("}")) {
                if (isEof()) {
                    throw new ParseException();
                }
                if (accept(";")) {
                    continue;
                }
                if (accept("option")) {
                    OptionAssignment option = parseOptionAssignment();
                    if ("allow_alias".equals(option.name())) {
                        allowAlias = "true".equals(option.valueIdentifier());
                    }
                    putFeature(features, option.name(), option.valueIdentifier());
                    expect(";");
                } else if (accept("reserved")) {
                    parseReserved(ranges, names, 2147483647);
                } else {
                    Token valueName = expectIdentifierLike();
                    expect("=");
                    int number = parseSignedInt();
                    if (accept("[")) {
                        skipBalanced('[', ']');
                    }
                    expect(";");
                    values.add(new EnumValue(valueName.text(), number, valueName.line()));
                }
            }
            enums.put(
                    fqn,
                    new EnumType(
                            fqn,
                            name,
                            keyword.line(),
                            List.copyOf(values),
                            new Reserved(List.copyOf(ranges), ordered(names)),
                            allowAlias,
                            ordered(features)));
        }

        private void parseService() {
            Token keyword = expect("service");
            String name = expectIdentifierLike().text();
            String fqn = qualify(List.of(name));
            expect("{");
            List<Rpc> rpcs = new ArrayList<>();
            while (!accept("}")) {
                if (isEof()) {
                    throw new ParseException();
                }
                if (accept(";")) {
                    continue;
                }
                if (accept("option")) {
                    parseOptionAssignment();
                    expect(";");
                } else if (accept("rpc")) {
                    rpcs.add(parseRpc());
                } else {
                    throw new ParseException();
                }
            }
            services.put(fqn, new Service(fqn, name, keyword.line(), List.copyOf(rpcs)));
        }

        private Rpc parseRpc() {
            Token name = expectIdentifierLike();
            expect("(");
            boolean clientStreaming = accept("stream");
            String input = parseTypeName();
            expect(")");
            expect("returns");
            expect("(");
            boolean serverStreaming = accept("stream");
            String output = parseTypeName();
            expect(")");
            String idempotency = null;
            String http = null;
            if (accept(";")) {
                return new Rpc(
                        name.text(),
                        input,
                        output,
                        clientStreaming,
                        serverStreaming,
                        null,
                        null,
                        name.line());
            }
            expect("{");
            while (!accept("}")) {
                if (accept(";")) {
                    continue;
                }
                expect("option");
                OptionAssignment option = parseOptionAssignment();
                if ("idempotency_level".equals(option.name())) {
                    idempotency = option.valueIdentifier();
                } else if ("(google.api.http)".equals(option.name())) {
                    http = option.value();
                }
                expect(";");
            }
            accept(";");
            return new Rpc(
                    name.text(),
                    input,
                    output,
                    clientStreaming,
                    serverStreaming,
                    idempotency,
                    http,
                    name.line());
        }

        private void parseExtend() {
            expect("extend");
            parseTypeName();
            expect("{");
            while (!accept("}")) {
                if (isEof()) {
                    throw new ParseException();
                }
                if (accept(";")) {
                    continue;
                }
                parseField(null);
            }
        }

        private void parseReserved(List<Range> ranges, Set<String> names, int max) {
            do {
                if (peek().kind() == TokenKind.STRING) {
                    names.add(next().stringValue());
                } else if (peek().kind() == TokenKind.IDENT && !"max".equals(peek().text())) {
                    if ("to".equals(peek(1).text())) {
                        int start = parseSignedInt();
                        expect("to");
                        int end = accept("max") ? max : parseSignedInt();
                        ranges.add(new Range(start, end));
                    } else {
                        names.add(next().text());
                    }
                } else {
                    int start = parseSignedInt();
                    int end = start;
                    if (accept("to")) {
                        end = accept("max") ? max : parseSignedInt();
                    }
                    ranges.add(new Range(start, end));
                }
            } while (accept(","));
            if (accept("[")) {
                skipBalanced('[', ']');
            }
            expect(";");
        }

        private FieldOptions parseFieldOptions() {
            String jsonName = null;
            String defaultValue = null;
            Boolean deprecated = null;
            Boolean packed = null;
            String jstype = null;
            String ctype = null;
            Map<String, String> features = new LinkedHashMap<>();
            Set<String> behaviors = new LinkedHashSet<>();
            Map<String, String> validations = new LinkedHashMap<>();
            while (!accept("]")) {
                OptionAssignment option = parseOptionAssignment();
                switch (option.name()) {
                    case "json_name" -> jsonName = option.stringValue();
                    case "default" -> defaultValue = option.value();
                    case "deprecated" ->
                            deprecated = Boolean.valueOf("true".equals(option.valueIdentifier()));
                    case "packed" ->
                            packed = Boolean.valueOf("true".equals(option.valueIdentifier()));
                    case "jstype" -> jstype = option.valueIdentifier();
                    case "ctype" -> ctype = option.valueIdentifier();
                    case "(google.api.field_behavior)" ->
                            behaviors.addAll(option.valueIdentifiers());
                    default -> {
                        if (isValidationOption(option.name())) {
                            validations.merge(
                                    option.name(), option.value(), (a, b) -> a + ", " + b);
                        } else {
                            putFeature(features, option.name(), option.valueIdentifier());
                        }
                    }
                }
                if (!accept(",")) {
                    expect("]");
                    break;
                }
            }
            return new FieldOptions(
                    jsonName,
                    defaultValue,
                    deprecated,
                    packed,
                    jstype,
                    ctype,
                    ordered(features),
                    ordered(behaviors),
                    ordered(validations));
        }

        private OptionAssignment parseOptionAssignment() {
            String name = parseOptionName();
            expect("=");
            List<Token> valueTokens = new ArrayList<>();
            int braceDepth = 0;
            int bracketDepth = 0;
            while (!isEof()) {
                if (braceDepth == 0
                        && bracketDepth == 0
                        && (";".equals(peek().text())
                                || ",".equals(peek().text())
                                || "]".equals(peek().text()))) {
                    break;
                }
                Token token = next();
                if ("{".equals(token.text())) {
                    braceDepth++;
                } else if ("}".equals(token.text())) {
                    if (braceDepth == 0) {
                        throw new ParseException();
                    }
                    braceDepth--;
                } else if ("[".equals(token.text())) {
                    bracketDepth++;
                } else if ("]".equals(token.text())) {
                    if (bracketDepth == 0) {
                        throw new ParseException();
                    }
                    bracketDepth--;
                }
                valueTokens.add(token);
            }
            if (valueTokens.isEmpty() || braceDepth != 0 || bracketDepth != 0) {
                throw new ParseException();
            }
            return new OptionAssignment(name, valueTokens);
        }

        private String parseOptionName() {
            StringBuilder builder = new StringBuilder();
            if (accept("(")) {
                // A leading dot only marks the extension name as fully qualified.
                accept(".");
                builder.append('(').append(parseQualifiedName()).append(')');
                expect(")");
            } else {
                builder.append(expectIdentifierLike().text());
            }
            while (accept(".")) {
                builder.append('.').append(expectIdentifierLike().text());
            }
            return builder.toString();
        }

        private String parseQualifiedName() {
            StringBuilder builder = new StringBuilder();
            builder.append(expectIdentifierLike().text());
            while (accept(".")) {
                builder.append('.').append(expectIdentifierLike().text());
            }
            return builder.toString();
        }

        private String parseTypeName() {
            if (accept(".")) {
                return "." + parseQualifiedName();
            }
            return parseQualifiedName();
        }

        private int parseSignedInt() {
            Token token;
            if (accept("-")) {
                token = expectKind(TokenKind.NUMBER);
                return -parseNumber(token.text());
            }
            token = expectKind(TokenKind.NUMBER);
            return parseNumber(token.text());
        }

        private int parseNumber(String value) {
            try {
                String lower = value.replace("_", "").toLowerCase(Locale.ROOT);
                boolean negative = lower.startsWith("-");
                if (negative || lower.startsWith("+")) {
                    lower = lower.substring(1);
                }
                int radix =
                        lower.startsWith("0x")
                                ? 16
                                : lower.startsWith("0") && lower.length() > 1 ? 8 : 10;
                if (radix == 16) {
                    lower = lower.substring(2);
                }
                int parsed = (int) Long.parseLong(lower, radix);
                return negative ? -parsed : parsed;
            } catch (NumberFormatException exception) {
                throw new ParseException();
            }
        }

        private void skipImport() {
            if (peek().kind() == TokenKind.STRING) {
                next();
            } else {
                if (Set.of("public", "weak", "option").contains(peek().text())) {
                    next();
                }
                expectKind(TokenKind.STRING);
            }
            expect(";");
        }

        private void skipUntilStatementEnd() {
            while (!isEof() && !accept(";")) {
                if ("{".equals(peek().text()) || "}".equals(peek().text())) {
                    throw new ParseException();
                }
                next();
            }
        }

        private void skipBalanced(char open, char close) {
            int depth = 1;
            while (depth > 0) {
                if (isEof()) {
                    throw new ParseException();
                }
                Token token = next();
                if (token.text().equals(Character.toString(open))) {
                    depth++;
                } else if (token.text().equals(Character.toString(close))) {
                    depth--;
                }
            }
        }

        private boolean isDeclaration(String keyword) {
            if ("extend".equals(keyword)) {
                return keyword.equals(peek().text());
            }
            return keyword.equals(peek().text())
                    && peek(1).kind() == TokenKind.IDENT
                    && "{".equals(peek(2).text());
        }

        private boolean isVisibility() {
            return "export".equals(peek().text()) || "local".equals(peek().text());
        }

        private boolean isLabel(String value) {
            return "optional".equals(value) || "required".equals(value) || "repeated".equals(value);
        }

        private Token expectIdentifierLike() {
            if (peek().kind() != TokenKind.IDENT) {
                throw new ParseException();
            }
            return next();
        }

        private Token expectKind(TokenKind kind) {
            if (peek().kind() != kind) {
                throw new ParseException();
            }
            return next();
        }

        private Token expect(String text) {
            if (!accept(text)) {
                throw new ParseException();
            }
            return previous();
        }

        private boolean accept(String text) {
            if (text.equals(peek().text())) {
                index++;
                return true;
            }
            return false;
        }

        private Token next() {
            return tokens.get(index++);
        }

        private Token previous() {
            return tokens.get(index - 1);
        }

        private Token peek() {
            return peek(0);
        }

        private Token peek(int offset) {
            int target = index + offset;
            return target < tokens.size() ? tokens.get(target) : tokens.get(tokens.size() - 1);
        }

        private boolean isEof() {
            return peek().kind() == TokenKind.EOF;
        }

        private String qualify(List<String> scope) {
            String local = String.join(".", scope);
            return StringUtils.isBlank(packageName) ? local : packageName + "." + local;
        }

        private List<String> append(List<String> parents, String name) {
            List<String> scope = new ArrayList<>(parents);
            scope.add(name);
            return scope;
        }

        private static void putFeature(
                Map<String, String> features, String optionName, String value) {
            if (optionName.startsWith("features.") && value != null) {
                features.put(optionName.substring("features.".length()), value);
            }
        }

        /**
         * Matches a validation extension set whole or through a sub-path such as {@code
         * .string.min_len}.
         */
        private static boolean isValidationOption(String optionName) {
            return VALIDATION_OPTIONS.stream()
                    .anyMatch(
                            option ->
                                    optionName.equals(option)
                                            || optionName.startsWith(option + "."));
        }

        private static FieldOptions emptyFieldOptions() {
            return new FieldOptions(
                    null, null, null, null, null, null, Map.of(), Set.of(), Map.of());
        }

        private static String lowerFirst(String value) {
            return value.isEmpty()
                    ? value
                    : value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
        }
    }

    private record OptionAssignment(String name, List<Token> valueTokens) {
        String value() {
            StringBuilder builder = new StringBuilder();
            for (Token token : valueTokens) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(
                        token.kind() == TokenKind.STRING
                                ? "STRING:" + token.stringValue()
                                : token.text());
            }
            return builder.toString();
        }

        String valueIdentifier() {
            return valueTokens.stream()
                    .filter(token -> token.kind() == TokenKind.IDENT)
                    .map(Token::text)
                    .findFirst()
                    .orElse(null);
        }

        String stringValue() {
            return valueTokens.stream()
                    .filter(token -> token.kind() == TokenKind.STRING)
                    .map(Token::stringValue)
                    .findFirst()
                    .orElse(null);
        }

        Set<String> valueIdentifiers() {
            Set<String> identifiers = new LinkedHashSet<>();
            for (Token token : valueTokens) {
                if (token.kind() == TokenKind.IDENT) {
                    identifiers.add(token.text());
                }
            }
            return identifiers;
        }
    }

    private static final class ParseException extends RuntimeException {}
}
