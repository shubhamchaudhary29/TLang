# http

## Purpose
Enables building HTTP servers and making client-side HTTP requests (GET, POST, PUT, DELETE) to integrate with external APIs and services.

Client requests are synchronous. Server handlers execute concurrently on a bounded fixed worker pool. Every exchange receives an isolated interpreter execution cursor, request map, and response buffer while retaining access to program globals and closures.

## API

### Client-Side Functions

#### `get(url)` / `get(url, headers)`
- **Signature**: `get(url: String, headers: Map = {})`
- **Return Type**: `Map`
- **Description**: Performs a synchronous HTTP GET request. Returns a map with keys `status` (Number), `ok` (Boolean), `body` (String), and `headers` (Map).

#### `post(url, body)` / `post(url, body, headers)`
- **Signature**: `post(url: String, body: String, headers: Map = {})`
- **Return Type**: `Map`
- **Description**: Performs a synchronous HTTP POST request with a payload body. Returns a map with keys `status`, `ok`, `body`, and `headers`.

#### `put(url, body)` / `put(url, body, headers)`
- **Signature**: `put(url: String, body: String, headers: Map = {})`
- **Return Type**: `Map`
- **Description**: Performs a synchronous HTTP PUT request with a payload body. Returns a map with keys `status`, `ok`, `body`, and `headers`.

#### `delete(url)` / `delete(url, headers)`
- **Signature**: `delete(url: String, headers: Map = {})`
- **Return Type**: `Map`
- **Description**: Performs a synchronous HTTP DELETE request. Returns a map with keys `status`, `ok`, `body`, and `headers`.

---

### Server-Side Constructor

#### `server(port)`
- **Signature**: `server(port: Number)`
- **Return Type**: `Map` (Server connection object)
- **Description**: Instantiates a new HTTP server listening on the specified port.

---

### Server Object Methods
The returned server map exposes the following chainable handler and control methods:

#### `get(path, handler)`
- **Signature**: `server.get(path: String, handler: Function)`
- **Return Type**: `Map` (Returns the server instance for chaining)
- **Description**: Registers a request handler for HTTP GET requests on the specified path.

#### `post(path, handler)`
- **Signature**: `server.post(path: String, handler: Function)`
- **Return Type**: `Map` (Returns the server instance for chaining)
- **Description**: Registers a request handler for HTTP POST requests on the specified path.

#### `put(path, handler)`
- **Signature**: `server.put(path: String, handler: Function)`
- **Return Type**: `Map` (Returns the server instance for chaining)
- **Description**: Registers a request handler for HTTP PUT requests on the specified path.

#### `delete(path, handler)`
- **Signature**: `server.delete(path: String, handler: Function)`
- **Return Type**: `Map` (Returns the server instance for chaining)
- **Description**: Registers a request handler for HTTP DELETE requests on the specified path.

#### `use(middleware)`
- **Signature**: `server.use(middleware: Function)`
- **Return Type**: `Map` (Returns the server instance for chaining)
- **Description**: Registers a global middleware function in the request pipeline.

#### `start()`
- **Signature**: `server.start()`
- **Return Type**: `Null`
- **Description**: Starts the HTTP server in the background and publishes the registered route/middleware snapshot. Registration after this call is rejected.

#### `stop()`
- **Signature**: `server.stop()`
- **Return Type**: `Null`
- **Description**: Stops the listener, gives active exchanges a short grace period, and shuts down owned workers. A stopped server object cannot be restarted.

---

### Request (`req`) Map Structure
Handlers receive a `req` map representing the incoming HTTP request:
- `method`: `String` (e.g. `"GET"`, `"POST"`)
- `path`: `String` (the requested resource path)
- `body`: `String` (raw request payload body)
- `json`: `Map` or `List` (parsed request payload body if `Content-Type` contains `application/json` and the body is valid JSON; otherwise `null`)
- `headers`: `Map` (incoming header keys are lowercased)
- `query`: `Map` (query parameters parsed from URL)
- `params`: `Map` (wildcard/path parameters like `:id`)

### Response (`res`) Map Structure
Handlers receive a `res` map representing the outgoing HTTP response, exposing the following chainable methods:
- `status(code: Number)`: Sets the HTTP status code (returns `res`).
- `header(name: String, value: String)`: Sets a response header (returns `res`).
- `text(body: String)`: Ends the response sending text/plain payload (returns `null`).
- `json(value: Object)`: Stringifies `value` and ends the response sending application/json payload (returns `null`).
- `send(body: String)`: Ends the response sending raw string payload (returns `null`).

---

## Examples

### 1. HTTP Client GET Request
```tiny
import http

let res be http.get("https://api.github.com/users/octocat", {"User-Agent": "TLang"})
if res.ok
    show "Response body: " + res.body
otherwise
    show "Error code: " + res.status
```

### 2. Creating a simple Server with parameters and status codes
```tiny
import http

let server be http.server(8080)

server.get("/users/:id", function taking req and res
    let userId be req.params.id
    res.status(200).json({"user": userId, "status": "active"})
)

server.start()
```

### 3. Middleware execution
```tiny
import http

let server be http.server(8080)

server.use(function taking req and res and next
    show "Incoming request: " + req.method + " " + req.path
    next()
)

server.get("/", function taking req and res
    res.text("Hello World")
)

server.start()
```

---

## Errors
- **Type mismatch**: Passing non-string URLs or bodies, or a non-integer port, throws a `RuntimeError`:
  - `First argument to 'get' must be a string URL.`
  - `Headers argument to 'get' must be a map (got ...).`
  - `Port must be an integer.`
  - `Port must be in the range 1-65535 (got ...).`
- **Connection Failure**: If a client-side HTTP request fails, it throws an `HttpError`:
  - `HTTP request to '...' failed: <reason>`
- **Port already in use / Startup failure**:
  - `Failed to start HTTP server on port 8080: Address already in use`
- **Double Response / Middleware mistakes**:
  - Attempting to send a response more than once: `Response already sent. Exactly one response should ever be sent.`
  - Calling `next()` more than once in middleware: `next() called more than once.`
  - If a route handler completes execution without calling a response-sending method or invoking `next()`, the pipeline raises: `No response was sent by the handler or middleware.`
- **Lifecycle mistakes**:
  - Registering a route or middleware after start: `Routes and middleware cannot be registered after the HTTP server starts.`
  - Restarting a stopped server: `HTTP server instances cannot be restarted after stop().`
- **Handler failures**: A detailed source-aware TLang diagnostic is written
  server-side, while the remote client receives only status `500` with body
  `Internal Server Error`. Source paths, stack frames, request secrets, native
  causes, and Java implementation details are never returned to the client.

---

## Notes
- **Concurrency**: Each exchange runs on a named fixed-size pool worker and uses a fresh interpreter execution cursor. The default worker count is the available-processor count clamped to 4–32 and can be overridden with `-Dtlang.http.workers=N`; the waiting queue is bounded.
- **State semantics**: Parameters, locals, stack depth, request data, and response state are request-local. Global bindings, closures, imported modules, lists, and maps remain shared. Individual global binding and collection operations are synchronized, but compound read-modify-write expressions are not atomic transactions.
- **Route publication**: Routes and middleware are immutable while serving, so register all of them before `start()`.
- **Header Case**: Incoming request header keys are lower-cased automatically (e.g. `req.headers.get("authorization")`).
- **Synchronous Execution**: All client requests block the interpreter thread until completion or timeout (default timeout is 10 seconds).
- **Architecture details**: See [Concurrent HTTP runtime](../docs/concurrent-runtime.md).
- **Diagnostic details**: See [Runtime diagnostics](../docs/errors.md).
- **Lambda inside Map/List Literals**: Inline lambdas (`function taking req and res ...`) can be defined directly inside map/list literals (the lexer handles restoring newline tracking for the lambda block). For cleaner code organization, you can also declare the lambda first, store it in a variable, and reference it inside the map/list literal.
