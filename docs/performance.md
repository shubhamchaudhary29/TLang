# Performance and benchmarking

TLang's benchmarks establish a reproducible measurement foundation before
runtime optimization work begins. They are correctness-checked JMH workloads,
not claims that TLang is faster or slower than another language.

## Commands

Use the fast smoke task in CI and before committing benchmark changes:

```bash
./gradlew benchmarkSmoke
```

It compiles all benchmark source sets, loads and validates every fixture, runs
each discovered benchmark once without a fork or warmup, and writes
`build/reports/jmh/smoke-results.json`. This proves discovery, correctness, and
structured output; its scores are deliberately not suitable as a baseline.

Run the full local suite with:

```bash
./gradlew jmh
```

The full configuration uses JMH 1.37 with one fork, three warmup iterations,
five measurement iterations, and one second per iteration. Results are written
to `build/reports/jmh/results.json`. Environment metadata is written beside it
as `build/reports/jmh/environment.json` and includes the TLang version, Git
commit, Java VM/version, operating system, architecture, JMH version, and run
configuration.

To generate a clean reference report:

```bash
./gradlew clean jmh
```

Archive both JSON files together. A recorded report is a reference for that
specific commit and environment, not a universal performance guarantee.

## Coverage

- Front end: lexing, parsing, and semantic resolution using small, medium, and
  large deterministic programs.
- Interpreter: arithmetic, loops, function calls, recursion, closures, lists,
  maps, and string operations using `.tiny` fixtures.
- Standard library: JSON parsing/stringification and string joining.
- Database: a controlled SQLite round trip in a fresh temporary directory whose
  path contains spaces; state is removed after every invocation.
- Backend primitives: deterministic HTTP route normalization, segmentation, and
  parameterized route matching without network access.
- Concurrent backend: complete loopback HTTP requests through the real server,
  request interpreter fork, handler, response buffer, and socket at batch
  concurrency 1, 10, 50, and 100. Each invocation validates every response.
- End to end: a warmed in-memory source run through lexing, parsing, resolution,
  and interpretation.

The end-to-end benchmark excludes process startup and source-file I/O. It must
not be presented as CLI startup time. A future startup benchmark should use a
separate process-level harness and remain clearly labeled.

## Correctness and state isolation

Each runtime fixture defines a top-level `result`. Trial setup executes the
fixture and compares that result with a deterministic expected value before JMH
collects measurements. Benchmark methods return their computed data so JMH can
prevent dead-code elimination. Parsed programs are immutable inputs and each
runtime operation gets a fresh interpreter. The SQLite workload creates fresh
state per invocation, closes its connection, and recursively removes its
dedicated temporary directory.

Benchmark support tests cover missing fixtures, invalid source, failed script
execution, missing results, malformed output configuration, output directory
creation, paths containing spaces, and cleanup retry behavior.

## Reading results

The suite primarily reports average time per operation. Lower scores indicate
less elapsed time for the named workload, in the reported unit. Compare the
same benchmark and parameters, not unrelated rows. Inspect error/confidence
data and rerun enough times to distinguish a stable effect from noise.

Do not compare results from different machines blindly. CPU model and power
policy, operating system, Java build, background load, thermal state, and JMH
configuration can all move scores. GitHub-hosted runner timings are especially
variable, so CI checks compilation, correctness, and smoke execution only. It
does not enforce absolute scores or percentage regression gates.

## Concurrent HTTP results

`ConcurrentHttpBenchmark.loopbackBatch` reports average milliseconds for a
whole simultaneous batch, parameterized by `concurrency`. It is a real network
benchmark against a small `"ok"` handler, not a router-method microbenchmark.
For a result with batch size `N` and average duration `D` milliseconds, the
corresponding batch throughput is `N * 1000 / D` requests/second. Inspect the
JMH error/confidence values and the machine metadata before comparing runs.

The benchmark fails if any response has a non-200 status or a corrupt body.
JMH is not a load generator for saturation/capacity planning; use a dedicated
tool and a representative application/database for production sizing.

## Current limitations and future work

TLang currently uses a tree-walking interpreter. HTTP handler execution is
concurrent, but CPU-bound handlers still consume one fixed-pool worker for their
duration and synchronous filesystem, HTTP-client, and database calls block that
request's worker. Bytecode/JIT work and language-level async syntax remain
separate future phases.

Future optimization work should first capture a full JSON result and matching
environment metadata, make one focused change, and rerun under the same
controlled conditions. Improvements should preserve fixture results and the
full validation suite. Package management remains a separate roadmap phase.
