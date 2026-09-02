# Structured tasks

TLang v0.3 provides two explicit concurrency expressions:

```tiny
let task be spawn calculate(10)
let result be await task
```

Ordinary functions remain synchronous when called normally. There is no
`async define`, promise chaining, callback scheduler, event loop, or coroutine
state machine.

## Execution model

For `spawn foo(a(), b())`, the caller evaluates `foo`, then `a()`, then `b()`.
Only the resolved callable and evaluated values are handed to the background
task. Argument AST nodes are never evaluated later on another cursor.

```text
caller interpreter cursor
  -> evaluate callee and arguments
  -> admit TaskValue
  -> fork isolated execution cursor
  -> Java 21 virtual thread
  -> existing function/native call pipeline
  -> result or structured RuntimeError
  -> blocking await
```

Each task cursor owns its current-environment pointer and recursion depth. It
shares immutable AST/function definitions, lexical closure environments,
program globals, the module loader, synchronized lists/maps, native module
state, and its root interpreter's `TaskRuntime`.

The runtime is not process-global. Independent root interpreters in one JVM
have independent limits, task identifiers, and wait graphs.

## Syntax and values

`spawn` requires a call:

```tiny
let named be spawn work(1)
let closureTask be spawn closure("value")
let moduleTask be spawn service.calculate()
let responseTask be spawn http.get(url)
```

`await` is an expression with unary-like precedence:

```tiny
let direct be await spawn work()
let combined be (await task) + 1
show await task
return await task
```

A task is opaque. `type_of(task)` is `"task"`; stringification reports only
`<task pending>`, `<task running>`, `<task completed>`, or `<task failed>`.
Results and failures are reusable across repeated awaits.

## Scheduling and limits

Tasks use stable Java 21 virtual threads, so a task may spawn and await a child
without fixed-pool starvation. Each runtime rejects creation above the
outstanding-task limit rather than blocking for capacity. The default is 1024;
set `-Dtlang.tasks.maxOutstanding=N` to choose a positive process configuration
for newly created root interpreters.

Capacity is released after success, TLang failure, unexpected native failure,
or fatal host termination. Cancellation is not implemented in M3.

## Errors and cycles

An underlying `NameError`, `TypeError`, `DatabaseError`, `HttpError`, or other
TLang category remains unchanged. Its original source location and function
frames remain primary. The task stores an immutable spawn frame; every await
creates a fresh outward await frame. Java causes remain internal and are not
rendered by the normal formatter.

`TaskError` is reserved for task-system failures such as admission rejection,
wait interruption, unexpected native exceptions, cross-runtime misuse, and
detected dependency cycles. A runtime-local wait graph detects self-await and
multi-task cycles before blocking; timeouts are not used as deadlock semantics.

## Closures, modules, and shared data

Closure environments stay alive when a task outlives the function that created
it. Module exports and nested module functions are ordinary callable values and
can be spawned. HTTP-client and database native calls follow their existing
semantics inside tasks; one SQLite handle still serializes its operations. A
shared PostgreSQL handle borrows separately from its bounded pool, so task
queries can overlap. A transaction handle is serialized and must not be shared
between tasks.

Globals, lists, and maps retain the concurrent runtime model. Individual
binding and collection operations are synchronized. Compound operations such
as `set counter to counter + 1` remain multiple operations and are not atomic.

## HTTP handlers

Tasks may be created and awaited inside simultaneous handlers. Awaiting blocks
that handler's current bounded-pool worker; it is not coroutine suspension and
does not change the M1 HTTP worker architecture.

Request method, path, body, query, headers, JSON, and parameter values are
copied into request-local TLang data before the handler runs. A task may read
data deliberately passed from that map.

Response mutation remains owned by the request handler execution thread. A
background task calling `res.text`, `res.json`, `res.send`, `res.status`, or
`res.header` receives `HttpError`. Tasks should return data; the handler should
await it and mutate `res` itself.

## Limitations

- No async function declarations, promises, chaining, callbacks, or event loop.
- `await` blocks its current TLang execution cursor.
- No task cancellation or user-visible task handles beyond await/state text.
- No automatic atomicity for compound shared-state operations.
- No user-language `try`/`catch`.
- Un-awaited virtual-thread tasks are not guaranteed to keep a terminating host
  process alive.
