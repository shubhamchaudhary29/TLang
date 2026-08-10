# jwt

## Purpose
Provides utilities for generating and verifying JSON Web Tokens (JWT) using HMAC-SHA256, integrating standard library capabilities of JSON serialization and cryptography.

---

## API

#### `sign(payload, secret)`
- **Signature**: `sign(payload: Map, secret: String)`
- **Return Type**: `String`
- **Description**: Generates an HMAC-SHA256 signed JSON Web Token (JWT) representing the `payload` map, using the provided `secret`. If the payload map does not contain an `"exp"` claim, a default expiration of 1 hour from the current system time is automatically added.
- **Under the Hood**:
  - The header defaults to `{"alg": "HS256", "typ": "JWT"}`.
  - The JWT is structured as `base64url(header) + "." + base64url(payload) + "." + base64url(signature)`.
  - The signature is calculated by taking `crypto.hmacSha256(headerBase64Url + "." + payloadBase64Url, secret)`. Since `crypto.hmacSha256` returns a hex-encoded string, the sign function converts the hex signature back to bytes before base64url-encoding it for the token's third segment.

#### `verify(token, secret)`
- **Signature**: `verify(token: String, secret: String)`
- **Return Type**: `Map`
- **Description**: Verifies the signature of the provided `token` against the `secret` key. Also validates the `exp` claim against the current time. Returns a validation result map with keys:
  - `valid`: `Boolean` (`true` if valid and not expired, `false` otherwise).
  - `payload`: `Map` (the decoded payload map if valid; empty map if invalid/expired/tampered).
- **Under the Hood**:
  - The token is split by `.`.
  - Recomputes the expected HMAC-SHA256 signature as hex.
  - Base64url-decodes the received token signature to bytes, converts it to hex, and compares the expected hex signature and received hex signature in constant-time using `crypto.compareConstantTime`.
  - Checks if `exp` (seconds since Unix epoch) is less than the current time (via `time.now()`).

---

## Examples

### 1. Generating and Verifying a JWT
```tiny
import jwt
import time

let payload be {
    "userId": 42,
    "role": "admin",
    "exp": time.now() + 3600
}
let secret be "my-super-secret-key"

let token be jwt.sign(payload, secret)
let result be jwt.verify(token, secret)

if result.valid
    show "Authenticated user: " + result.payload.userId
```

---

## Errors

### 1. Programmer Misuse (Throws `RuntimeError`)
Invalid argument types passed to `jwt` functions represent developer misuse and will throw a `RuntimeError` terminating execution:
- `sign` parameter validation:
  - `First argument to 'sign' must be a map.`
  - `Second argument to 'sign' must be a string.`
- `verify` parameter validation:
  - `First argument to 'verify' must be a string.`
  - `Second argument to 'verify' must be a string.`

### 2. Routine Verification Failure (Returned as Data)
Expected token anomalies do **not** throw errors; they are returned gracefully as data via a map of the structure `{valid: false, payload: {}}`:
- Token tampering or malformed structure (not containing exactly 3 segments).
- Signature validation failure (incorrect secret or modified payload).
- Expired token (payload `exp` Unix timestamp is in the past).
- Base64url-decoding failure or JSON payload parsing failure.

---

## Notes
- To maintain security, the signature check uses constant-time comparison via `crypto.compareConstantTime` to defend against timing attacks.
- Only the `HS256` (HMAC-SHA256) algorithm is supported.
