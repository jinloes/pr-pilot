package com.jinloes.prpilot.review;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compact per-language defect checklists, condensed from the bug-hunt language references.
 *
 * <p>Only the languages present in the diff are added to the prompt, so a Java-only PR does not pay
 * for the Rust list. The lists widen what Pass B looks for; every finding still has to meet the
 * evidence policy.
 */
final class LanguageChecklists {

    private static final Map<String, String> LANGUAGE_BY_EXTENSION =
            Map.ofEntries(
                    Map.entry("java", "Java"),
                    Map.entry("kt", "Kotlin"),
                    Map.entry("kts", "Kotlin"),
                    Map.entry("js", "JavaScript/TypeScript"),
                    Map.entry("jsx", "JavaScript/TypeScript"),
                    Map.entry("mjs", "JavaScript/TypeScript"),
                    Map.entry("cjs", "JavaScript/TypeScript"),
                    Map.entry("ts", "JavaScript/TypeScript"),
                    Map.entry("tsx", "JavaScript/TypeScript"),
                    Map.entry("mts", "JavaScript/TypeScript"),
                    Map.entry("cts", "JavaScript/TypeScript"),
                    Map.entry("py", "Python"),
                    Map.entry("go", "Go"),
                    Map.entry("rs", "Rust"),
                    Map.entry("cs", "C#"),
                    Map.entry("c", "C/C++"),
                    Map.entry("h", "C/C++"),
                    Map.entry("cc", "C/C++"),
                    Map.entry("cpp", "C/C++"),
                    Map.entry("cxx", "C/C++"),
                    Map.entry("hh", "C/C++"),
                    Map.entry("hpp", "C/C++"),
                    Map.entry("hxx", "C/C++"),
                    Map.entry("sh", "Shell"),
                    Map.entry("bash", "Shell"),
                    Map.entry("zsh", "Shell"));

    private static final Map<String, String> CHECKLISTS = new LinkedHashMap<>();

    static {
        CHECKLISTS.put(
                "Java",
                "- A stream, connection, reader, or lock not closed on every path (use"
                        + " try-with-resources / finally).\n"
                        + "- An exception caught and dropped, or rethrown without its cause.\n"
                        + "- equals overridden without hashCode, or a mutable field used in either"
                        + " while the object sits in a hash collection.\n"
                        + "- A HashMap, ArrayList, or SimpleDateFormat shared across threads, or a"
                        + " check-then-act on a ConcurrentHashMap instead of compute/putIfAbsent.\n"
                        + "- A ThreadLocal not removed on a pooled thread, or context lost when work"
                        + " moves to an executor or CompletableFuture.\n"
                        + "- @Transactional, @Async, or @Cacheable on a method called from the same"
                        + " class (the proxy is bypassed), or on a private method.\n"
                        + "- A lazy JPA association read in a loop (N+1), or an entity returned"
                        + " outside its session.\n"
                        + "- SQL, LDAP, or a shell command built by string concatenation; an XML"
                        + " parser without external entities disabled.\n"
                        + "- Integer overflow or == on boxed Integer/Long, and Optional.get()"
                        + " without a presence check.\n");
        CHECKLISTS.put(
                "Kotlin",
                "- A `return` inside a lambda (let, forEach, run) that exits the enclosing"
                        + " function instead of the lambda.\n"
                        + "- runCatching, or catch (e: Exception), around a suspend call: it"
                        + " swallows CancellationException and breaks cancellation.\n"
                        + "- `!!` on a value that can be null, or a Java platform type assumed"
                        + " non-null.\n"
                        + "- GlobalScope, or a CoroutineScope never cancelled, leaking work past"
                        + " its owner's lifetime.\n"
                        + "- runBlocking on a request, UI, or coroutine thread.\n"
                        + "- @Volatile used for a read-modify-write that needs an atomic or a"
                        + " Mutex; shared mutable state touched from several coroutines.\n"
                        + "- A data class with a mutable or array property used as a map key or"
                        + " compared with ==.\n"
                        + "- Blocking I/O on Dispatchers.Default or Main instead of Dispatchers.IO.\n");
        CHECKLISTS.put(
                "JavaScript/TypeScript",
                "- A promise not awaited or returned, so its rejection is unhandled or its"
                        + " result is used before it settles.\n"
                        + "- async callbacks in forEach, or Promise.all over work that must run in"
                        + " order or is unbounded.\n"
                        + "- A React effect with missing or unstable dependencies, a missing"
                        + " cleanup, or state set after unmount.\n"
                        + "- A stale closure capturing an old value in a callback, timer, or"
                        + " listener.\n"
                        + "- `any`, a non-null `!`, or an `as` cast hiding a real undefined or"
                        + " wrong-shape value.\n"
                        + "- == coercion, falsy checks that reject 0 or \"\", or a number parsed"
                        + " without radix or NaN handling.\n"
                        + "- innerHTML, dangerouslySetInnerHTML, eval, or a URL built from user"
                        + " input; object keys from input enabling prototype pollution.\n"
                        + "- An event listener, interval, or subscription never removed.\n");
        CHECKLISTS.put(
                "Python",
                "- A mutable default argument (list, dict, set) shared across calls.\n"
                        + "- A bare except, or except Exception, that hides the failure or catches"
                        + " KeyboardInterrupt/SystemExit or asyncio.CancelledError.\n"
                        + "- A file, connection, or lock not managed by `with`.\n"
                        + "- A blocking call (requests, time.sleep, sync DB) inside an async"
                        + " function, or a coroutine called without await.\n"
                        + "- A late-binding closure in a loop capturing the loop variable.\n"
                        + "- Naive and timezone-aware datetimes mixed, or local time used for"
                        + " storage.\n"
                        + "- SQL or shell commands built by string formatting; pickle, yaml.load,"
                        + " or eval on untrusted data.\n"
                        + "- A dict or list mutated while iterating over it.\n");
        CHECKLISTS.put(
                "Go",
                "- An error return ignored, or shadowed by := in an inner scope.\n"
                        + "- A goroutine that can block forever on a channel, or is never"
                        + " cancelled via its context.\n"
                        + "- A loop variable captured by a goroutine or closure (pre-Go 1.22).\n"
                        + "- A map, slice, or struct written from several goroutines without a"
                        + " lock.\n"
                        + "- A response body, file, or rows not closed; defer inside a loop.\n"
                        + "- A nil interface holding a typed nil pointer compared with nil.\n"
                        + "- An outbound HTTP or RPC call without a context deadline or client"
                        + " timeout.\n"
                        + "- An append to a sub-slice that overwrites the caller's backing array.\n");
        CHECKLISTS.put(
                "Rust",
                "- unwrap(), expect(), or indexing that can panic on input or I/O failure.\n"
                        + "- A Mutex or RefCell guard held across an .await or a long call.\n"
                        + "- A blocking call inside async code without spawn_blocking.\n"
                        + "- An `unsafe` block whose invariant the change breaks or does not"
                        + " document.\n"
                        + "- Integer arithmetic that can overflow or a lossy `as` cast.\n"
                        + "- A Result discarded with `let _ =` or `.ok()` that hides a failure.\n"
                        + "- A task spawned and never joined or cancelled.\n");
        CHECKLISTS.put(
                "C#",
                "- `async void` outside an event handler, or a Task neither awaited nor"
                        + " observed, so its exception is lost.\n"
                        + "- .Result, .Wait(), or GetAwaiter().GetResult() on a Task, which can"
                        + " deadlock under a synchronization context or starve the thread"
                        + " pool.\n"
                        + "- An IDisposable (stream, connection, DbContext,"
                        + " CancellationTokenSource) not disposed on every path (use `using`),"
                        + " or a new HttpClient created per call instead of a shared or"
                        + " factory-managed one.\n"
                        + "- A CancellationToken accepted but not passed to the calls that take"
                        + " one, or OperationCanceledException caught and treated as a"
                        + " failure.\n"
                        + "- lock on this, a Type, or a string; a Dictionary or List shared"
                        + " across threads without a lock or a concurrent collection.\n"
                        + "- The null-forgiving `!` operator, or a suppressed nullable warning,"
                        + " on a value that can be null.\n"
                        + "- SQL built by interpolation or concatenation, including FromSqlRaw,"
                        + " ExecuteSqlRaw, or SqlCommand text (FromSql and FromSqlInterpolated"
                        + " parameterize).\n"
                        + "- An IEnumerable enumerated more than once (re-running its query), or"
                        + " an unbounded query materialized with ToList().\n"
                        + "- Unity: `?.`, `??`, or `is null` on a UnityEngine.Object, which"
                        + " skips Unity's destroyed-object check; GetComponent, Find, or an"
                        + " allocation inside Update; a coroutine or event subscription not"
                        + " stopped or removed in OnDisable or OnDestroy.\n");
        CHECKLISTS.put(
                "C/C++",
                "- A buffer read or write not checked against the buffer's size (memcpy,"
                        + " strcpy, sprintf, indexing, or a length taken from input).\n"
                        + "- Use after free, double free, a dangling pointer or reference (to a"
                        + " local, a temporary, or a container element), or an iterator used"
                        + " after its container changes.\n"
                        + "- Memory, a file, or a lock leaked on an error path, or a raw new or"
                        + " malloc where RAII (a smart pointer or scoped guard) belongs.\n"
                        + "- A variable or field read before it is initialized.\n"
                        + "- Integer overflow, signed and unsigned mixing, or truncation in a"
                        + " size or index calculation.\n"
                        + "- A format string taken from input, or a specifier that does not"
                        + " match its argument.\n"
                        + "- Data shared across threads without a lock or an atomic.\n"
                        + "- A polymorphic base class without a virtual destructor, or a class"
                        + " owning a resource without matching copy and move operations (rule"
                        + " of three/five).\n"
                        + "- Undefined behavior the optimizer can exploit: a null dereference,"
                        + " signed overflow, a strict-aliasing violation, or a shift by the"
                        + " type's width or more.\n");
        CHECKLISTS.put(
                "Shell",
                "- An unquoted variable or command substitution that splits on spaces or"
                        + " expands globs (rm $file, for f in $(ls)).\n"
                        + "- A script that continues after a failed command (no set -euo"
                        + " pipefail or equivalent checks), or cd without || exit.\n"
                        + "- rm -rf, chmod, or mv on a path built from a variable that can be"
                        + " empty or unset (rm -rf \"$DIR/\"*).\n"
                        + "- eval, or a variable interpolated into a command run through ssh,"
                        + " sh -c, sed, or awk, when the value can come from input.\n"
                        + "- A script downloaded and piped to a shell, or a download used"
                        + " without checksum verification.\n"
                        + "- A temporary file at a fixed, predictable path instead of mktemp,"
                        + " or no trap to clean it up.\n"
                        + "- A secret passed on a command line (visible in the process list) or"
                        + " printed by set -x.\n"
                        + "- $? read after another command replaced it, or a pipeline that"
                        + " masks an earlier command's failure.\n");
    }

    private LanguageChecklists() {}

    /** The language name for a path, or {@code null} when no checklist covers its extension. */
    static String languageFor(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash + 1 || dot == path.length() - 1) return null;
        return LANGUAGE_BY_EXTENSION.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * The checklist section for the given changed paths, or an empty string when none of them is a
     * covered language. Languages appear in a fixed order so the prompt is deterministic.
     */
    static String sectionFor(Collection<String> paths) {
        var languages = new TreeSet<String>();
        for (String path : paths) {
            String language = languageFor(path);
            if (language != null) languages.add(language);
        }
        if (languages.isEmpty()) return "";
        var section =
                new StringBuilder(
                        "\nLanguage-specific checks. During Pass B, also check the changed code"
                                + " in these languages for:\n");
        for (var entry : CHECKLISTS.entrySet()) {
            if (!languages.contains(entry.getKey())) continue;
            section.append("\n").append(entry.getKey()).append(":\n").append(entry.getValue());
        }
        return section.toString();
    }
}
