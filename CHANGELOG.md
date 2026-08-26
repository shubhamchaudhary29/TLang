# Changelog

## Unreleased (0.3.0)

- Added explicit `spawn call(...)` and blocking `await task` expressions backed
  by root-owned Java 21 virtual-thread task runtimes.
- Added opaque task values, isolated task interpreter cursors, configurable
  outstanding-task admission, dependency-cycle detection, and reusable results.
- Preserved structured error categories and source locations across task
  boundaries with explicit spawn/await stack frames.
- Prevented background tasks from mutating HTTP response wrappers outside their
  owning request cursor, while allowing copied request data to be read safely.
- Added deterministic task, closure, module, HTTP, SQLite, cycle, limit,
  repeated-await, 500-task stress, benchmark, and CI repetition coverage.
- Added immutable, structured runtime diagnostics with source locations,
  user-facing categories, and innermost-first TLang call frames.
- Preserved source identity and stack traces across closures, recursion, user
  modules, module initialization, native boundaries, and HTTP handlers.
- Categorized import, database, HTTP-client, validation, type, name, index, and
  arity failures while retaining native causes without printing Java stacks.
- Hardened HTTP failures so detailed diagnostics remain server-side and remote
  clients receive only a generic `500 Internal Server Error` response.
- Replaced the global HTTP interpreter lock with isolated per-request execution
  cursors on a bounded, owned worker pool.
- Defined thread-safe shared semantics for globals, mutable lists/maps, module
  initialization, native cache/config state, and SQLite connections.
- Added deterministic parallelism, isolation, failure, module, database,
  routing, stress, and shutdown tests plus a real loopback HTTP benchmark.
- Documented the concurrent runtime architecture, lifecycle, and mutable-global
  caveats, and added a concurrent API example.

## v0.2.0

- Added scope-aware LSP completion for keywords, built-ins, local bindings,
  parameters, functions, imports, native modules, and known module exports.
- Added a pinned JMH benchmark suite for the front end, interpreter workloads,
  standard library, SQLite, routing, and the warmed end-to-end pipeline.
- Added fast benchmark smoke validation to CI and machine-readable benchmark
  results/environment metadata.
- Documented benchmark methodology, interpretation, and current runtime limits.

## v0.1.0

- Published installers for the initial tagged release.
