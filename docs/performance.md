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
- Backend: deterministic HTTP route normalization, segmentation, and
  parameterized route matching without network access.
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

## Current limitations and future work

TLang currently uses a tree-walking interpreter. HTTP handler execution is
serialized by synchronization on the interpreter, so request handlers sharing
an interpreter do not run concurrently. The router benchmarks intentionally
measure deterministic routing primitives rather than claiming concurrent HTTP
throughput.

Future optimization work should first capture a full JSON result and matching
environment metadata, make one focused change, and rerun under the same
controlled conditions. Improvements should preserve fixture results and the
full validation suite. Bytecode/JIT work, concurrency changes, and package
management remain separate future roadmap phases.
