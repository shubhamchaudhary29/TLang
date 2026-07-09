# db

## Purpose
Provides native SQLite database access for reading, writing, updating, and deleting structured data in backend applications.

## API

### Top-Level Module Functions

#### `open(path)`
- **Signature**: `open(path: String)`
- **Return Type**: `Map` (Database connection object)
- **Description**: Opens a connection to a SQLite database. Use `":memory:"` for an in-memory database.

---

### Connection Object Methods
The database connection map returned by `open` exposes the following methods:

#### `query(sql, params)`
- **Signature**: `conn.query(sql: String, params: List)`
- **Return Type**: `List` (of Maps)
- **Description**: Runs a SQL query (SELECT) and returns the result set as a list of maps, where each map maps column labels to runtime values.

#### `execute(sql, params)`
- **Signature**: `conn.execute(sql: String, params: List)`
- **Return Type**: `Number` (Integer)
- **Description**: Executes a non-query SQL command (like CREATE TABLE) and returns the number of affected rows.

#### `insert(sql, params)`
- **Signature**: `conn.insert(sql: String, params: List)`
- **Return Type**: `Number` (Integer)
- **Description**: Alias for `execute`. Executes an INSERT statement and returns the number of affected rows.

#### `update(sql, params)`
- **Signature**: `conn.update(sql: String, params: List)`
- **Return Type**: `Number` (Integer)
- **Description**: Alias for `execute`. Executes an UPDATE statement and returns the number of affected rows.

#### `delete(sql, params)`
- **Signature**: `conn.delete(sql: String, params: List)`
- **Return Type**: `Number` (Integer)
- **Description**: Alias for `execute`. Executes a DELETE statement and returns the number of affected rows.

#### `close()`
- **Signature**: `conn.close()`
- **Return Type**: `Null`
- **Description**: Closes the database connection.

---

## Examples

### 1. Database connection and Table creation
```tiny
import db

let conn be db.open("app.db")
conn.execute("CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY, msg TEXT)", [])
conn.close()
```

### 2. Inserting and Querying Rows
```tiny
import db

let conn be db.open(":memory:")
conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)", [])

// Insert values
let rowsAffected be conn.insert("INSERT INTO users (name, age) VALUES (?, ?)", ["Bob", 28])
show rowsAffected // 1

// Query values
let results be conn.query("SELECT * FROM users WHERE age > ?", [20])
let firstUser be results.get(0)
show firstUser.get("name") // "Bob"
conn.close()
```

---

## Errors
- **Argument / Parameter validation**:
  - `Database path must be a string.`
  - `SQL query must be a string.`
  - `Parameters must be a list.`
- **Parameter count mismatch**:
  - `Expected 2 parameters, but got 1.` (If the number of placeholders `?` in the SQL string does not match the size of the parameter list).
- **Unsupported SQLite / Java Types**:
  - Blobs are not supported: `BLOB type is not supported in this version of TLang database wrapper (future work).`
  - Unsupported parameters: `Unsupported parameter type: ...`
  - Real fractional values (e.g. `2.5`): `Floating point values with nonzero fractional parts (like 2.5) are not supported in TLang.`
  - Integer overflow: SQLite 64-bit integer values exceeding 32-bit bounds will throw `Integer overflow: SQLite value ... does not fit in a 32-bit integer.`
- **Connection errors**:
  - Performing operations on a closed connection: `Connection is closed.`
  - Database Driver issues: `SQLite JDBC driver not found on classpath: ...`
  - Internal SQLite syntax errors or constraints: `Database error: [SQLITE_ERROR] SQL error or missing database ...`

---

## Notes
- **JDBC Driver dependency**: The wrapper relies on the presence of the `org.sqlite.JDBC` driver in the classpath.
- **Type Mapping**:
  - SQLite `INTEGER` corresponds to TLang `NUMBER`.
  - SQLite `TEXT` corresponds to TLang `STRING`.
  - SQLite `NULL` maps to TLang `nil`.
  - SQLite `REAL` is supported only if it represents a whole number (i.e. has a zero fractional part), converting directly to a TLang `NUMBER`.
  - SQLite booleans (stored as integer `1` or `0`) can be queried, but inserting boolean parameters requires manual coercion or passes `1`/`0` directly under the hood.
