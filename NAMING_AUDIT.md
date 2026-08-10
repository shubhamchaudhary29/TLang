# TLang Naming Consistency Audit Report

This report presents a naming consistency audit across all native and standard library modules in TLang, identifying multi-word casing inconsistencies and recommending a standard convention.

---

## 1. Casing Convention Inventory

We reviewed the exported function names from all 15 active modules registered in `ModuleRegistry`. The multi-word and compound names are categorized below.

### camelCase (8 functions)
- `strings.isBlank`
- `strings.padLeft`
- `strings.padRight`
- `config.getOr`
- `crypto.hashPassword`
- `crypto.verifyPassword`
- `crypto.compareConstantTime`
- `crypto.hmacSha256`

### snake_case (2 functions)
- `time.elapsed_seconds`
- `math.floor_div`

### Single Lowercase Words & Alphanumerics (37 functions)
These names are single lowercase words (or unified alphanumeric representations) and do not exhibit casing style options:
- **`config`**: `load`, `get`, `require`
- **`log`**: `debug`, `info`, `warn`, `error`
- **`crypto`**: `sign`, `verify`, `sha256`
- **`validate`**: `check`
- **`jwt`**: `sign`, `verify`
- **`mail`**: `send`
- **`cache`**: `set`, `get`, `delete`
- **`filesystem`**: `read`, `write`, `append`, `exists`, `delete`, `list`, `mkdir`, `rmdir`
- **`time`**: `now`
- **`json`**: `stringify`, `parse`
- **`database`**: `open`
- **`random`**: `between`, `boolean`, `choice`, `shuffle`
- **`math`**: `abs`, `max`, `min`, `pow`, `gcd`, `sign`
- **`http`**: `get`, `post`, `put`, `delete`, `server`

---

## 2. Recommendation

We recommend standardizing on **camelCase** for all multi-word standard library function names in TLang. 

### Reasoning:
1. **Prevalence in Standard Library**: 80% of current multi-word functions (8 out of 10) already use camelCase. Only 2 functions use snake_case. Standardizing on camelCase minimizes breaking changes.
2. **Idiomatic Developer Code Usage**: Throughout the existing test suite and application code, TLang developers predominantly use camelCase for custom variables, function parameters, and map keys (e.g. `userId`, `authCounter`, `customVal`, `authHeader`, `expiredPayload`, `expiredToken`). Adopting camelCase for standard library functions aligns with user conventions.
3. **Consistency**: Cleanly resolves all standard library casing discrepancies.

---

## 3. Full Rename List

To align with the recommended **camelCase** convention, the following function renames are proposed:

| Module | Current Name | Proposed Name |
| :--- | :--- | :--- |
| **`time`** | `elapsed_seconds` | `elapsedSeconds` |
| **`math`** | `floor_div` | `floorDiv` |

No other native module function renames are required.

---

## 4. Blast Radius

We scanned the codebase for references to `elapsed_seconds` and `floor_div` inside `.tiny` test files and standard library documentation. The call sites and references requiring updates are detailed below:

### 1. `elapsed_seconds` -> `elapsedSeconds` (5 total references across 2 files)
- **Tests** (1 call site):
  - [src/test/resources/runtime/test_stdlib_time.tiny](file:///home/gigachad/Trash/TLang/src/test/resources/runtime/test_stdlib_time.tiny#L10):
    `let elapsed be time.elapsed_seconds(t1)`
- **Documentation** (4 references):
  - [stdlib/time.md](file:///home/gigachad/Trash/TLang/stdlib/time.md#L13-L14): `#### elapsed_seconds(startTime)`
  - [stdlib/time.md](file:///home/gigachad/Trash/TLang/stdlib/time.md#L28): `let duration be time.elapsed_seconds(start)`
  - [stdlib/time.md](file:///home/gigachad/Trash/TLang/stdlib/time.md#L36): `Argument to 'elapsed_seconds' must be an integer.`

### 2. `floor_div` -> `floorDiv` (7 total references across 2 files)
- **Tests** (2 call sites):
  - [src/test/resources/runtime/test_stdlib_math.tiny](file:///home/gigachad/Trash/TLang/src/test/resources/runtime/test_stdlib_math.tiny#L14):
    `show math.floor_div(10, 3)`
  - [src/test/resources/runtime/test_stdlib_math.tiny](file:///home/gigachad/Trash/TLang/src/test/resources/runtime/test_stdlib_math.tiny#L17):
    `show math.floor_div(-10, 3)`
- **Documentation** (5 references):
  - [stdlib/math.md](file:///home/gigachad/Trash/TLang/stdlib/math.md#L33-L34): `#### floor_div(a, b)`
  - [stdlib/math.md](file:///home/gigachad/Trash/TLang/stdlib/math.md#L61-L62): `show math.floor_div(7, 2)` / `show math.floor_div(-7, 2)`
  - [stdlib/math.md](file:///home/gigachad/Trash/TLang/stdlib/math.md#L81): `Dividing by zero in 'floor_div'`

---

## 5. Verb-Level Check

We audited verb choices across all standard library modules to check for consistency:
- **`delete`**: Shared by `filesystem.delete`, `cache.delete`, and `http.delete` (all represent the deletion or removal of an object, file, or resource. This is fully consistent).
- **`get`**: Shared by `config.get`, `cache.get`, and `http.get` (all retrieve resources, values, or configuration keys. This is fully consistent).
- **`write` / `append`**: Used in `filesystem` for direct output operations.
- **`query` / `update`**: Used in database connection contexts, representing structured queries.

No verb-level discrepancies or conflicting synonyms were found. The standard library verb usage is already highly consistent.
