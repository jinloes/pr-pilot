; Protocol Buffers (.proto) highlighting

; Message, enum, service, and oneof names — treated as type declarations
(message_name) @type
(enum_name) @type
(service_name) @type
(message_or_enum_type) @type

; RPC method names
(rpc_name) @function.method

; Comments
(comment) @comment

; String literals
(string) @string

; Numeric literals
[
  (int_lit)
  (float_lit)
] @number

; Boolean literals (named nodes in this grammar, not anonymous tokens)
[
  (true)
  (false)
] @boolean

; Declaration keywords
[
  "syntax"
  "option"
  "message"
  "enum"
  "service"
  "rpc"
  "returns"
  "oneof"
  "map"
  "reserved"
] @keyword.type

; Import / package
[
  "import"
  "package"
  "weak"
  "public"
] @keyword.import

; Field modifiers
[
  "repeated"
  "optional"
] @keyword.modifier

; Scalar type keywords (used as field types)
[
  "double"
  "float"
  "int32"
  "int64"
  "uint32"
  "uint64"
  "sint32"
  "sint64"
  "fixed32"
  "fixed64"
  "sfixed32"
  "sfixed64"
  "bool"
  "string"
  "bytes"
] @type.builtin

; Miscellaneous keywords
[
  "stream"
] @keyword