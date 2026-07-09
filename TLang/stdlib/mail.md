# mail

## Purpose
Provides capability for sending emails via SMTP, leveraging configuration variables for server connectivity and authentication.

---

## Design Constraint (SMTP Library Integration)
Because the JDK standard library does not provide a built-in SMTP client, and there is no build-tool dependency management in place yet, we must decide how to handle SMTP client logic:
1. **Option 1: Minimal Vendored SMTP Jar (Recommended)**
   Add a lightweight, self-contained SMTP library jar (e.g. a minimal SMTP-only package) to `lib/` and update the execution classpath.
   - *Pros*: Handles STARTTLS security negotiations, SMTP authentication, and headers cleanly. Saves significant coding overhead and avoids security vulnerabilities.
   - *Cons*: Adds a dependency to `lib/` before the automated build system is in place.
2. **Option 2: Hand-Rolled SMTP Client**
   Establish raw TCP/SSL connections using `java.net.Socket` and `javax.net.ssl.SSLSocket` and communicate manually via raw SMTP command strings (`EHLO`, `STARTTLS`, `AUTH LOGIN`, `MAIL FROM`, `RCPT TO`, `DATA`).
   - *Pros*: Zero new dependencies.
   - *Cons*: Highly prone to edge-case bugs, protocol errors across different SMTP servers, and STARTTLS downgrade attack vulnerabilities (if TLS negotiation is not strictly verified).

> [!IMPORTANT]
> **Maintainer Recommendation**: We recommend Option 1. Designing protocol/crypto-level negotiation manually increases correctness and security risks that are not worth taking on when the native-module pattern allows simple jar integration.
> **Note**: This recommendation requires explicit maintainer confirmation before Phase 4b (mail module implementation) proceeds.

---

## API

#### `send(to, subject, body)`
- **Signature**: `send(to: String, subject: String, body: String)`
- **Return Type**: `Null`
- **Description**: Sends an email with the specified subject and body to the recipient's address. SMTP configuration is dynamically loaded from environment variables using the `config` standard library module:
  - `SMTP_HOST`: Hostname of the SMTP server.
  - `SMTP_PORT`: Port of the SMTP server.
  - `SMTP_USERNAME`: Username for server authentication.
  - `SMTP_PASSWORD`: Password for server authentication.
  - `SMTP_SECURE`: Security protocol (e.g., `"tls"`, `"ssl"`, or `"none"`).

---

## Examples

### 1. Sending an Email
```tiny
import mail

// Assumes SMTP environment variables are set in .env
mail.send("customer@example.com", "Welcome!", "Thank you for signing up.")
```

---

## Errors

### 1. Programmer Misuse (Throws `RuntimeError`)
Invalid argument types passed to `mail` functions represent developer misuse and will throw a `RuntimeError` terminating execution:
- `First argument to 'send' must be a string.`
- `Second argument to 'send' must be a string.`
- `Third argument to 'send' must be a string.`

### 2. Operational/Infrastructure Failures (Throws `RuntimeError`)
Unlike JWT verification, an email send failure represents an exceptional runtime infrastructure anomaly (e.g., mail server unreachable, credential rejection, network timeout). It will throw a `RuntimeError` to propagate up:
- `SMTP connection failed: <details>`
- `SMTP authentication failed: <details>`
- `SMTP message sending failed: <details>`

---

## Notes
- To prevent credentials leakage and simplify APIs, all connection configuration is strictly gathered from the environment via the `config` module, rather than passed as function parameters.
