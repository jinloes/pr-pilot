; TypeScript — ECMAScript base + TypeScript-specific patterns
; ECMAScript base (shared with JavaScript)
(identifier) @variable

(property_identifier) @variable.member
(shorthand_property_identifier) @variable.member
(private_property_identifier) @variable.member

(object_pattern (shorthand_property_identifier_pattern) @variable)
(object_pattern (object_assignment_pattern (shorthand_property_identifier_pattern) @variable))

((identifier) @type
  (#lua-match? @type "^[A-Z]"))

((identifier) @constant
  (#lua-match? @constant "^_*[A-Z][A-Z%d_]*$"))

((shorthand_property_identifier) @constant
  (#lua-match? @constant "^_*[A-Z][A-Z%d_]*$"))

((identifier) @variable.builtin
  (#any-of? @variable.builtin "arguments" "module" "console" "window" "document"))

((identifier) @type.builtin
  (#any-of? @type.builtin
    "Object" "Function" "Boolean" "Symbol" "Number" "Math" "Date" "String" "RegExp" "Map" "Set"
    "WeakMap" "WeakSet" "Promise" "Array" "Int8Array" "Uint8Array" "Uint8ClampedArray" "Int16Array"
    "Uint16Array" "Int32Array" "Uint32Array" "Float32Array" "Float64Array" "ArrayBuffer" "DataView"
    "Error" "EvalError" "InternalError" "RangeError" "ReferenceError" "SyntaxError" "TypeError"
    "URIError"))

; Function definitions
(function_expression name: (identifier) @function)
(function_declaration name: (identifier) @function)
(generator_function name: (identifier) @function)
(generator_function_declaration name: (identifier) @function)

(method_definition name: [(property_identifier)(private_property_identifier)] @function.method)
(method_definition name: (property_identifier) @constructor (#eq? @constructor "constructor"))

(pair key: (property_identifier) @function.method value: (function_expression))
(pair key: (property_identifier) @function.method value: (arrow_function))

(assignment_expression
  left: (member_expression property: (property_identifier) @function.method)
  right: (arrow_function))
(assignment_expression
  left: (member_expression property: (property_identifier) @function.method)
  right: (function_expression))

(variable_declarator name: (identifier) @function value: (arrow_function))
(variable_declarator name: (identifier) @function value: (function_expression))
(assignment_expression left: (identifier) @function right: (arrow_function))
(assignment_expression left: (identifier) @function right: (function_expression))

; Function calls
(call_expression function: (identifier) @function.call)
(call_expression function: (member_expression property: [(property_identifier)(private_property_identifier)] @function.method.call))
(call_expression function: (await_expression (identifier) @function.call))
(call_expression function: (await_expression (member_expression property: [(property_identifier)(private_property_identifier)] @function.method.call)))

((identifier) @function.builtin
  (#any-of? @function.builtin
    "eval" "isFinite" "isNaN" "parseFloat" "parseInt" "decodeURI" "decodeURIComponent" "encodeURI"
    "encodeURIComponent" "require"))

(new_expression constructor: (identifier) @constructor)

; Decorators
(decorator "@" @attribute (identifier) @attribute)
(decorator "@" @attribute (call_expression (identifier) @attribute))
(decorator "@" @attribute (member_expression (property_identifier) @attribute))
(decorator "@" @attribute (call_expression (member_expression (property_identifier) @attribute)))

; Literals
[
  (this)
  (super)
] @variable.builtin

((identifier) @variable.builtin (#eq? @variable.builtin "self"))

[
  (true)
  (false)
] @boolean

[
  (null)
  (undefined)
] @constant.builtin

[
  (comment)
  (html_comment)
] @comment

(string) @string
(template_string) @string
(escape_sequence) @string.escape
(regex_pattern) @string.regexp

(number) @number

((identifier) @number
  (#any-of? @number "NaN" "Infinity"))

; Keywords
[
  "if" "else" "switch" "case"
] @keyword.conditional

[
  "import" "from" "as" "export"
] @keyword.import

[
  "for" "of" "do" "while" "continue"
] @keyword.repeat

[
  "break" "const" "debugger" "extends" "get" "let" "set" "static" "target" "var" "with"
] @keyword

"class" @keyword.type

[
  "async" "await"
] @keyword.coroutine

[
  "return" "yield"
] @keyword.return

"function" @keyword.function

[
  "new" "delete" "in" "instanceof" "typeof"
] @keyword.operator

[
  "throw" "try" "catch" "finally"
] @keyword.exception

(export_statement "default" @keyword)
(switch_default "default" @keyword.conditional)

; Operators
[
  "--" "-" "-=" "&&" "+" "++" "+=" "&=" "/=" "**=" "<<=" "<" "<=" "<<"
  "=" "==" "===" "!=" "!==" "=>" ">" ">=" ">>" "||" "%" "%=" "*" "**" ">>>"
  "&" "|" "^" "??" "*=" ">>=" ">>>=" "^=" "|=" "&&=" "||=" "??=" "..."
] @operator

(ternary_expression ["?" ":"] @keyword.conditional.ternary)
(unary_expression ["!" "~" "-" "+"] @operator)
(unary_expression ["delete" "void"] @keyword.operator)

; Parameters (ECMAScript)
(parameter pattern: (identifier) @variable.parameter)
(parameter (rest_pattern (identifier) @variable.parameter))
(parameter (object_pattern (shorthand_property_identifier_pattern) @variable.parameter))
(parameter (object_pattern (object_assignment_pattern (shorthand_property_identifier_pattern) @variable.parameter)))
(parameter (object_pattern (pair_pattern value: (identifier) @variable.parameter)))
(parameter (array_pattern (identifier) @variable.parameter))
(arrow_function parameter: (identifier) @variable.parameter)

; TypeScript-specific

; Type identifiers (more specific than identifier, so placed after)
(type_identifier) @type

(predefined_type) @type.builtin

; Typed function parameters
(required_parameter pattern: (identifier) @variable.parameter)
(optional_parameter pattern: (identifier) @variable.parameter)

; Declarations that introduce type names
(interface_declaration name: (type_identifier) @type)
(type_alias_declaration name: (type_identifier) @type)
(enum_declaration name: (identifier) @type)

; Generic type parameters
(type_parameter name: (type_identifier) @variable.parameter)

; Keywords — modifiers
[
  "declare"
  "abstract"
  "override"
  "readonly"
  "asserts"
  "infer"
  "keyof"
  "unique"
  "satisfies"
] @keyword.modifier

(accessibility_modifier) @keyword.modifier

[
  "namespace"
  "module"
] @keyword.import

"implements" @keyword.type
"enum" @keyword.type
"interface" @keyword.type
"type" @keyword.type

; Type assertion / cast
(as_expression "as" @keyword.operator)

; Non-null assertion operator
(non_null_expression "!" @operator)
