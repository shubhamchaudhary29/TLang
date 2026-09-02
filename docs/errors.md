# Runtime diagnostics

TLang runtime failures are structured language diagnostics. They carry a stable
category, a user-facing message, the originating source location, and immutable
TLang call frames. The CLI formats that model; messages and stack traces are not
assembled inside the interpreter.

Compile-time lexer, parser, and semantic diagnostics remain separate because
they describe source validation rather than an executing call stack. Runtime
failures continue to use exit code `70`; compile-time diagnostics continue to
use exit code `65`.

## Categories

| Category | Representative use |
| --- | --- |
| `RuntimeError` | General execution failures such as division by zero |
| `TypeError` | Invalid operand types and attempts to call non-callable values |
| `NameError` | A runtime environment lookup or assignment cannot find a name |
| `ImportError` | Missing or circular user-module imports |
| `DatabaseError` | SQLite/PostgreSQL connection, statement, timeout, constraint, value, pool, transaction, or lifecycle failures |
| `HttpError` | Malformed HTTP URLs and transport, timeout, or request-construction failures |
| `ValidationError` | Invalid use of the validation API or an invalid validation schema |
| `IndexError` | Missing map keys and out-of-range list/string indexes |
| `ArityError` | A function or method receives the wrong number of arguments |
| `TaskError` | Task admission, cycle, interruption, or infrastructure failure |

`validate.check()` still returns `{valid, errors}` for ordinary application-data
validation failures. `ValidationError` is reserved for misuse of that API; it
does not turn expected invalid input into an exception.

## Source identity

Every token references one immutable source unit containing its source name and
source text. All tokens from a file share that object; source text is not copied
into each token or stack frame. Main scripts, user modules, closures, and parsed
interpolation expressions therefore retain their real source identity without a
global “current filename.” This remains safe when HTTP requests execute
concurrently.

The primary location points to the expression that raised the error. The
formatter prints one source line and caret when source text is available.
Host-created tokens may have a position but no source text; those diagnostics
fall back to a location without a snippet.

## TLang stack frames

Frames are ordered from the innermost failing call outward. A function frame is
recorded at the call site that invoked that function, while the primary location
continues to identify the actual failing expression. Repeated recursive calls
remain repeated frames; frames are never deduplicated by function name.

Named functions use their declared names. Lambdas use `<anonymous>`. Runtime
module-initialization failures add `<module name>` boundaries, and HTTP request
failures add a final method/path frame such as `POST /report`. Native-call
boundaries are represented without exposing Java implementation methods.
Task failures add `<spawn>` where they were created, and each wait adds a fresh
`<await>` frame without mutating the failure stored in the task.

For example, the checked-in three-file fixture produces a diagnostic shaped
like:

```text
RuntimeError: Division by zero.

  --> .../stack_math.tiny:2:18
   |
2 |     return value / divisor
   |                  ^

Stack trace:
  at divide (.../stack_service.tiny:4:23)
  at buildReport (.../stack_main.tiny:5:26)
  at handleRequest (.../stack_main.tiny:7:14)
```

Paths depend on how the CLI was invoked; tests assert the structured locations
and logical ordering rather than a machine-specific absolute prefix.

## Native causes and Java separation

Native integrations may retain a Java cause for debugging. Database provider
errors and HTTP transport errors do this. Normal CLI formatting never renders
the cause, Java class names, or a Java stack trace. Successful calls do not build
formatted stack strings or capture Java stacks.

PostgreSQL errors are translated to a small safe vocabulary (for example,
authentication failure, connection failure, timeout, missing table, invalid
SQL, and constraint violation). JDBC URLs, passwords, SQLState internals, and
server detail fields are not included in the TLang-facing message. SQLite keeps
its established diagnostic text for backward compatibility.

## HTTP security and concurrency

A runtime failure aborts only the affected request. The server remains alive,
and other requests continue independently. The default server-side diagnostic
sink formats the detailed TLang diagnostic to standard error.

Remote clients receive only status `500` and `Internal Server Error`. Responses
do not include TLang frames, source paths, request bodies, authorization or
cookie values, database details, Java class names, or Java stack traces. There is
currently no public debug mode that changes this response policy.

Runtime errors and frame lists are immutable. Adding an outer function, module,
native, or HTTP frame creates a new error value with a defensive frame copy, so
simultaneous request failures cannot contaminate one another.

## Language boundary

TLang still has no language-level `try`/`catch`, `throw`, or asynchronous
exception syntax. Structured diagnostics improve propagation and reporting; they
do not add a new control-flow feature.
