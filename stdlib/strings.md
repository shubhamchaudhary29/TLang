# strings

## Purpose
Provides helper functions for manipulating, formatting, padding, and inspecting string data.

## API

#### `join(list, separator)`
- **Signature**: `join(list: List, separator: String)`
- **Return Type**: `String`
- **Description**: Joins elements of a list into a single string separated by the separator.

#### `repeat(str, count)`
- **Signature**: `repeat(str: String, count: Number)`
- **Return Type**: `String`
- **Description**: Returns a string repeated `count` times.

#### `reverse(str)`
- **Signature**: `reverse(str: String)`
- **Return Type**: `String`
- **Description**: Reverses the character order of a string.

#### `isBlank(str)`
- **Signature**: `isBlank(str: String)`
- **Return Type**: `Boolean`
- **Description**: Checks if a string is empty or contains only whitespace.

#### `padLeft(str, width, padChar)`
- **Signature**: `padLeft(str: String, width: Number, padChar: String)`
- **Return Type**: `String`
- **Description**: Pads the left side of a string with a single character until it reaches the specified width.

#### `padRight(str, width, padChar)`
- **Signature**: `padRight(str: String, width: Number, padChar: String)`
- **Return Type**: `String`
- **Description**: Pads the right side of a string with a single character until it reaches the specified width.

#### `toNumber(str)`
- **Signature**: `toNumber(str: String)`
- **Return Type**: `Number`
- **Description**: Parses a string as an integer and returns it as a `NUMBER`. Throws a `RuntimeError` if the string is not a valid integer.

---

## Examples

### 1. Joining and Repeating
```tiny
import strings

let tags be ["tech", "tlang", "backend"]
show strings.join(tags, ", ") // "tech, tlang, backend"

show strings.repeat("ab", 3) // "ababab"
```

### 2. Padding Strings
```tiny
import strings

show strings.padLeft("42", 5, "0")  // "00042"
show strings.padRight("42", 5, " ") // "42   "
```

---

## Errors
- **Argument validation**:
  - `First argument to 'join' must be a list.`
  - `Second argument to 'join' must be a string separator.`
  - `First argument to 'repeat' must be a string.`
  - `Second argument to 'repeat' must be an integer count.`
  - `Argument to 'reverse' must be a string.`
  - `Argument to 'isBlank' must be a string.`
  - `Arguments to 'padLeft' must be (string, integer, string).`
  - `Arguments to 'padRight' must be (string, integer, string).`
  - `Argument to 'toNumber' must be a string.`
- **Argument constraint failures**:
  - Negative repeat count: `Repeat count must be non-negative.`
  - Padding string length must be exactly 1: `Padding character must be a string of length 1.`
  - Invalid integer string: `'toNumber' expected a numeric string, got 'abc'.`

---

## Notes
- **String indexing**: Characters inside strings are 0-indexed when converted or accessed via native methods.
- **Unicode Support**: String functions operate on UTF-16 code units (standard Java `String` characters).

---

## Naming inconsistency flagged
The module mixes two different casing standards:
- Lowercase: `join`, `repeat`, `reverse`
- CamelCase: `isBlank`, `padLeft`, `padRight`

These must be audited and standardized (e.g. to lowercase or camelCase) in the Phase 5 styling pass.
