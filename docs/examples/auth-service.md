# Example Walkthrough: Authentication Service

This walkthrough guides you through `examples/auth_service.tiny`, a production-style, database-backed JSON API service implementing user signup, login, and profile lookup using JWT authentication.

This example showcases how TLang's domain-specific design allows you to build a complete authentication backend in **under 150 lines of code**.

---

## 1. Imports and Setup

The application starts by importing the necessary standard library modules:
```tiny
import config
import log
import crypto
import validate
import jwt
import http
import db
import json
```

Configuration is loaded from the environment or a `.env` file automatically using `config.load()`. We load the port, the JWT signature secret key, and the SQLite database path:
```tiny
config.load()
let portVal be config.getOr("PORT", "8080")
let port be json.parse(portVal)
let jwtSecret be config.getOr("JWT_SECRET", "super-secret-key")
let dbPath be config.getOr("DB_PATH", "auth.db")
```

---

## 2. Database Initialization

We open an embedded SQLite database using `db.open(...)` and create the `users` table:
```tiny
let conn be db.open(dbPath)
conn.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE, password_hash TEXT)", [])
```

---

## 3. Declarative Schema Validation

Before processing user credentials, we declare validation schemas using the `validate` module. This prevents malformed data or sql-injection attempts from reaching the logic:
```tiny
let signupSchema be {
    email: {
        type: "string", 
        required: true, 
        pattern: "^[a-zA-Z0-9+_.-]+@[a-zA-Z0-9.-]+$"
    }, 
    password: {
        type: "string", 
        required: true, 
        min: 6
    }
}
```

---

## 4. Endpoints and Request Handlers

### Signup (`POST /signup`)
The signup handler validates the inputs, hashes the password using bcrypt via `crypto.hashPassword`, and inserts the new user:
```tiny
let validationResult be validate.check(bodyMap, signupSchema)
if not validationResult.valid
    res.status(400).json({"errors": validationResult.errors})
    return null
```
If the email already exists in the database, SQLite's unique constraint is checked, and we return a `400 Bad Request`. Otherwise, we hash the password and insert:
```tiny
let hash be crypto.hashPassword(password)
conn.insert("INSERT INTO users (email, password_hash) VALUES (?, ?)", [email, hash])
```

### Login (`POST /login`)
The login handler verifies the email and checks the password hash using `crypto.verifyPassword`:
```tiny
let isPasswordCorrect be crypto.verifyPassword(password, storedHash)
if not isPasswordCorrect
    res.status(401).json({"error": "Invalid email or password"})
    return null
```
On success, it signs a stateless JWT token using `jwt.sign`:
```tiny
let payload be {userId: user.get("id"), email: email}
let token be jwt.sign(payload, jwtSecret)
res.json({"token": token})
```

### Profile Profile (`GET /me`)
The profile endpoint reads the `Authorization` header, extracts the Bearer token, and verifies it:
```tiny
let jwtResult be jwt.verify(token, jwtSecret)
if not jwtResult.valid
    res.status(401).json({"error": "Invalid token"})
    return null
```
> [!NOTE]
> In accordance with TLang's developer-experience-focused design, `jwt.verify` returns a result map containing `.valid` and `.payload` fields instead of raising an exception. This makes validation flow control highly explicit and clean.

---

## 5. Crash Resilience

TLang's HTTP server is highly robust against route failures. The `/crash` route forces a division by zero:
```tiny
let crashHandler be function taking req and res
    let x be 1 / 0
```
When this handler executes, it raises a `RuntimeError`. The HTTP server catches this error, prints a clean error log, responds to the client with `500 Internal Server Error`, and **continues running** to serve subsequent requests without crashing the process.

---

## 6. How to Run the App & Tests

### Step 1: Start the Service
From the repository root, start the server:
```bash
./gradlew installDist
build/install/tlang/bin/tlang run examples/auth_service.tiny
```

### Step 2: Run the Integration Tests
TLang provides a ready-made Bash integration test suite verifying validation, authentication, and error handling:
```bash
bash examples/test_auth_service.sh
```
All curl calls, response bodies, status codes, and post-crash server resilience are fully tested.
