# Changelog

## v0.4.0

### Database and PostgreSQL

- Refactored the `db` module behind provider-neutral connection, session,
  conversion, error, and lifecycle boundaries while preserving SQLite behavior.
- Added PostgreSQL with authenticated URLs/options, bounded HikariCP pools,
  connection waits, statement timeouts, transaction isolation, and recovery from
  broken pooled connections.
- Added explicit transactions with commit, rollback, automatic rollback on
  operation failure, predictable closed-state behavior, and cleanup across
  runtime, HTTP, and task failures.
- Preserved parameterized raw SQL, affected-row operations, SQLite
  `lastInsertId()`, PostgreSQL `RETURNING`, and provider-neutral value conversion.

### Migrations

- Added deterministic forward-only migrations through `migrate(path)` and
  read-only `migrationStatus(path)` for SQLite and PostgreSQL.
- Added numeric ordering, strict UTF-8 discovery, SHA-256 drift detection,
  append-only `_tlang_migrations` history, and transactional multi-statement
  execution.
- Serialized concurrent deploys with PostgreSQL advisory locks and SQLite
  immediate transactions, including bounded waits and complete failure cleanup.

### Query builder

- Added immutable `connection.table()` and `transaction.table()` builders with
  projection, comparisons, membership, ordering, pagination, first/all/count,
  and map-based insert/update/delete.
- Added strict quoted identifiers, deterministic map ordering, bound values,
  bounded query complexity, input snapshots, and mandatory predicates for
  update/delete.

### Repositories

- Added immutable `connection.repository(table, definition)` and transaction
  repositories with explicit primary-key, field, and read-only contracts.
- Added `find`, `exists`, `create`, `update`, `delete`, `count`, and a normal
  query-builder escape hatch, with stable read projections and strict mutation
  validation.

### Security, concurrency, and validation

- Added credential-safe PostgreSQL diagnostics for transport, authentication,
  timeout, syntax, missing-table, and constraint failures while preserving
  source-aware `DatabaseError` stacks and generic remote HTTP 500 responses.
- Added real SQLite and Testcontainers PostgreSQL coverage for CRUD, pooling,
  migrations, locks, transactions, immutable builders/repositories, HTTP/tasks,
  concurrency, lifecycle, injection resistance, and credential redaction.
- Added repeated CI stress validation, database and repository benchmark smoke
  cases, complete database documentation, and a runnable migrated PostgreSQL
  repository example.
- Reconciled the language philosophy with the concurrent HTTP and explicit
  `spawn` / `await` behavior introduced in v0.3.

## v0.3.0

- Added strict `tlang.toml` project manifests and deterministic, integrity-checked
  `tlang.lock` dependency graphs.
- Added `tlang init`, `add`, `remove`, `install`, and `list`, including local path
  and Git dependencies, immutable commit pinning, transitive resolution, clear
  cycle/source-conflict diagnostics, and explicit `install --update` behavior.
- Added project-local atomic package installation and exact-commit Git caching,
  corruption/partial-install repair, process-safe locking, and network-free
  `install --offline` behavior.
- Integrated declared packages with source-aware module loading while preserving
  standalone scripts, sibling/project modules, native-module compatibility,
  task/closure behavior, and dependency source paths in structured diagnostics.
- Hardened package metadata, paths, lockfiles, Git arguments, package trees, and
  install destinations against malformed input, traversal, symlink escape,
  command injection, partial writes, and install-time code execution.
- Added deterministic local Git fixtures and manifest, lockfile, resolver, CLI,
  import, cache, offline, security, failure, large-graph, and concurrent-install
  coverage plus a network-free runnable package example.
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
