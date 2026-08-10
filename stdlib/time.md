# time

## Purpose
Enables retrieving the current system time and calculating intervals or elapsed time in seconds.

## API

#### `now()`
- **Signature**: `now()`
- **Return Type**: `Number`
- **Description**: Returns the current UNIX epoch timestamp in seconds (integer).

#### `elapsedSeconds(startTime)`
- **Signature**: `elapsedSeconds(startTime: Number)`
- **Return Type**: `Number`
- **Description**: Calculates the difference in seconds between the current time and the provided start timestamp.

---

## Examples

### 1. Timing an Operation
```tiny
import time

let start be time.now()
// ... perform some work ...
let duration be time.elapsedSeconds(start)
show "Operation completed in " + duration + " seconds."
```

---

## Errors
- **Argument validation**:
  - Passing a non-integer to `elapsedSeconds` throws a `RuntimeError`: `Argument to 'elapsedSeconds' must be an integer.`

---

## Notes
- **Granularity**: The time module measures time in whole seconds. Fine-grained milliseconds or microseconds are not supported.
- **System Clock Dependency**: Resolution depends on the host system clock (`System.currentTimeMillis()`).
