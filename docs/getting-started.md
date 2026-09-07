# Getting Started with TLang

TLang is a small, dynamically typed scripting language for straightforward backend APIs. It prioritizes simple syntax and a built-in native standard library. It is dynamically typed, and its HTTP runtime handles requests concurrently with isolated per-request execution state.

---

## Installation

### Native installers

Download the TLang v0.4.0 installer for your platform from the
[GitHub release](https://github.com/shubhamchaudhary29/TLang/releases/tag/v0.4.0):

- Linux: `tlang_0.4.0_amd64.deb`
- macOS: `TLang-0.4.0.pkg`
- Windows: `TLang-0.4.0.msi`

After installation, `tlang version` should print `TLang version 0.4.0`.

### Build from Source

Building from source requires a Java 21 JDK and Bash on Linux, macOS, or WSL.

1. Clone the repository:
   ```bash
   git clone https://github.com/shubhamchaudhary29/TLang.git
   cd TLang
   ```
2. Build the distribution package using the Gradle Wrapper:
   ```bash
   ./gradlew installDist
   ```
   This will compile the Java source files, resolve dependencies, and generate a startup executable script under the `build/install/tlang/bin/` folder.

---

## Your First Program

Let's create a minimal "Hello, World!" program.

1. Create a file named `hello.tiny` in your working directory and add the following code:
   ```tiny
   show "Hello, World!"
   ```
2. Execute the script using the compiled CLI executable:
   ```bash
   build/install/tlang/bin/tlang run hello.tiny
   ```
3. You should see the following output printed to the terminal:
   ```
   Hello, World!
   ```

---

## Core Syntax Basics

TLang is highly structured and uses indentation for blocking (block statements are indented with **4 spaces**). Here are the core constructs to help you start writing TLang:

### 1. Variables
Variables are declared using the `let ... be` keyword syntax and can be updated using `set ... to`.

```tiny
let message be "Hello, TLang"
set message to "Hello, World!"
show message
```

### 2. Control Flow
Conditional execution is handled by `if` and `otherwise` blocks.

```tiny
let age be 20

if age >= 18
    show "Adult"
otherwise
    show "Minor"
```

### 3. Loops
TLang supports traditional `while` loops as well as a simplified `repeat` loop for running a block of code a specific number of times.

```tiny
# While loop
let count be 0
while count < 3
    show count
    set count to count + 1

# Repeat loop
repeat 3 times as i
    show "Iteration: ${i}"
```

### 4. Functions
Functions are declared using the `define` keyword. Parameters can also declare default values using the `be` syntax.

```tiny
define greet taking name and greeting be "Hello"
    show "${greeting}, ${name}!"

greet("Alice")          # Output: Hello, Alice!
greet("Bob", "Welcome")  # Output: Welcome, Bob!
```

## Start a project

Standalone scripts need no configuration. For a reproducible multi-file
project, create a manifest and lock its dependencies:

```bash
mkdir my_api
cd my_api
tlang init
tlang add utils --path ../utils
tlang install
tlang run main.tiny
```

Commit `tlang.toml` and `tlang.lock`, but not `.tlang/`. A cloned project can
then reproduce the same pinned Git graph with `tlang install`; use `--offline`
when the exact commits are already cached. Read [Projects and package
management](packages.md) for the manifest schema, package entry modules,
updates, import precedence, and security model.

---

## Next Steps

Once you've run your first script, check out these references for deeper learning:
- **[Language Reference](language-reference.md)**: Explore the detailed language syntax, multi-line list/map literals, lambdas, and imports.
- **[Standard Library Reference](stdlib/index.md)**: Browse the documentation for built-in modules like `http`, `db` (SQLite/PostgreSQL queries and migrations), `jwt`, `json`, and more.
- **[Example: Auth Service](examples/auth-service.md)**: Read a walkthrough of a complete JSON API backend authentication service written in TLang.
- **[Projects and Packages](packages.md)**: Build reproducible projects with local and pinned Git dependencies.

## Common database CRUD

Create tables through [SQL migrations](../stdlib/db.md#forward-only-migrations),
then use immutable table handles for ordinary reads and writes:

```tiny
import db
let connection be db.open("app.db")
connection.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)", [])
let users be connection.table("users")
users.insert({id: 1, name: "Ada"})
show users.where("id", "=", 1).first()
users.where("id", "=", 1).update({name: "Grace"})
users.where("id", "=", 1).delete()
connection.close()
```

`insert`, `update`, and `delete` return affected-row counts. Update/delete require
filters. See [structured CRUD](../stdlib/db.md#safe-structured-crud) for nil,
pagination, transactions, limits, and when to use raw SQL.

## Repositories for repeated CRUD

Once your schema exists, use a repository to define which fields routine reads
return and which fields writes accept:

```tiny
import db
let connection be db.open("app.db")
let users be connection.repository("users", {primaryKey: "id", fields: ["id", "name"]})
users.create({id: 2, name: "Ada"})
show users.find(2)
users.update(2, {name: "Grace"})
show users.query().orderBy("id", "asc").all()
users.delete(2)
connection.close()
```

This uses the `users` table from the previous example. Repositories do not create
tables; use migrations for a deployed schema. Choose repository methods for CRUD
by ID, query builders for filters/pagination, and raw SQL for advanced expressions
or generated keys. See [repository contracts](../stdlib/db.md#lightweight-repositories).
