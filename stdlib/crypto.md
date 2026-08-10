# crypto

## Purpose
Provides secure cryptographic operations, including slow-salted password hashing, constant-time comparisons to prevent timing attacks, and signature utilities for JSON Web Tokens (JWT).

## API

#### `hashPassword(password)`
- **Signature**: `hashPassword(password: String)`
- **Return Type**: `String`
- **Description**: Generates a slow, salted hash of the password using PBKDF2WithHmacSHA256. Used for secure database storage of user credentials. Returns a formatted string: `pbkdf2$<iterations>$<saltHex>$<hashHex>`.

#### `verifyPassword(password, hash)`
- **Signature**: `verifyPassword(password: String, hash: String)`
- **Return Type**: `Boolean`
- **Description**: Verifies a plaintext password against a previously generated PBKDF2 hash.

#### `compareConstantTime(a, b)`
- **Signature**: `compareConstantTime(a: String, b: String)`
- **Return Type**: `Boolean`
- **Description**: Compares two strings in constant time. Used to verify security tokens or secrets without leaking timing information.

#### `hmacSha256(data, secret)`
- **Signature**: `hmacSha256(data: String, secret: String)`
- **Return Type**: `String` (Hex encoded)
- **Description**: Generates an HMAC-SHA256 signature of `data` using the provided `secret` key. Needed for JWT signature creation and validation.

#### `sha256(data)`
- **Signature**: `sha256(data: String)`
- **Return Type**: `String` (Hex encoded)
- **Description**: Generates a fast SHA-256 hash of the input string. Not to be used for storing passwords.

---

## Examples

### 1. Password Hashing and Verification
```tiny
import crypto

let plainPassword be "super-secure-pass"
let hashedPassword be crypto.hashPassword(plainPassword)

// Save hashedPassword to DB...

let matches be crypto.verifyPassword("wrong-pass", hashedPassword) // false
let matchesCorrect be crypto.verifyPassword(plainPassword, hashedPassword) // true
```

### 2. Constant-Time API Token Comparison
```tiny
import crypto

let expectedToken be "token_abc123"
let incomingToken be "token_abc123"

if crypto.compareConstantTime(expectedToken, incomingToken)
    show "Tokens match!"
```

### 3. Signing data using HMAC-SHA256
```tiny
import crypto

let payload be "header.payload"
let secret be "my-jwt-secret"
let signature be crypto.hmacSha256(payload, secret)
```

---

## Errors
- **Argument validation**:
  - Passing non-string arguments to any function: `Argument to '...' must be a string.` (or `"First argument to '...' must be a string."` for multi-argument functions).
- **Invalid Password Hash Format**:
  - Passing an invalid hash string to `verifyPassword`: `Invalid password hash format.`

---

## Notes
- **Password Hashing Security Constraint**: `hashPassword` uses PBKDF2WithHmacSHA256 with a default work factor of 100,000 iterations and a random 16-byte salt to prevent dictionary and brute-force attacks.
- **Timing attacks**: Standard string comparison (`==`) short-circuits on the first differing character, leaking timing details. Always use `compareConstantTime` when verifying signatures, tokens, or credentials.
