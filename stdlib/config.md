# config

## Purpose
Provides a standard, predictable interface for reading application configuration variables from system environment variables and `.env` files.

## API

#### `load()`
- **Signature**: `load()`
- **Return Type**: `Null`
- **Description**: Searches for a `.env` file in the current working directory and loads its key-value pairs into the environment. If the file is not found, this is a silent no-op.

#### `get(key)`
- **Signature**: `get(key: String)`
- **Return Type**: `String` (or `nil` if not set)
- **Description**: Returns the value of the environment variable specified by `key`. Returns `nil` if the key is not defined.

#### `getOr(key, defaultValue)`
- **Signature**: `getOr(key: String, defaultValue: String)`
- **Return Type**: `String`
- **Description**: Returns the value of the environment variable specified by `key`. Returns `defaultValue` if the key is not defined.

#### `require(key)`
- **Signature**: `require(key: String)`
- **Return Type**: `String`
- **Description**: Retrieves the value of the environment variable specified by `key`. Throws a `RuntimeError` if the key is not defined.

---

## Examples

### 1. Load `.env` and Read Configuration
```tiny
import config
import strings

config.load()

let port be strings.toNumber(config.getOr("PORT", "8080"))
let dbUrl be config.require("DATABASE_URL")

show "Running on port: " + port
```

### 2. Optional vs Required Configurations
```tiny
import config

config.load()

// Required keys throw if missing, preventing startup with bad config
let stripeSecret be config.require("STRIPE_API_KEY")

// Optional keys fallback to defaults
let debugMode be config.getOr("DEBUG_MODE", "false")
```

---

## Errors
- **Argument validation**:
  - Passing a non-string argument to any function throws a `RuntimeError`: `Argument to '...' must be a string.`
- **Missing Required Key**:
  - Calling `require` on a missing key: `RuntimeError: Configuration key 'DATABASE_URL' is required but not set.`

---

## Notes
- **Precedence**: System environment variables always take precedence over values loaded from a `.env` file.
- **Types**: All configuration values are returned as `String` representations. The caller must manually convert them to other types (e.g. using `strings.toNumber`).
- **Load timing**: `load()` should typically be called once at the entry point of the application before reading any config keys.
