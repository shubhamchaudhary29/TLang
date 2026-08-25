# Concurrent HTTP runtime

TLang v0.3 executes HTTP handlers concurrently without sharing an interpreter's
mutable execution cursor. This document records the runtime audit, the state
model, and the operational limits of that design.

## Request lifecycle

```text
HttpServer fixed worker pool
  -> immutable route/middleware snapshot
  -> request-local req and res values
  -> request interpreter fork
  -> shared handler/closure definition
  -> request-local function environments and recursion depth
  -> buffered response flush
```

Before v0.3, `ServerOps` sent the whole middleware and route chain through
`synchronized (interpreter)`. That lock was necessary because `Interpreter`
stored the current lexical environment and recursion depth in mutable fields.
It also serialized all handler work, even when Java's HTTP server accepted more
than one connection.

The v0.3 server creates a lightweight interpreter fork for every exchange.
ASTs, functions, native functions, module exports, and lexical closure roots are
reused. The fork owns its current-environment pointer and recursion counter, so
parameters, locals, nested call frames, defaults, and failures cannot overwrite
another request's execution state.

## State classification

| Runtime state | Classification | Semantics |
| --- | --- | --- |
| Parsed AST, tokens, route patterns | Immutable shared | Published before `start()` and never mutated while serving. |
| Function declarations and function bodies | Immutable shared | A call creates a fresh local environment in the request interpreter. |
| Closure/global environments | Thread-safe shared | Binding reads, definitions, and assignments are synchronized and visible across requests. |
| Scalar global values | Thread-safe shared binding | Each read/write is atomic. A compound expression such as `set n to n + 1` is multiple operations and is not an atomic increment. |
| TLang lists and maps | Thread-safe shared | Primitive `add`, `set`, `put`, `remove`, lookup, and snapshot operations are synchronized. Multi-operation application invariants still require an external transaction/coordination mechanism. |
| Request, query, header, JSON-body, and path-parameter maps | Request-local | A new object graph is created for every exchange. |
| Response status, headers, and body buffer | Request-local | Exactly one wrapper is created and flushed per exchange. Handler failures replace an unflushed partial response with a 500 response. |
| Module export cache | Thread-safe shared | First load is atomic; a user module executes once per `ModuleLoader`. Module failures raise `RuntimeError` and never terminate the JVM. |
| Native JSON/crypto/validation/filesystem/random utilities | Immutable or stateless shared | Temporary parsers, buffers, digests, and results are created per call. Shared inputs are read through stable snapshots. |
| Config and cache module stores | Thread-safe shared | Backed by concurrent maps. Cache expiry removal is conditional on the entry observed. |
| SQLite connection object | Thread-safe shared per connection | Operations and `close()` on one JDBC connection are serialized by that connection's lock. Different connections can proceed concurrently and remain subject to SQLite file locking/busy errors. |
| HTTP client | Thread-safe shared | Java's immutable requests and thread-safe `HttpClient` are used; calls remain synchronous from the calling TLang handler. |
| Filesystem and process environment | External shared state | Filesystem calls are synchronous and rely on OS filesystem semantics. Environment variables are read-only; `.env` cache publication is concurrent-safe. |

Global state remains global for backward compatibility. TLang deliberately does
not apply copy-on-request semantics: a handler that assigns a global makes that
new value visible to other handlers. Individual accesses cannot corrupt the
environment, list, or map, but read-modify-write algorithms can interleave. Use
SQLite transactions or another coordination primitive when the application
requires an atomic multi-step invariant.

## Executor and lifecycle

Each server owns a fixed executor. The default worker count is the JVM's
reported processor count clamped to the range 4–32;
`-Dtlang.http.workers=N` overrides it. The waiting queue is bounded at 256 exchanges and applies caller-runs
backpressure when saturated, so the runtime does not create unbounded threads or
an unbounded task queue. Worker names begin with `tlang-http-` for diagnostics.

Routes and middleware must be registered before `start()`. Start publishes
immutable snapshots. `stop()` closes the listening socket with a short grace
period, shuts down the executor, and waits for worker termination when called
outside a worker. A stopped server object cannot be restarted; create a new
server instead.

## Audit findings fixed in v0.3

- The whole handler chain was guarded by one interpreter monitor.
- The default `HttpServer` executor did not provide an explicit resource bound
  or owned shutdown lifecycle.
- User-module cache and circular-import tracking used unsynchronized maps/sets.
- Module syntax/runtime errors called `System.exit`, allowing a request-time
  import failure to terminate the service.
- Cache/config native modules mutated shared `LinkedHashMap` instances.
- The same SQLite JDBC connection could be used and closed concurrently.
- Language-created mutable lists/maps and several native results were raw Java
  collections that could be structurally corrupted when stored globally.
- Direct 404/405 writes could be followed by the no-response fallback, risking
  a second response on the same exchange.

The deterministic socket tests in `ConcurrentHttpServerTest` cover real
parallel entry, closures/recursion, local/request/response isolation, security
headers, runtime failures, native modules, shared collections, SQLite reads and
writes, routing, stress, and shutdown. `ConcurrentHttpBenchmark` measures a
complete loopback small-response handler at concurrency 1, 10, 50, and 100.
