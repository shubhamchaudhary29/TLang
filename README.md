# TLang

TLang is a small, dynamically typed scripting language for straightforward backend APIs. It includes native modules for concurrent HTTP servers, SQLite/PostgreSQL databases, cryptography, configuration, JSON, and validation. Explicit `spawn` and `await` expressions run ordinary calls on bounded Java 21 virtual-thread tasks without introducing async functions, promises, or an event loop.

---

## Quickstart

### 1. Install TLang

TLang v0.4.0 is distributed as native installers for Linux, macOS, and Windows.
Download the package for your platform from the
[v0.4.0 GitHub release](https://github.com/shubhamchaudhary29/TLang/releases/tag/v0.4.0):

- Linux: `tlang_0.4.0_amd64.deb`
- macOS: `TLang-0.4.0.pkg`
- Windows: `TLang-0.4.0.msi`

The installers include the runtime. Java 21 is required only when building from
source. After installing, `tlang version` should report `TLang version 0.4.0`.

To build from source instead:

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
With an installed package, execute it with `tlang run hello.tiny`. For a
source-built distribution, use:
```bash
build/install/tlang/bin/tlang run hello.tiny
```

### Reproducible projects

Create a project and install a local or Git dependency with:

```bash
tlang init
tlang add utils --path ../utils
tlang install
tlang run main.tiny
```

Commit `tlang.toml` and `tlang.lock`; the generated `.tlang/` cache and install
tree stays local. Git dependencies are pinned to exact commits, transitive
graphs are deterministic, and `tlang install --offline` never accesses the
network. See [Projects and package management](docs/packages.md).

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

### PostgreSQL with bounded pooling

The same `db` module accepts PostgreSQL URLs. Credentials come naturally from
the existing `config` module, and every normal value is a prepared-statement
parameter:

```tiny
import config
import db

config.load()
let conn be db.open(config.require("DATABASE_URL"), {
    username: config.require("DATABASE_USER"),
    password: config.require("DATABASE_PASSWORD"),
    poolSize: 10,
    queryTimeoutSeconds: 15
})

let migrationResult be conn.migrate("migrations")
let activeUsers be conn.query("SELECT id, name FROM users WHERE active = ?", [true])
```

PostgreSQL handles are safe to share across concurrent HTTP handlers and tasks;
each ordinary operation borrows independently from the handle's bounded pool.

For CRUD by primary key, declare an explicit repository field contract:

```tiny
let usersRepository be conn.repository("users", {
    primaryKey: "id",
    fields: ["id", "name", "active"]
})
let userById be usersRepository.find(42)
usersRepository.update(42, {name: "Ada"})
```

Repositories provide `find`, `exists`, `create`, `update`, `delete`, `count`, and
`query`. Use repositories for field-checked CRUD, `repository.query()` or
`connection.table()` for structured queries, and raw SQL for full control.
See the [repository contract](stdlib/db.md#lightweight-repositories).

Common CRUD is available without writing SQL:

```tiny
let users be conn.table("users")
let user be users.where("id", "=", 1).first()
let page be users.orderBy("id", "asc").limit(20).all()
users.where("id", "=", 1).update({name: "Ada"})
```

Table builders are immutable, bind every value, and reject update/delete without
predicates. The same `table()` API works on transaction handles. Raw SQL remains
available for advanced operations.

Forward-only numbered SQL migrations work with both PostgreSQL and SQLite,
record SHA-256 history, reject edited or retroactively inserted migrations, and
serialize concurrent deploys at the database. See the
[database reference](stdlib/db.md) for the filename contract, transactions,
lifecycle, timeouts, value mappings, security behavior, and SQLite compatibility.

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
- **[PostgreSQL API Example](examples/postgres-api/README.md)**: A runnable PostgreSQL notes service with pooling, migrations, repository CRUD, and a query-builder escape hatch.
- **[Performance and Benchmarking](docs/performance.md)**: JMH commands, benchmark coverage, methodology, and result interpretation.
- **[Concurrent Runtime Architecture](docs/concurrent-runtime.md)**: HTTP execution isolation, shared-state semantics, database concurrency, and lifecycle guarantees.
- **[Runtime Diagnostics](docs/errors.md)**: Structured error categories, source-aware TLang stack traces, native causes, and safe HTTP error responses.
- **[Projects and Packages](docs/packages.md)**: Manifests, lockfiles, local/Git dependencies, imports, offline installs, security, and troubleshooting.
- **[Local Package Example](examples/packages/app/README.md)**: A complete network-free project and dependency.

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
