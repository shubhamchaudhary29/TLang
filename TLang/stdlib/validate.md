# validate

## Purpose
Enables validation of dynamic input maps (such as parsed JSON requests or form submissions) against a declarative schema map defining type, value, and structure constraints.

## API

#### `check(data, schema)`
- **Signature**: `check(data: Map, schema: Map)`
- **Return Type**: `Map`
- **Description**: Validates the input `data` map against the structural rules defined in the `schema` map. Returns a verification map with keys:
  - `valid`: `Boolean` (`true` if all rules pass, `false` otherwise).
  - `errors`: `Map` (mapping invalid field names to error messages describing the validation failure).

---

### Schema Mapping and Validation Rules
Schemas are standard nested maps where keys represent data fields, and values represent nested maps of rules:
- `required`: `Boolean` (if `true`, the field must be present and not `nil`).
- `type`: `String` (must be one of: `"string"`, `"number"`, `"boolean"`, `"list"`, `"map"`).
- `min`: `Number` (minimum value for numbers, or minimum length for strings and lists).
- `max`: `Number` (maximum value for numbers, or maximum length for strings and lists).
- `pattern`: `String` (regular expression string that string fields must match).
- `in`: `List` (list of acceptable exact values).

---

## Examples

### 1. User Registration Validation
```tiny
import validate

let schema be {
    "email": {
        "type": "string",
        "required": true,
        "pattern": "^[a-zA-Z0-9+_.-]+@[a-zA-Z0-9.-]+$"
    },
    "age": {
        "type": "number",
        "min": 18
    },
    "role": {
        "type": "string",
        "in": ["user", "admin"]
    }
}

let inputData be {
    "email": "invalid-email",
    "age": 16,
    "role": "guest"
}

let result be validate.check(inputData, schema)
if result.valid
    show "Data is valid!"
otherwise
    show result.errors.get("email") // "email must match pattern ..."
    show result.errors.get("age")   // "age must be at least 18"
```

---

## Errors
- **Argument validation**:
  - If `data` is not a map: `First argument to 'check' must be a map.`
  - If `schema` is not a map: `Second argument to 'check' must be a map.`
- **Invalid Schema Rules**:
  - Throws `RuntimeError` if an unsupported rule key or value type is found in the schema definition (e.g. `Invalid validation type 'float' in schema.`).

---

## Notes
- **Nested validation**: Missing optional fields that are not marked `required` are skipped and do not produce errors.
- **Synchronous Execution**: The validation is performed synchronously in a single pass.
