# TLang

TLang is a small, dynamically typed scripting language for straightforward backend APIs. It includes native modules for concurrent HTTP servers, SQLite, cryptography, configuration, JSON, and validation. Explicit `spawn` and `await` expressions run ordinary calls on bounded Java 21 virtual-thread tasks without introducing async functions, promises, or an event loop.

---

## Quickstart

### 1. Build TLang from Source
To compile the TLang compiler and runtime CLI, run:
```bash
git clone https://github.com/shubhamchaudhary29/TLang.git
cd TLang
./gradlew installDist
```

### 2. Run a Hello World Script
Create a script named `hello.tiny`:
```tiny
show "Hello, World!"
```
Execute it using the compiled distribution executable:
```bash
build/install/tlang/bin/tlang run hello.tiny
```

---

## Coding Examples

### Hello World
```tiny
show "Hello, World!"
```

### HTTP Server with Database (SQLite)
A complete HTTP POST endpoint that inserts JSON request payloads into an SQLite table:
```tiny
import http
import db
import json

# Open or create database
let conn be db.open("api.db")
conn.execute("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, content TEXT)", [])

# Set up server routes
let server be http.server(8080)
server.post("/notes", function taking req and res
    let body be json.parse(req.body)
    conn.insert("INSERT INTO notes (content) VALUES (?)", [body.content])
    res.status(201).text("Note created successfully!")
    return nil
)

show "Server listening on port 8080..."
server.start()
```

### Structured background tasks

```tiny
define calculate taking value
    return value + 1

let first be spawn calculate(10)
let second be spawn calculate(20)

show await first
show await second
```

The callee and arguments are evaluated by the caller before scheduling. `await`
blocks only its current TLang execution cursor and returns the task result or
rethrows its structured TLang failure.

---

## Documentation Index

Explore the TLang guides and references:
- **[Getting Started Guide](docs/getting-started.md)**: A step-by-step introduction to installing and writing your first TLang script.
- **[Language Reference](docs/language-reference.md)**: Human-readable guide to variables, control flow, functions/lambdas, tasks, collections, modules, and `nil`.
- **[Structured Tasks](docs/tasks.md)**: `spawn`/`await` syntax, execution isolation, errors, limits, HTTP ownership, and limitations.
- **[Standard Library Reference](docs/stdlib/index.md)**: Detailed reference pages for the available native modules.
- **[Auth Service Example Walkthrough](docs/examples/auth-service.md)**: An in-depth architectural look at the complete backend user registration and authentication service example.
- **[Concurrent API Example](examples/concurrent-api/README.md)**: A runnable multi-route service with parallel CPU work and shared collection state.
- **[Performance and Benchmarking](docs/performance.md)**: JMH commands, benchmark coverage, methodology, and result interpretation.
- **[Concurrent Runtime Architecture](docs/concurrent-runtime.md)**: HTTP execution isolation, shared-state semantics, SQLite behavior, and lifecycle guarantees.
- **[Runtime Diagnostics](docs/errors.md)**: Structured error categories, source-aware TLang stack traces, native causes, and safe HTTP error responses.

---

## Project Specifications & Philosophy

TLang is built upon strong foundational principles. For details on language semantics and architecture, see:
- **[Language Philosophy (LANGUAGE_PHILOSOPHY.md)](LANGUAGE_PHILOSOPHY.md)**: The developer-experience-first principles guiding TLang's design.
- **[Language Specification (SPEC.md)](SPEC.md)**: The formal specification of TLang's grammar, AST, and evaluation semantics.

---

## Editor tooling

The bundled VS Code extension uses TLang's Java language server for diagnostics,
hover, definition, references, rename, and context-aware completion. Completion
includes visible lexical bindings and known native/user-module exports; no
parallel JavaScript autocomplete implementation is required.

## Performance benchmarks

Run the fast compile/correctness smoke check with:

```bash
./gradlew benchmarkSmoke
```

Run the warmed and forked local JMH suite with:

```bash
./gradlew jmh
```

Smoke scores are not performance baselines. See the
[performance guide](docs/performance.md) before recording or comparing results.
