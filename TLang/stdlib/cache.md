# cache

## Purpose
Provides a process-local, in-memory key-value cache with Time-To-Live (TTL) expiration support.

---

## Design Choices
- **Lazy Expiry-on-Read**: To align with TLang's single-threaded execution model and avoid introducing background worker threads (which would violate the simple execution philosophy), eviction is performed lazily. Expired keys are checked and deleted only when `get()` is called.
- **Value Preservation**: Since the cache operates entirely in-process within the running JVM environment, it supports storing any native TLang value type (`NUMBER`, `STRING`, `BOOLEAN`, `LIST`, `MAP`) without requiring serializing or parsing overhead.
- **Missing Keys**: Requesting a missing key or an expired key returns `null` (nil) rather than throwing a runtime error.

---

## API

#### `set(key, value, ttl_seconds)`
- **Signature**: `set(key: String, value: Any, ttl_seconds: Number)`
- **Return Type**: `Null`
- **Description**: Stores a value in the cache under the specified key, with a expiration duration in seconds.

#### `get(key)`
- **Signature**: `get(key: String)`
- **Return Type**: `Any` (or `Null`)
- **Description**: Retrieves the value associated with the key. If the key does not exist or has expired, returns `null`.

#### `delete(key)`
- **Signature**: `delete(key: String)`
- **Return Type**: `Boolean`
- **Description**: Removes the key and its value from the cache. Returns `true` if the key was present, `false` otherwise.

---

## Examples

### 1. Basic Cache Usage
```tiny
import cache
import time

// Store a map with a 5-second expiry
cache.set("session_123", {userId: 42, role: "admin"}, 5)

// Retrieve immediately
let session be cache.get("session_123")
show session.userId // 42

// Wait for expiration (simulated)
time.sleep(6)

// Retrieves null after expiry
let expiredSession be cache.get("session_123")
show expiredSession // nil
```

---

## Errors

### 1. Programmer Misuse (Throws `RuntimeError`)
Invalid parameter count or types passed to native functions represents developer misuse and will throw a `RuntimeError`:
- `set` parameter validation:
  - `First argument to 'set' must be a string.`
  - `Third argument to 'set' must be an integer.`
- `get` parameter validation:
  - `Argument to 'get' must be a string.`
- `delete` parameter validation:
  - `Argument to 'delete' must be a string.`

---

## Notes
- Memory usage: Since eviction is lazy, unaccessed keys will occupy memory until the application restarts or they are explicitly overwritten or deleted.
