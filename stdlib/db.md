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

## Lightweight repositories

Choose the smallest database API that expresses the operation:

| Level | Use it for | Example |
| --- | --- | --- |
| Repository | CRUD by one primary key with an explicit field contract | `users.find(id)` |
| Query builder | Filtering, sorting, pagination, or deliberate bulk operations | `users.query().where("active", "=", true).all()` |
| Raw SQL | Joins, expressions, casts, custom schemas, or generated keys | `conn.query(sql, params)` |

Create a repository on a connection or an existing transaction:

```tiny
let definition be {
    primaryKey: "id",
    fields: ["id", "name", "email", "active", "created_at"],
    readOnly: ["created_at"]
}
let users be conn.repository("users", definition)
users.create({id: 42, name: "Ada", email: "ada@example.com", active: true})
let user be users.find(42)
let found be users.exists(42)
users.update(42, {name: "Ada Lovelace"})
let page be users.query().where("active", "=", true).orderBy("id", "asc").limit(20).all()
let total be users.count()
users.delete(42)
```

### Definition contract

`repository(table, definition)` requires a map with `primaryKey` and `fields`;
`readOnly` is optional and defaults to `[]`. Unknown options are rejected.
The table and every field use M3's identifier rules: 1–63 ASCII letters,
digits, or underscores, starting with a letter or underscore. Qualified input
names, wildcards, aliases, and SQL expressions are rejected.

`fields` is an ordered list of 1–100 distinct field names. Reads preserve its
projection order. Case-only duplicates are also rejected, preventing ambiguous
aliases for the same SQLite column. `primaryKey` must be one string matching a
declared field exactly. `readOnly` is a list of at most 100 distinct declared
fields; names in definitions and mutation maps must match exactly, including
case. A read-only primary key is allowed and cannot be supplied to `create`.

Definitions are application contracts, not schema declarations. Create the
schema through migrations and ensure the named primary key is actually a
non-null primary key or unique key in the database. Repositories do not inspect,
create, or synchronize schemas. Required fields, defaults, types, and database
constraints remain database responsibilities.

Definition maps, field lists, and read-only lists are snapshotted at creation.
Later caller mutations cannot change a repository. Repository metadata is
immutable and owns no JDBC resources.

### Methods and return values

| Method | Contract |
| --- | --- |
| `find(id)` | Returns a map containing only declared fields, or `nil` when absent. Uses M3 equality and `first()`. |
| `exists(id)` | Returns a boolean, reading at most one row and only the primary-key column. |
| `create(fields)` | Inserts one nonempty map of declared writable fields; returns affected-row count, normally `1`. |
| `update(id, fields)` | Updates matching rows with a nonempty map of declared writable fields; rejects changing the primary key. Returns affected-row count, including `0` for a missing row. |
| `delete(id)` | Deletes by primary-key equality; returns affected-row count, including `0` when absent. |
| `count()` | Counts all rows using M3 count semantics and the existing signed 32-bit integer conversion. |
| `query()` | Returns a fresh normal M3 builder, initially selecting declared fields. |

IDs must be non-`nil` values supported by existing parameter binding (integer,
string, or boolean); compatibility with the key's SQL type follows the database.
All four ID-based methods reject `nil`, avoiding accidental NULL-key mutations.
Other fields may contain `nil`. Existing boolean and date/timestamp conversions
are unchanged; PostgreSQL writes requiring SQL casts use the raw SQL API.

Create/update reject unknown fields, read-only fields, empty maps, and unsupported
values. Create can supply the primary key unless it is read-only. No defaults,
generated-key retrieval, or hidden follow-up reads are added. For PostgreSQL
`RETURNING id`, use `conn.query(...)` explicitly.

`find` and the default `query()` projection do not expose newly added columns
such as password hashes or reset tokens. `query()` is an explicit escape to M3:
callers can change its projection or filter other columns. It is not an
authorization boundary. M3 rejects mutations on a projected builder; deliberate
bulk writes use `conn.table(...)` or raw SQL. Repositories have no mass-mutation
or unsafe method.

### Transactions, concurrency, and lifetime

```tiny
let tx be conn.begin()
let transactional be tx.repository("users", definition)
transactional.create({id: 43, name: "Grace", email: "grace@example.com", active: true})
transactional.update(43, {name: "Grace Hopper"})
tx.commit()
```

Repository operations use the same M3 builders and M1 sessions as table handles.
Definition validation, mutation validation, and database failures on a transaction
abort that transaction. A missing row is not a failure. Commit, rollback, parent
close, and cursor cleanup invalidate repository handles and their derived native
query handles. Repositories do not extend connection or transaction lifetimes.

An application-owned repository can be shared across tasks and HTTP handlers.
Every operation constructs independent immutable query intent, preserving SQLite
serialization and PostgreSQL bounded pooling. Transaction repositories retain the
existing rule against sharing a transaction across unrelated requests/tasks.
No reflection, caching, identity map, relationships, hooks, or unit of work is
introduced. Errors use the existing safe `DatabaseError` boundary; remote HTTP
clients receive the existing generic 500 response.

## Safe structured CRUD

Use `conn.table("users")` for ordinary CRUD, or `transaction.table("users")`
inside an existing transaction. Raw `query`/`execute` and their aliases remain
available for joins, expressions, casts, `RETURNING`, and deliberate bulk SQL.
Creating and deriving a table handle executes no SQL and owns no JDBC resources.

```tiny
let users be conn.table("users")
users.insert({id: 1, name: "Ada", email: "ada@example.com"})
let user be users.where("email", "=", "ada@example.com").first()
let page be users.select(["id", "name"]).orderBy("id", "asc").limit(20).offset(0).all()
users.where("id", "=", 1).update({name: "Ada Lovelace"})
let total be users.count()
users.where("id", "=", 1).delete()
```

TLang uses `let ... be`, map entries such as `{name: value}`, and `nil` for SQL
NULL. Keep method chains on one line, or assign intermediate builders using
existing language syntax.

| Method | Behavior |
| --- | --- |
| `select(columns)` | Nonempty list of distinct column names; replaces the projection. Default is all columns. |
| `where(column, operator, value)` | Appends an AND predicate. Operators: `=`, `!=`, `<`, `<=`, `>`, `>=`, `like` (case-insensitive). |
| `whereIn(column, values)` | Appends membership as an AND predicate; snapshots the list. Empty list matches nothing. `nil` entries also match SQL NULL; duplicates are allowed. |
| `orderBy(column, direction)` | Appends ordering; only `asc` or `desc`, case-insensitively. Use a unique final sort column for stable pagination. |
| `limit(n)`, `offset(n)` | Replace the corresponding value; require a TLang integer in `0..2147483647`. Offset alone uses an implicit limit of `2147483647`. |
| `all()` | Executes and returns the existing list of row maps. |
| `first()` | Executes with a limit of at most one; retains offset and honors `limit(0)`. Returns a row map or `nil`. |
| `count()` | Counts matching rows, ignoring select, ordering, limit, and offset. Returns a TLang integer; overflow uses the existing database numeric error. |
| `insert(fields)` | Inserts one nonempty map; returns affected-row count (normally `1`), never a generated key. |
| `update(fields)` | Updates a nonempty map on matching rows; returns affected-row count. Requires at least one predicate. |
| `delete()` | Deletes matching rows and returns affected-row count. Requires at least one predicate. |

`where("name", "=", nil)` generates `IS NULL`; `!= nil` generates `IS NOT
NULL`. Other comparisons with `nil` are rejected. `whereIn("name", ["Ada",
nil])` matches either Ada or NULL. As with raw SQL, ordinary non-null comparisons
against NULL do not match. SQL collation, LIKE case sensitivity, and NULL sort
placement follow the database; the builder does not replace provider semantics.

All table and column identifiers must match `[A-Za-z_][A-Za-z0-9_]{0,62}`.
Names are quoted and case-preserving; column references are table-qualified to
prevent SQLite treating a missing quoted column as a string. Qualified input
names such as `public.users`, aliases, wildcards, and SQL expressions are not
accepted. PostgreSQL tables use the connection's normal search path. For custom
schemas or expressions, use raw SQL with application-controlled identifiers.

Values use exactly the existing string/integer/boolean/`nil` parameter binding.
Lists/maps and other values are rejected. Dates and timestamps read through the
builder use the existing ISO string conversion. PostgreSQL date/timestamp writes
that require SQL casts still use raw parameterized SQL; the builder does not
infer types or introduce implicit casts. SQLite booleans still read as `0`/`1`.

Insert/update columns are sorted by ASCII identifier order, regardless of map
insertion order. Each value, including pagination, is a bound parameter. Input
lists/maps are snapshotted; derived builders never modify their parents. There
are at most 100 predicates, selected columns, ordering terms, or write columns,
and at most 900 total bound parameters per compiled operation (including write
values and pagination). `whereIn` accepts at most 900 entries, including `nil`.
These conservative bounds produce predictable errors across both providers.

Writes reject `select`, `orderBy`, `limit`, and `offset`, so modifiers cannot be
silently ignored during mutation. Insert also rejects predicates. Update/delete
without predicates fail with `DatabaseError`; there is no unsafe bypass. An
empty `whereIn` is a valid predicate and updates/deletes zero rows. Intentional
whole-table writes remain possible through raw SQL.

```tiny
let base be conn.table("users")
let named be base.where("name", "=", "Ada")
let other be base.where("name", "=", "Grace")
# base, named, and other have independent immutable query state.
let tx be conn.begin()
tx.table("users").where("id", "=", 1).update({name: "Grace"})
tx.commit()
```

Builders may be reused across requests/tasks with an application-owned
connection. Execution uses the existing SQLite serialization or PostgreSQL
pool. Transaction builders retain the transaction's pinned session and failure
semantics: argument validation or database failure aborts the transaction.
Closing the owning connection, ending a transaction, or finishing a cursor that
owns the connection invalidates its handles, including derived builders. No
builder extends resource lifetime. As with other TLang handle maps, do not
replace method fields in shared handles.

## Forward-only migrations

`migrate(directory)` discovers, validates, and applies pending SQL migrations.
It returns a deterministic summary map:

```tiny
let result be conn.migrate("migrations")
show result.applied
show result.skipped
```

`migrationStatus(directory)` performs the same discovery, checksum, history,
and ordering validation without applying pending SQL. It returns one entry per
discovered file, ordered by numeric version:

```tiny
let status be conn.migrationStatus("migrations")
# [{version: 1, name: "create_users", checksum: "...", state: "applied"}, ...]
```

Migrations are intentionally forward-only. There is no `down`, automatic
rollback migration, schema DSL, model layer, ORM, or automatic
schema generation. Correct a migration that has never applied, or add a new
higher-numbered file for a deployed schema.

### Directory and filename contract

Only immediate regular files in the requested directory are considered.
Subdirectories and hidden files are ignored. Symbolic links in the directory
path or among its entries are rejected. Any visible non-directory entry that
does not match the migration grammar is an error; it is never executed.

The exact filename grammar is:

```text
<version>_<name>.sql

version := one or more ASCII digits, numerically 1..2147483647
name    := a Unicode letter or number, followed by zero or more Unicode
           letters, numbers, combining marks, `_`, `.`, or `-`
suffix  := lowercase `.sql`
```

Examples are `0001_create_users.sql`, `0002_create_sessions.sql`, and
`10_தமிழ்.sql`. Leading zeroes are allowed for readability but are not part of
the identity: `0001_one.sql` and `1_other.sql` are duplicate version `1` and
fail. Ordering is always numeric and never depends on filesystem order.

The path may be relative to the process working directory or absolute. It must
exist, name a readable directory, and must not contain a `..` component.
Migration files must be strict UTF-8 and non-empty after excluding whitespace,
comments, and separators. This intentionally makes missing paths, file paths,
unsupported extensions, malformed names, unreadable files, symlinks, invalid
UTF-8, blank SQL, and comments-only SQL deterministic `DatabaseError` failures.

### History and drift protection

TLang creates `_tlang_migrations` in the target database with these fields:

| Field | Meaning |
| --- | --- |
| `version` | Positive 32-bit migration version and primary identity |
| `name` | Filename name between the underscore and `.sql` |
| `checksum` | Lowercase SHA-256 of the exact file bytes |
| `applied_at` | UTC ISO-8601 application timestamp |

Checksums use the exact bytes that were executed. Consequently LF and CRLF
files have different checksums; repositories should enforce one line-ending
policy and must not rewrite deployed migration files. If an applied version has
a different name or checksum, migration stops before executing pending SQL and
does not rewrite history. Invalid history rows also fail closed.

Gaps are permitted when first applied, such as versions `0001` and `0003`.
After `0003` is recorded, introducing an unapplied `0002` is rejected as
out-of-order. The applied frontier is append-only. History entries created by a
newer application version may be absent from an older checkout; they remain in
the database and still establish the frontier.

### SQL scripts and transactions

Each file may contain multiple statements. TLang scans the complete script and
does not use `split(";")`. Semicolons inside single-quoted strings, quoted
identifiers, line/block comments, PostgreSQL dollar-quoted bodies, and SQLite
trigger `BEGIN ... END` programs are preserved. Trailing SQL without a final
semicolon is executed. Unterminated constructs fail instead of silently
skipping content.

PostgreSQL applies each pending migration and its history row in one database
transaction. SQLite holds one `BEGIN IMMEDIATE` transaction for the migration
run, so every pending file and history row in that run commits together. A
syntax, constraint, later-statement, or history-insert failure rolls back its
transaction: failed SQL has no history row, partial schema/data changes do not
remain where the database supports transactional DDL, and the connection is
usable for a corrected rerun.

Scripts are sent as dialect SQL, not through an interactive client. Client-side
commands such as PostgreSQL `psql` backslash commands are not supported.
Top-level transaction-control statements (`BEGIN`, `START`, `COMMIT`, `END`,
`ROLLBACK`, `SAVEPOINT`, `RELEASE`, and `PREPARE`) are rejected because they could escape
the atomic boundary owned by the migration engine.

### Concurrent deploys and production use

PostgreSQL migration runs acquire a session advisory lock keyed to the current
database and `_tlang_migrations`. SQLite acquires the database write lock with
`BEGIN IMMEDIATE`. These are database-level locks, not Java-only monitors, so
separate TLang processes cannot both apply the same version. A waiting run
rechecks history after acquiring the lock and reports the migration as skipped.

Lock waiting is bounded by `queryTimeoutSeconds`. PostgreSQL polls a nonblocking
advisory-lock attempt; SQLite uses a temporary busy timeout. Success, SQL
failure, validation failure, interruption, and connection close all release the
lock and transaction/pooled connection. Keep migrations short, deploy only one
ordered migration set, back up production data, and grant the configured role
only the DDL privileges those migrations require.

Migration filenames may appear in safe diagnostics. Database targets,
credentials, JDBC details, SQLState values, and raw PostgreSQL server details do
not. HTTP clients continue to receive only the generic 500 response described
in [Runtime diagnostics](../docs/errors.md).

## Transactions

`begin()` returns a transaction handle with the same query/execute/insert/
update/delete methods, `table(name)`, and `repository(table, definition)`, plus `commit()` and `rollback()`.

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

Down migrations, schema DSLs, ORMs, joins in table builders, savepoints/nested
transactions, floating-point values, binary values, and automatic JSON decoding
are not provided. PostgreSQL network failures abort the affected operation or
transaction; subsequent ordinary operations borrow a validated replacement
connection from the pool.
