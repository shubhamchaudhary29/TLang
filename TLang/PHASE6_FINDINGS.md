# PHASE6_FINDINGS

Findings, friction points, and crash behavior analysis from building the Real Auth Service Backend in TLang.

## Request-Time Crash Behavior & Robustness

### Findings
When a request handler throws a `RuntimeError` (e.g., division by zero, database constraint violation, or bad type coercion) or a generic Java exception, **the HTTP server does NOT crash**. It catches the exception, responds to the client with an HTTP 500 status code, and continues running successfully to serve subsequent requests.

### Source Code Evidence
In `dev/tlang/runtime/http/ServerOps.java`, lines 98–115:
```java
synchronized (interpreter) {
    try {
        runChain(0, reqMap, resWrapper, interpreter, exchange);

        // Fallback check: if the entire chain finished (including dispatch/short-circuit) and no response sent
        if (!resWrapper.isSent()) {
            Token dummyToken = new Token(dev.tlang.lexer.TokenType.IDENTIFIER, "handler", null, 1);
            throw new RuntimeError(dummyToken, "No response was sent by the handler or middleware.");
        }
        resWrapper.flush();
    } catch (RuntimeError e) {
        // Respond 500, do not crash server
        sendErrorResponse(exchange, 500, "Runtime Error: " + e.getMessage());
    } catch (Exception e) {
        // Convert escaped Java exception to 500
        sendErrorResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
    }
}
```

### Integration Test Evidence
In our `test_auth_service.sh` integration suite, we verify:
1. Hit `/crash` (which performs division by zero `1 / 0`).
2. Status code returned is `500`.
3. Body contains `"Runtime Error: Division by zero."`.
4. Subsequent request to `/me` succeeds with `200 OK`, showing the server is fully alive.

---

## Friction Points & Classification

### 1. Missing Keyword Literal for `nil` / `null`
- **What Happened**: There is no keyword in the grammar representing the null value. Attempting to use `nil` directly causes a semantic compilation error (`Undefined variable 'nil'`). We had to declare a helper function `define getNull` returning implicitly, assign it to a global variable `let null be getNull()`, and return `null` when exiting route handlers early.
- **Classification**: **Language Gap** (requires `SPEC.md` updates).
- **Proposed Fix**: Add a literal keyword `nil` (or `null`) to the Lexer and Parser, evaluating directly to Java's `null` representation inside the Interpreter.
- **Status**: Resolved in Phase 7b. Added the `nil` keyword, which parses as a literal null expression.

### 2. Strict Newlines Prevent Multi-Line Maps and Lists
- **What Happened**: Declaring maps or lists across multiple lines (such as validation schemas or configuration maps) fails parsing with `Expected map key (identifier or string)` because newlines are treated as statement separators.
- **Classification**: **Language Gap** (requires parser adjustment).
- **Proposed Fix**: Modify `Parser.java` to skip `TokenType.NEWLINE` when parsing inside balanced curly braces `{}` or square brackets `[]`.
- **Status**: Resolved in Phase 7b. Suppressed newline and indentation processing in the Lexer when inside unclosed map braces `{}` or list brackets `[]`. Documented the nested inline lambda limitation.

### 3. Port Configuration Requires `Type.NUMBER` But Config Exposes `Type.STRING`
- **What Happened**: Starting an HTTP server requires an integer port (`http.server(port)`), but `config.getOr` returns a string. TLang lacks helper parsing functions (e.g. `toInt`), so we had to use `json.parse(portVal)` as a workaround to deserialize `"8087"` into `8087`.
- **Classification**: **Stdlib Gap** (missing conversion function).
- **Proposed Fix**: Add string parsing functions to the standard library (e.g., `strings.toInt(str)` or `math.parseInt(str)`).
- **Status**: Resolved in Phase 7a. Added `strings.toNumber(str)` to parse decimal integer strings to numbers.

### 4. Boilerplate for Parsing and Validating Request Bodies
- **What Happened**: `req.body` is exposed as a raw string. Every JSON route must call `let bodyMap be json.parse(req.body)` before applying validations, resulting in repetitive boilerplate.
- **Classification**: **Stdlib Gap**.
- **Proposed Fix**: Expose a pre-parsed `req.json` or `req.parsedBody` map automatically in the request wrapper when the incoming request has a `Content-Type: application/json` header.
- **Status**: Resolved in Phase 7a. Added `req.json` field to request map containing parsed JSON when request has `application/json` content-type header and is valid JSON, otherwise `null`.

### 5. `conn.insert` Returns Rows Affected Instead of Generated Key
- **What Happened**: The database `conn.insert` returns the number of affected rows (typically `1`) instead of the auto-incremented primary key. We had to perform a secondary query `SELECT last_insert_rowid() as id` to fetch the generated user ID.
- **Classification**: **Stdlib Gap**.
- **Proposed Fix**: Modify `DatabaseModule.java`'s `insert` method to retrieve generated keys using `Statement.RETURN_GENERATED_KEYS` and return the inserted row ID.
- **Status**: Resolved in Phase 7a. Retained existing contract for `conn.insert` but added a new method `conn.lastInsertId()` which returns the SQLite `last_insert_rowid()` value.

