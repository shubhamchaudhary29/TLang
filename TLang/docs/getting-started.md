# Getting Started with TLang

Welcome to TLang! TLang is an opinionated, domain-specific programming language designed from the ground up to be **the easiest language for backend web development**. Instead of competing as a general-purpose language, TLang prioritizes a simple syntax, a robust built-in native standard library, and absolute safety for web services.

---

## Installation

### Prerequisites
- **Java 21** or later (JDK).
- **Bash** shell (for Linux, macOS, or WSL).

### Build from Source
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

---

## Next Steps

Once you've run your first script, check out these references for deeper learning:
- **[Language Reference](language-reference.md)**: Explore the detailed language syntax, multi-line list/map literals, lambdas, and imports.
- **[Standard Library Reference](stdlib/index.md)**: Browse the documentation for built-in modules like `http`, `db` (SQLite), `jwt`, `json`, and more.
- **[Example: Auth Service](examples/auth-service.md)**: Read a walkthrough of a complete JSON API backend authentication service written in TLang.
