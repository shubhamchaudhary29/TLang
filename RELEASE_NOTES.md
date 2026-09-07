# TLang v0.4.0

TLang v0.4.0 expands the existing SQLite support into a more complete database
layer for backend applications while preserving direct SQL access.

## Highlights

- PostgreSQL support with bounded HikariCP connection pools, configurable waits,
  statement timeouts, transaction isolation, and broken-connection recovery.
- Provider-neutral transactions and safe parameter binding for SQLite and
  PostgreSQL.
- Deterministic forward-only migrations with numeric ordering, SHA-256 history,
  transactional application, PostgreSQL advisory locks, and SQLite serialization.
- An immutable query builder for common filtering, sorting, pagination, and
  guarded CRUD operations.
- Lightweight repositories with explicit primary-key, field, and read-only
  contracts.
- Stronger database lifecycle, concurrency, error translation, credential
  redaction, and HTTP/task cleanup behavior.
- Real SQLite and PostgreSQL integration, security, and repeated stress coverage.

## Database API levels

Use the repository layer for concise CRUD by primary key:

```tiny
let users be connection.repository("users", {
    primaryKey: "id",
    fields: ["id", "name", "email", "active"]
})
let user be users.find(42)
```

Use the query builder for structured filtering and pagination:

```tiny
let active be users.query().where("active", "=", true).limit(20).all()
```

Use parameterized raw SQL for joins, expressions, provider-specific features,
casts, and generated keys:

```tiny
let rows be connection.query("SELECT id FROM users WHERE email = ?", [email])
```

## Installers

This release includes native installers for Linux (`.deb`), macOS (`.pkg`), and
Windows (`.msi`). GitHub also provides source archives for the `v0.4.0` tag.

## Important limitations

- Migrations are forward-only.
- The repository layer is not a full ORM and has no relationships or composite keys.
- There is no automatic schema creation or synchronization.
- Query-builder joins and nested boolean-expression APIs are not provided.
- Advanced and provider-specific SQL continues to use the raw SQL APIs.

TLang remains a pre-1.0 language. Review the
[database reference](stdlib/db.md) for the exact API, portability rules, and
current limits.
