# Changelog

## Unreleased (0.3.0)

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
