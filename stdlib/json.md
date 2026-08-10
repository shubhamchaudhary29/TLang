# json

## Purpose
Provides capabilities to serialize TLang data structures (lists, maps, primitives) to JSON strings, and parse JSON strings back into native TLang maps, lists, and primitives.

## API

#### `stringify(value)`
- **Signature**: `stringify(value: Object)`
- **Return Type**: `String`
- **Description**: Serializes a TLang object (number, boolean, string, list, map, or nil) into its JSON string representation.

#### `parse(jsonString)`
- **Signature**: `parse(jsonString: String)`
- **Return Type**: `Object`
- **Description**: Deserializes a JSON string into its corresponding native TLang representation (number, boolean, string, list, map, or nil).

---

## Examples

### 1. Parsing a JSON String
```tiny
import json

let dataString be "{\"name\": \"Alice\", \"age\": 30, \"active\": true}"
let user be json.parse(dataString)

show user.get("name") // Alice
show user.get("age") // 30
```

### 2. Serializing a Map to JSON
```tiny
import json

let payload be {
    "title": "New Event",
    "tags": ["tech", "tlang"],
    "completed": false
}

let jsonString be json.stringify(payload)
show jsonString // {"title":"New Event","tags":["tech","tlang"],"completed":false}
```

---

## Errors
- **Argument validation**:
  - `Argument to 'parse' must be a string.`
- **Invalid serialization**:
  - Attempting to serialize a function or lambda: `Cannot serialize a function to JSON.`
  - Attempting to serialize a map with non-string keys: `JSON object keys must be strings.`
- **Syntax and Parse Errors**:
  - Parsing a malformed JSON string throws `RuntimeError` stating the parser position, expected token, or syntax error (e.g. `Expected ':' after key`).

---

## Notes
- **Key ordering**: Map keys are preserved in their insertion order (uses Java's `LinkedHashMap` under the hood) during parsing and stringification.
- **Escape Sequences**: `stringify` handles escaping common JSON control characters like `\n`, `\t`, `\r`, `\b`, `\f`, `\"`, and `\\`.
- **Numbers**: Since TLang only supports integers, floating-point numbers encountered during parsing will fail, or be rounded/treated as syntax errors depending on the json reader constraints.
