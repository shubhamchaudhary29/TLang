# log

## Purpose
Provides a unified logging utility supporting standard severity levels (debug, info, warn, error) and structured (JSON) outputs for observability.

## API

#### `debug(message)` / `debug(message, fields)`
- **Signature**: `debug(message: String, fields: Map = {})`
- **Return Type**: `Null`
- **Description**: Outputs a log entry at the `DEBUG` level with the given message and optional metadata fields.

#### `info(message)` / `info(message, fields)`
- **Signature**: `info(message: String, fields: Map = {})`
- **Return Type**: `Null`
- **Description**: Outputs a log entry at the `INFO` level with the given message and optional metadata fields.

#### `warn(message)` / `warn(message, fields)`
- **Signature**: `warn(message: String, fields: Map = {})`
- **Return Type**: `Null`
- **Description**: Outputs a log entry at the `WARN` level with the given message and optional metadata fields.

#### `error(message)` / `error(message, fields)`
- **Signature**: `error(message: String, fields: Map = {})`
- **Return Type**: `Null`
- **Description**: Outputs a log entry at the `ERROR` level with the given message and optional metadata fields.

---

## Examples

### 1. Plain Logging
```tiny
import log

log.info("Server startup initiated.")
log.warn("Database pool running low on connections.")
```

### 2. Structured Logging with Fields
```tiny
import log

let fields be {
    "ip": "127.0.0.1",
    "elapsed_ms": 150,
    "user_id": 412
}

log.info("HTTP request completed successfully", fields)
log.error("Failed to process payment", {"transaction_id": "tx_998", "reason": "insufficient_funds"})
```

---

## Errors
- **Argument validation**:
  - If `message` is not a string: `First argument to log functions must be a string.`
  - If `fields` is not a map: `Second argument to log functions must be a map.`

---

## Notes
- **Output stream**: `debug`, `info`, and `warn` logs are printed to standard output (`stdout`), while `error` logs are printed to standard error (`stderr`).
- **Structured format**: Logs are printed as a JSON string to stdout/stderr in a single line, containing fields like `"timestamp"`, `"level"`, `"message"`, and the custom payload fields.
