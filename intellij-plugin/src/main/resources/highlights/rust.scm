; Forked from https://github.com/tree-sitter/tree-sitter-rust
; Identifier conventions
(identifier) @variable

((identifier) @type
  (#lua-match? @type "^[A-Z]"))

(const_item name: (identifier) @constant)

((identifier) @constant
  (#lua-match? @constant "^[A-Z][A-Z%d_]*$"))

(type_identifier) @type
(primitive_type) @type.builtin
(field_identifier) @variable.member
(shorthand_field_identifier) @variable.member
(self) @variable.builtin

; Function definitions
(function_item (identifier) @function)
(function_signature_item (identifier) @function)

(parameter [
  (identifier)
  "_"
] @variable.parameter)

(closure_parameters (_) @variable.parameter)

; Function calls
(call_expression function: (identifier) @function.call)
(call_expression function: (scoped_identifier (identifier) @function.call .))
(call_expression function: (field_expression field: (field_identifier) @function.call))
(generic_function function: (identifier) @function.call)
(generic_function function: (scoped_identifier name: (identifier) @function.call))
(generic_function function: (field_expression field: (field_identifier) @function.call))

; Enum constructors (uppercase in calls)
((call_expression function: (scoped_identifier "::" name: (identifier) @constant))
  (#lua-match? @constant "^[A-Z]"))

((identifier) @constant.builtin
  (#any-of? @constant.builtin "Some" "None" "Ok" "Err"))

; Macros
(macro_definition "macro_rules!" @function.macro)
(macro_invocation macro: (identifier) @function.macro)
(macro_invocation macro: (scoped_identifier (identifier) @function.macro .))
(attribute_item (attribute (identifier) @attribute))
(inner_attribute_item (attribute (identifier) @attribute))
(attribute (scoped_identifier (identifier) @attribute .))

; Literals
(boolean_literal) @boolean
(integer_literal) @number
(float_literal) @number.float
[
  (raw_string_literal)
  (string_literal)
] @string
(escape_sequence) @string.escape
(char_literal) @character

; Keywords
[
  "use" "mod"
] @keyword.import
(use_as_clause "as" @keyword.import)

[
  "default" "impl" "let" "move" "unsafe" "where"
] @keyword

[
  "enum" "struct" "union" "trait" "type"
] @keyword.type

[
  "async" "await"
] @keyword.coroutine

"try" @keyword.exception

[
  "ref" "pub" (mutable_specifier) "const" "static" "dyn" "extern"
] @keyword.modifier

"fn" @keyword.function

[
  "return" "yield"
] @keyword.return

(type_cast_expression "as" @keyword.operator)

[
  "if" "else" "match"
] @keyword.conditional

[
  "break" "continue" "in" "loop" "while"
] @keyword.repeat

(for_expression "for" @keyword.repeat)

; Operators
[
  "!" "!=" "%" "%=" "&" "&&" "&=" "*" "*=" "+" "+=" "-" "-=" ".." "..=" "..."
  "/" "/=" "<" "<<" "<<=" "<=" "=" "==" ">" ">=" ">>" ">>=" "?" "@" "^" "^=" "|" "|=" "||"
] @operator

; Comments
[
  (line_comment)
  (block_comment)
] @comment
