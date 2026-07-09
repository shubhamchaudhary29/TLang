# TLang Language Philosophy

## 1. North Star

TLang's goal is to be the easiest language for building backends — not to compete with Rust on speed or Python on ecosystem size, but to win on developer experience.

---

## 2. Core Rules

### Rule 1 — Grammar changes are extremely rare.
The core grammar of TLang is frozen, as specified in `SPEC.md`. Designing or proposing new keywords, syntax constructs, or AST node shapes requires a formal revision of the language specification itself rather than an incidental addition during standard library or native module expansion. Keeping the grammar stable guarantees tooling predictability (such as LSP or formatter implementations) and avoids language complexity creep.

### Rule 2 — Features are added through native modules, not syntax.
Whenever a new feature is proposed, it should be built as a native module using the `NativeModule` interface (`dev.tlang.modules.NativeModule`), which exposes exports via a single `getExports()` method mapping names to native functions. The existing modules (`http`, `db`, `json`, `filesystem`, `math`, `random`, `strings`, `time`) demonstrate that powerful features can be integrated seamlessly into the language without changing a single line of parser or grammar logic.

### Rule 3 — Developer experience beats clever syntax.
If a language or library design choice makes the interpreter's implementation more elegant or compact but introduces confusing, verbose, or overly clever semantics at the user's call site, it must be rejected. The focus is strictly on the readability and simplicity of the `.tiny` files written by backend developers.

### Rule 4 — Backend-first.
When choosing between design alternatives that are otherwise equal, the design that directly supports common backend services (e.g., HTTP request/response handling, data persistence, configuration, authentication, validation) must be chosen over designs suited for general-purpose scripting or systems programming.

### Rule 5 — One obvious way to do things.
There should be exactly one clear, standard way to perform any given task in TLang. Stdlib and native modules must not expose redundant functions that perform the same job under different names, nor should different modules provide overlapping methods for the same concept (for example, reading environment variables should only exist in the `config` module, not inside general-purpose system or utility modules).

---

## 3. Explicitly Out of Scope

The following features are **explicitly out of scope** for TLang v1.0. Any future design document, feature proposal, or automated plan that reintroduces any of these items (such as a VM, GC, or try/catch) as if they were still open questions must be rejected against this document:

- **No try/catch exception handling**: Errors propagate up and terminate execution.
- **No floating-point numbers**: All numeric operations are integer-only.
- **No formal class syntax**: Objects are dynamic maps; there is no inheritance or prototype chain.
- **No concurrent/async request handling**: Execution is single-threaded and synchronous.
- **No bytecode VM**: The interpreter is a tree-walking interpreter running directly on the Java AST, and the host JVM handles memory management.

---

## 4. Naming Consistency Standard

All native and standard library modules in TLang must adhere to a strict naming convention to ensure predictability. Verbs used for common operations must remain consistent across all modules:
- **Read operations** must use `get` (e.g., `config.get()`, `map.get()`).
- **Write/Create operations** must use consistent, domain-appropriate verbs within a category (e.g., database operations use `db.query`/`db.insert`/`db.update`/`db.delete`, rather than mix-and-matching verbs like `fetch` or `modify`).
- **Logging operations** must use severity-named methods (e.g., `log.info()`, `log.error()`), not synonyms like `log.output()` or `log.write()`.

### Bad vs. Good Examples

| Category | Bad Design | Good Design |
| :--- | :--- | :--- |
| **HTTP client** | `http.fetch()` / `http.request()` | `http.get()` / `http.post()` |
| **Database** | `db.retrieve()` / `db.change()` | `db.query()` / `db.update()` |
| **Email** | `mail.sendEmail()` | `mail.send()` |
| **Configuration** | `config.obtain()` / `config.read()` | `config.get()` |
| **Logging** | `logger.output()` / `logger.write()` | `log.info()` / `log.warn()` / `log.error()` |

> [!NOTE]
> This standard applies retroactively. Existing native modules (`math`, `http`, `db`, `json`, `filesystem`, `random`, `strings`, `time`) will be audited and adjusted to conform to this naming standard as part of subsequent standard library documentation (Phase 2) and code style cleaning (Phase 5) iterations.

---

## 5. Decision Filter

Before any new feature, API, or module is proposed, it must pass through the following checklist. If any step fails, the proposal should be rejected or revised to fit TLang's philosophy:

1. **Does this require a grammar, parser, or AST change?**
   - *If yes*: It requires a formal revision of `SPEC.md` first and should be resisted by default (Rule 1).
2. **Can this be built as a native module using the existing `NativeModule` pattern?**
   - *If yes*: Build it as a module rather than introducing language-level syntax (Rule 2).
3. **Does this make backend development easier for most users?**
   - *If no*: It does not belong in the TLang core. Consider if it should be an external tool, a third-party package, or if it is simply out of scope (Rule 3 & Rule 4).
4. **Does this follow the naming consistency standard (Section 4)?**
   - *If no*: The methods/verbs must be renamed to match the established patterns before being merged (Rule 5).
