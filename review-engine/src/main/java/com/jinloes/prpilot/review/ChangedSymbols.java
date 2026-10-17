package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the names of existing declarations a diff changes, so their callers in the base commit can
 * be shown to the reviewer. Textual on purpose: a spurious candidate costs a few prompt bytes,
 * while a missed caller costs a missed bug. Language-agnostic except for protobuf schemas, whose
 * consumers use the names generated code derives from an element rather than the element's own.
 */
final class ChangedSymbols {
    static final int MAX_SYMBOLS = 12;
    static final int MIN_NAME_LENGTH = 3;

    /**
     * A declaration the diff touches. {@code declarationChanged} is true when a removed line
     * declared it (signature changed or symbol deleted); false when only its body changed. {@code
     * aliases} are further whole-word names to search: the names code generated from a protobuf
     * element uses for it.
     */
    record Symbol(String name, String path, boolean declarationChanged, List<String> aliases) {
        Symbol {
            aliases =
                    aliases == null
                            ? List.of()
                            : aliases.stream()
                                    .filter(alias -> !alias.equals(name))
                                    .distinct()
                                    .toList();
        }

        Symbol(String name, String path, boolean declarationChanged) {
            this(name, path, declarationChanged, List.of());
        }

        /** The name followed by its aliases. */
        List<String> searchTerms() {
            List<String> terms = new ArrayList<>(aliases.size() + 1);
            terms.add(name);
            terms.addAll(aliases);
            return List.copyOf(terms);
        }
    }

    private static final String NAME = "([A-Za-z_$][\\w$]*)";
    private static final List<Pattern> DECLARATIONS =
            List.of(
                    // Python, Go (with receiver), Kotlin (with extension receiver), JS, Rust.
                    Pattern.compile(
                            "\\b(?:def|func|fun|function|fn)\\s+(?:\\([^)]*\\)\\s*)?"
                                    + "(?:[A-Za-z_][\\w]*\\.)?"
                                    + NAME),
                    Pattern.compile(
                            "\\b(?:class|interface|enum|record|struct|trait|protocol)\\s+" + NAME),
                    // Java, C#, TypeScript and Kotlin members led by at least one modifier.
                    Pattern.compile(
                            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                                    + "(?:(?:public|protected|private|internal|static|final"
                                    + "|abstract|synchronized|native|default|override|virtual"
                                    + "|async|export|open|suspend)\\s+)+"
                                    + "(?:<[^>]*>\\s*)?"
                                    + "(?:[\\w<>\\[\\],.?]+\\s+)*?"
                                    + NAME
                                    + "\\s*\\("),
                    Pattern.compile(
                            "\\b(?:const|let|var)\\s+"
                                    + NAME
                                    + "\\s*=\\s*(?:async\\s+)?"
                                    + "(?:function\\b|\\([^)]*\\)\\s*=>|[A-Za-z_$][\\w$]*\\s*=>)"));

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ [^@]* @@ ?(.*)$");

    private static final Pattern PROTO_TYPE =
            Pattern.compile("^\\s*(message|enum|service)\\s+([A-Za-z_]\\w*)");
    private static final Pattern PROTO_RPC = Pattern.compile("^\\s*rpc\\s+([A-Za-z_]\\w*)\\s*\\(");
    // Requiring a number after "=" keeps out options, enum values, and syntax or edition lines.
    private static final Pattern PROTO_FIELD =
            Pattern.compile(
                    "^\\s*(?:(?:optional|required|repeated)\\s+)?"
                            + "(map\\s*<[^>]*>|\\.?[A-Za-z_][\\w.]*)\\s+"
                            + "([A-Za-z_]\\w*)\\s*=\\s*\\d");

    /**
     * Protobuf field names so common across messages (AIP standard fields and single generic words)
     * that searching for them and their accessors lists other messages' fields, not consumers of
     * the changed one.
     */
    private static final Set<String> GENERIC_PROTO_FIELDS =
            Set.of(
                    "name",
                    "type",
                    "value",
                    "key",
                    "data",
                    "kind",
                    "status",
                    "state",
                    "code",
                    "message",
                    "description",
                    "title",
                    "url",
                    "uri",
                    "parent",
                    "filter",
                    "order_by",
                    "page_size",
                    "page_token",
                    "next_page_token",
                    "total_size",
                    "update_mask",
                    "etag",
                    "uid",
                    "display_name",
                    "create_time",
                    "update_time",
                    "delete_time",
                    "expire_time",
                    "labels",
                    "annotations",
                    "request_id",
                    "validate_only");

    private static final Set<String> IGNORED =
            Set.of(
                    "if",
                    "for",
                    "while",
                    "switch",
                    "catch",
                    "return",
                    "new",
                    "throw",
                    "else",
                    "main",
                    "init",
                    "__init__",
                    "constructor",
                    "toString",
                    "hashCode",
                    "equals",
                    "get",
                    "set",
                    "run",
                    "call",
                    "apply",
                    "test",
                    "setUp",
                    "tearDown",
                    "compareTo",
                    "close",
                    "clone",
                    "values",
                    "valueOf",
                    "of",
                    "self",
                    "this",
                    "super");

    private ChangedSymbols() {}

    /**
     * Extracts up to {@link #MAX_SYMBOLS} changed declarations from {@code manifest}, declarations
     * whose signature changed first, then enclosing declarations whose body changed. Test files are
     * skipped because nothing outside them calls their members. In a {@code .proto} file the
     * declarations are messages, enums, services, RPCs and fields, each carrying the names
     * generated code gives it as aliases.
     */
    static List<Symbol> extract(InspectionManifest manifest) {
        if (manifest == null) return List.of();
        Map<String, Symbol> declarations = new LinkedHashMap<>();
        Map<String, Symbol> bodies = new LinkedHashMap<>();
        for (InspectionManifest.FileTarget file : manifest.files()) {
            if (file.path() == null || isTestPath(file.path()) || file.diff() == null) continue;
            boolean proto = file.path().toLowerCase(Locale.ROOT).endsWith(".proto");
            for (String line : file.diff().split("\n", -1)) {
                if (line.startsWith("-") && !line.startsWith("---")) {
                    Symbol symbol = symbol(line.substring(1), file.path(), true, proto);
                    if (symbol != null) declarations.putIfAbsent(symbol.name(), symbol);
                } else {
                    Matcher hunk = HUNK_HEADER.matcher(line);
                    if (hunk.matches()) {
                        Symbol symbol = symbol(hunk.group(1), file.path(), false, proto);
                        if (symbol != null) bodies.putIfAbsent(symbol.name(), symbol);
                    }
                }
            }
        }
        List<Symbol> symbols = new ArrayList<>(declarations.values());
        bodies.values().stream()
                .filter(symbol -> !declarations.containsKey(symbol.name()))
                .forEach(symbols::add);
        return symbols.size() > MAX_SYMBOLS ? symbols.subList(0, MAX_SYMBOLS) : symbols;
    }

    /** The name a source line declares, or null when it declares nothing worth searching for. */
    static String declaredName(String line) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.strip();
        if (trimmed.startsWith("//")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("*")
                || trimmed.startsWith("#")) {
            return null;
        }
        for (Pattern pattern : DECLARATIONS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                return name.length() < MIN_NAME_LENGTH || IGNORED.contains(name) ? null : name;
            }
        }
        return null;
    }

    private static Symbol symbol(String source, String path, boolean declaration, boolean proto) {
        if (proto) return protoSymbol(source, path, declaration);
        String name = declaredName(source);
        return name == null ? null : new Symbol(name, path, declaration);
    }

    /**
     * The protobuf element a {@code .proto} line declares, carrying the names generated code gives
     * it, or null when the line declares nothing worth searching for. Options, enum values and
     * reserved statements declare nothing searchable, and generic field names are skipped.
     */
    static Symbol protoSymbol(String line, String path, boolean declarationChanged) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.strip();
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return null;
        }
        Matcher type = PROTO_TYPE.matcher(line);
        if (type.find()) {
            String name = type.group(2);
            if (!searchable(name)) return null;
            return new Symbol(
                    name,
                    path,
                    declarationChanged,
                    "service".equals(type.group(1)) ? serviceAliases(name) : List.of());
        }
        Matcher rpc = PROTO_RPC.matcher(line);
        if (rpc.find()) {
            String name = rpc.group(1);
            return searchable(name)
                    ? new Symbol(name, path, declarationChanged, rpcAliases(name))
                    : null;
        }
        Matcher field = PROTO_FIELD.matcher(line);
        if (field.find()) {
            String fieldType = field.group(1);
            String name = field.group(2);
            if ("option".equals(fieldType)
                    || "group".equals(fieldType)
                    || GENERIC_PROTO_FIELDS.contains(name)
                    || !searchable(name)) {
                return null;
            }
            return new Symbol(name, path, declarationChanged, fieldAliases(name));
        }
        return null;
    }

    private static boolean searchable(String name) {
        return name.length() >= MIN_NAME_LENGTH && !IGNORED.contains(name);
    }

    /** The names protoc's Java, Go, C++, C#, Python and JavaScript generators give a field. */
    static List<String> fieldAliases(String field) {
        String lower = camelCase(field, false);
        String upper = camelCase(field, true);
        return List.of(
                lower,
                upper,
                "get" + upper,
                "Get" + upper,
                "set" + upper,
                "has" + upper,
                "Has" + upper,
                "clear" + upper,
                "Clear" + upper,
                "get" + upper + "List",
                "get" + upper + "Count",
                "add" + upper,
                "addAll" + upper,
                "get" + upper + "Map",
                "put" + upper,
                "get" + upper + "OrDefault",
                "get" + upper + "Value",
                "set" + upper + "Value",
                "set_" + field,
                "has_" + field,
                "clear_" + field,
                "mutable_" + field,
                "add_" + field,
                field + "_size");
    }

    /** The names gRPC stubs give an RPC method: lower camel case (Java, JS) and async forms. */
    static List<String> rpcAliases(String rpc) {
        return List.of(camelCase(rpc, false), rpc + "Async", "Async" + rpc);
    }

    /** The names gRPC, Connect and grpc-gateway generators give a service's stubs and handlers. */
    static List<String> serviceAliases(String service) {
        return List.of(
                service + "Grpc",
                service + "GrpcKt",
                service + "Client",
                service + "Server",
                service + "Service",
                service + "Stub",
                service + "Servicer",
                service + "Handler",
                "New" + service + "Client",
                "New" + service + "Handler",
                "Register" + service + "Server",
                "Register" + service + "Handler",
                "Register" + service + "HandlerFromEndpoint",
                "Register" + service + "HandlerServer",
                "Unimplemented" + service + "Server",
                "Unimplemented" + service + "Handler",
                "add_" + service + "Servicer_to_server");
    }

    /**
     * protoc's camel casing of a name: underscores are dropped, and a letter after an underscore or
     * a digit is upper-cased, as are the first letter when {@code upperFirst} is set.
     */
    static String camelCase(String name, boolean upperFirst) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean capitalizeNext = upperFirst;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(i == 0 && !upperFirst ? Character.toLowerCase(c) : c);
                capitalizeNext = false;
            } else if (c >= '0' && c <= '9') {
                sb.append(c);
                capitalizeNext = true;
            } else {
                capitalizeNext = true;
            }
        }
        return sb.toString();
    }

    static boolean isTestPath(String path) {
        String lower = "/" + path.toLowerCase(Locale.ROOT);
        String file = path.substring(path.lastIndexOf('/') + 1);
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.contains("/__tests__/")
                || file.startsWith("test_")
                || file.matches("\\w*(?:Test|Tests|Spec)\\.\\w+")
                || file.matches(".*[._](?:test|spec)\\.\\w+");
    }
}
