# Concurrent API example

This service exposes health, CPU-work, echo, and completion-summary routes. Its
global function and completion list remain visible to every handler, while each
request's `req`, `res`, parameters, headers, body, locals, recursion state, and
response buffer are isolated.

Start it from the repository root:

```bash
./gradlew installDist
build/install/tlang/bin/tlang run examples/concurrent-api/app.tiny
```

In another shell, issue ten work requests concurrently:

```bash
seq 1 10 | xargs -P 10 -I ID curl -s http://127.0.0.1:8088/work/ID
curl -s http://127.0.0.1:8088/completed
```

Every work response retains its own request ID. The final route shows all ten
atomic list additions; their order is intentionally scheduler-dependent.
