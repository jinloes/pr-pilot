; Bash/Shell highlighting
[
  "if" "then" "else" "elif" "fi" "case" "in" "esac"
] @keyword.conditional

[
  "for" "do" "done" "select" "until" "while"
] @keyword.repeat

[
  "declare" "typeset" "readonly" "local" "unset" "unsetenv"
] @keyword

"export" @keyword.import
"function" @keyword.function

[
  ">" ">>" "<" "<<" "&&" "|" "|&" "||" "=" "+=" "=~" "==" "!="
  "&>" "&>>" "<&" ">&" ">|" "<&-" ">&-" "<<-" "<<<"  ".." "!"
] @operator

[
  (string)
  (raw_string)
  (ansi_c_string)
  (heredoc_body)
] @string

((word) @boolean
  (#any-of? @boolean "true" "false"))

(comment) @comment

(function_definition name: (word) @function)
(command_name (word) @function.call)

(command_name (word) @function.builtin
  (#any-of? @function.builtin
    "." ":" "alias" "bg" "bind" "break" "builtin" "caller" "cd" "command" "compgen" "complete"
    "compopt" "continue" "coproc" "dirs" "disown" "echo" "enable" "eval" "exec" "exit" "false" "fc"
    "fg" "getopts" "hash" "help" "history" "jobs" "kill" "let" "logout" "mapfile" "popd" "printf"
    "pushd" "pwd" "read" "readarray" "return" "set" "shift" "shopt" "source" "suspend" "test" "time"
    "times" "trap" "true" "type" "typeset" "ulimit" "umask" "unalias" "wait"))

(number) @number

((word) @number
  (#lua-match? @number "^[0-9]+$"))

(variable_name) @variable

((variable_name) @constant
  (#lua-match? @constant "^[A-Z][A-Z_0-9]*$"))

((variable_name) @variable.builtin
  (#any-of? @variable.builtin
    "CDPATH" "HOME" "IFS" "MAIL" "MAILPATH" "OPTARG" "OPTIND" "PATH" "PS1" "PS2"
    "_" "BASH" "BASHOPTS" "BASHPID" "BASH_ALIASES" "BASH_ARGC" "BASH_ARGV" "BASH_ARGV0"
    "BASH_CMDS" "BASH_COMMAND" "BASH_ENV" "BASH_LINENO" "BASH_REMATCH" "BASH_SOURCE"
    "BASH_VERSINFO" "BASH_VERSION" "COLUMNS" "COMP_WORDS" "COMPREPLY" "DIRSTACK" "EUID"
    "FUNCNAME" "GLOBIGNORE" "GROUPS" "HISTCMD" "HISTFILE" "HISTSIZE" "HOME" "HOSTNAME"
    "HOSTTYPE" "IFS" "LANG" "LINENO" "LINES" "MACHTYPE" "OLDPWD" "OSTYPE" "PIPESTATUS"
    "PPID" "PWD" "RANDOM" "REPLY" "SECONDS" "SHELL" "SHELLOPTS" "SHLVL" "UID"))

(special_variable_name) @constant
