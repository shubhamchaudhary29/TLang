# db

`db` is TLang's single database module. It preserves path-based SQLite access
and adds pooled PostgreSQL access without separate driver modules or new
language syntax.

## Opening a database

`db.open(target, options?)` returns a database handle.

```tiny
import db

let sqlite be db.open("app.db")
let postgres be db.open("postgresql://localhost:5432/myapp")
```

SQLite targets are file paths; `":memory:"` creates an in-memory database.
PostgreSQL targets use `postgresql://host[:port]/database` (the `postgres://`
alias is also accepted). URL query strings, fragments, `jdbc:` URLs, and
unrecognized schemes are rejected so callers cannot inject arbitrary JDBC
properties. Percent-encoded usernames/passwords in URL user-info are supported,
but separate configuration is preferred because URLs are commonly logged by
other infrastructure.

The optional map has exactly these keys:

| Option | Default | Meaning |
| --- | ---: | --- |
| `username` | URL/driver default | PostgreSQL username |
| `password` | URL/driver default | PostgreSQL password |
| `poolSize` | `10` | PostgreSQL physical-connection upper bound (`1`–`64`) |
| `connectionTimeoutMs` | `5000` | Maximum pool wait/connect time (`250`–`120000`) |
| `queryTimeoutSeconds` | `30` | JDBC statement timeout (`1`–`3600`); applies to both providers |

Use the existing `config` module for environment-backed secrets:

```tiny
import config
import db

config.load()
let conn be db.open(config.require("DATABASE_URL"), {
    username: config.require("DATABASE_USER"),
    password: config.require("DATABASE_PASSWORD"),
    poolSize: 12,
    connectionTimeoutMs: 5000,
    queryTimeoutSeconds: 15
})
```

`db` does not read its own environment variables or create a second
configuration system.

## Database-handle methods

`query(sql, params)` executes a result-producing statement and returns a list
of row maps. `execute(sql, params)` executes a non-query statement and returns
its affected-row count. `insert`, `update`, and `delete` are compatibility
aliases of `execute`.

```tiny
conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)", [])
let affected be conn.insert("INSERT INTO users (id, name) VALUES (?, ?)", [1, "O'Brien"])
let rows be conn.query("SELECT id, name FROM users WHERE id = ?", [1])
```

Every value in `params` is bound through `PreparedStatement`; values are never
concatenated into SQL. Supported parameters are TLang strings, integers,
booleans, and `nil`. The parameter list is mandatory, including `[]` when the
SQL has no placeholders. Placeholder validation ignores quoted strings,
identifiers, SQL comments, and PostgreSQL dollar-quoted strings.

`lastInsertId()` preserves SQLite's `last_insert_rowid()` behavior. PostgreSQL
callers use a result-producing statement with `RETURNING`:

```tiny
let inserted be conn.query("INSERT INTO users (name) VALUES (?) RETURNING id", ["Ada"])
let id be inserted.get(0).id
```

`close()` is idempotent. Querying a closed handle raises `DatabaseError`.
Stopping an HTTP server does not close unrelated global handles; applications
must close them during their own shutdown path.

Handles opened inside a request handler or spawned task are owned by that
execution cursor and are automatically closed when it succeeds or fails.
Top-level handles remain application-owned so a server can share one pool for
its lifetime.

The read-only `provider` field is `"sqlite"` or `"postgresql"`.

## Transactions

`begin()` returns a transaction handle with the same query/execute/insert/
update/delete methods plus `commit()` and `rollback()`.

```tiny
let transaction be conn.begin()
transaction.insert("INSERT INTO ledger (account, amount) VALUES (?, ?)", [7, 100])
transaction.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", [100, 7])
transaction.commit()
```

A transaction pins one physical connection and serializes its own operations.
Do not share a transaction handle between HTTP handlers or spawned tasks.
Nested transactions are not exposed. SQLite permits one active transaction per
database handle; while it is active, use the transaction handle rather than the
parent handle. PostgreSQL can host multiple independent transaction handles up
to the pool bound.

If parameter binding, SQL execution, row conversion, or TLang-side argument
validation fails during a transaction operation, the runtime automatically
rolls back, closes the transaction, and releases its resource. This is required
because TLang has no language-level `try/catch`. Closing the parent database
handle also rolls back all active transactions. Calling commit/rollback after
completion raises `DatabaseError`.

## Values

| Database value | TLang value |
| --- | --- |
| SQL `NULL` | `nil` |
| `SMALLINT`/`INTEGER`/safe `BIGINT` or integral `NUMERIC` | integer |
| PostgreSQL `BOOLEAN` | boolean |
| SQLite boolean storage | integer `0`/`1` (backward compatible) |
| text/varchar | string |
| date/timestamp/UUID | ISO-formatted string |
| result row | map keyed by column label |
| result set | list of row maps |

TLang integers are signed 32-bit values. Fractional numeric values and integers
outside that range fail rather than truncate. Binary values and unsupported
provider-specific objects (for example raw PostgreSQL `jsonb`) also fail; decode
or cast them to text in SQL when that is the intended representation. Duplicate
column labels fail rather than silently overwriting a map entry—use SQL aliases.

## Pooling and concurrency

Each PostgreSQL `db.open` owns one bounded HikariCP pool. Ordinary operations
borrow a connection for the duration of one statement and return it in all
success and failure paths. A shared PostgreSQL handle is safe across concurrent
HTTP execution cursors and spawned tasks, and independent operations can run in
parallel. Pool exhaustion waits at most `connectionTimeoutMs` and then raises a
structured `DatabaseError`. `close()` prevents new borrows, waits for in-flight
ordinary operations, rolls back pinned transactions, and shuts down the pool.

SQLite keeps one JDBC connection per handle. Operations and close are
serialized on that handle; separate handles follow SQLite's normal file locking
and busy-error rules. TLang does not add hidden retries or a PostgreSQL-style
pool around SQLite.

## Errors and security

Database failures retain the call-site source location and TLang stack frames.
PostgreSQL failures are translated to safe descriptions for authentication,
connection, pool wait, timeout, syntax, missing table, and common constraints.
Messages and CLI formatting omit passwords, JDBC implementation stacks, raw
server details, and connection URLs. HTTP clients continue to receive only
`500 Internal Server Error`; detailed structured diagnostics stay server-side.

Parameterized APIs prevent parameter values—including quotes, semicolons,
SQL-looking strings, Unicode, multiline text, empty text, and `nil`—from
changing SQL structure. Table/column names cannot be parameters; applications
must select such identifiers from a fixed allowlist rather than accepting raw
request input.

## Current limits

Migrations, schema DSLs, ORMs, query builders, savepoints/nested transactions,
floating-point values, binary values, and automatic JSON decoding are outside
this milestone. PostgreSQL network failures abort the affected operation or
transaction; subsequent ordinary operations borrow a validated replacement
connection from the pool.
