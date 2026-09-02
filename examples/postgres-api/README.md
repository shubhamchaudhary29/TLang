# PostgreSQL notes API

This small example uses the standard `db` module, a bounded PostgreSQL pool,
prepared parameters, `INSERT ... RETURNING`, and concurrent HTTP handlers.

Create a database and export its configuration (or put the same keys in a
local `.env` file):

```bash
export DATABASE_URL=postgresql://127.0.0.1:5432/tlang_notes
export DATABASE_USER=tlang
export DATABASE_PASSWORD=change-me
export PORT=8080
./gradlew installDist
build/install/tlang/bin/tlang run examples/postgres-api/app.tiny
```

Create and list notes:

```bash
curl -H 'content-type: application/json' \
  -d '{"content":"prepared and pooled"}' \
  http://127.0.0.1:8080/notes
curl http://127.0.0.1:8080/notes
```

The handle is intentionally global for the process lifetime: concurrent
handlers borrow separate connections up to `poolSize`. Production process
shutdown should stop the server and call `connection.close()` from the
embedding lifecycle.
