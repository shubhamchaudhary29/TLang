# http

## Purpose
Enables building HTTP servers and making client-side HTTP requests (GET, POST, PUT, DELETE) to integrate with external APIs and services.

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
- **Description**: Starts the HTTP server. This method blocks the executing thread as it listens for incoming requests.

#### `stop()`
- **Signature**: `server.stop()`
- **Return Type**: `Null`
- **Description**: Synchronously stops the HTTP server.

---

### Request (`req`) Map Structure
Handlers receive a `req` map representing the incoming HTTP request:
- `method`: `String` (e.g. `"GET"`, `"POST"`)
- `path`: `String` (the requested resource path)
- `body`: `String` (raw request payload body)
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
- **Connection Failure**: If a client-side HTTP request fails, it throws a `RuntimeError`:
  - `HTTP request to '...' failed: <reason>`
- **Port already in use / Startup failure**:
  - `Failed to start HTTP server on port 8080: Address already in use`
- **Double Response / Middleware mistakes**:
  - Attempting to send a response more than once: `Response already sent. Exactly one response should ever be sent.`
  - Calling `next()` more than once in middleware: `next() called more than once.`
  - If a route handler completes execution without calling a response-sending method or invoking `next()`, the pipeline raises: `No response was sent by the handler or middleware.`

---

## Notes
- **Thread Safety**: The HTTP server runs on a background thread pool managed by Java's `HttpServer`, but all handler dispatching is serialized via synchronizing on the single-threaded tree-walking interpreter (`synchronized (interpreter)`).
- **Header Case**: Incoming request header keys are lower-cased automatically (e.g. `req.headers.get("authorization")`).
- **Synchronous Execution**: All client requests block the interpreter thread until completion or timeout (default timeout is 10 seconds).
