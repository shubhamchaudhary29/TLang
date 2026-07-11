# TLang Language Reference Guide

This document provides a readable, developer-friendly guide to writing code in TLang. For the formal grammar, lexical grammar, and precise AST definitions, please refer to the authoritative **[Language Specification (SPEC.md)](../SPEC.md)**.

---

## 1. Types and Literals

TLang has a dynamic but simple type system supporting six core data types:

### String
Strings are double-quoted and support character escaping (e.g. `\n`, `\t`, `\\`, `\"`). They also support **string interpolation** using `${expression}` syntax.
```tiny
let name be "Alice"
show "Hello, ${name}!"
show "Result: ${1 + 2 * 3}"
```

### Number
All numbers in TLang are 64-bit floating-point values (equivalent to `double` in Java).

### Boolean
Represented by the literals `true` and `false`.

### nil
The `nil` literal represents the absence of a value (equivalent to `null` in other languages). If a function does not return a value, it implicitly returns `nil`.

### List
Lists are ordered collections of values. They are zero-indexed and can store mixed types.
```tiny
let items be [1, "two", true, nil]
show items[1]  # Output: "two"
```

### Map
Maps are collections of key-value pairs. Keys can be unquoted identifiers (if they are valid identifiers and not keywords) or double-quoted strings.
```tiny
let user be {
    name: "Bob",
    "role-type": "admin",
    age: 30
}
show user.name        # Output: "Bob"
show user["role-type"] # Output: "admin"
```

---

## 2. Variables and Assignments

Variables are bound using `let ... be` and reassigned using `set ... to`. There are no implicit declarations or uninitialized variables.

```tiny
let count be 0          # Declaration
set count to count + 1   # Mutation
```

### Index and Property Assignment
You can mutate nested values in lists and maps using the same assignment syntax:
```tiny
let user be {name: "Bob", scores: [10, 20]}
set user.name to "Robert"
set user.scores[0] to 15
```

---

## 3. Control Flow

TLang uses indentation-based blocking (4 spaces per level). No curly braces `{}` or parentheses `()` around conditions are required.

### If/Otherwise
Conditional branches are evaluated in-order. An optional `otherwise` block runs if the condition is false.
```tiny
if score >= 90
    show "A"
otherwise
    if score >= 80
        show "B"
    otherwise
        show "C"
```

### Loops
TLang supports two loops:

1. **While Loop**: Runs as long as the condition evaluates to `true`.
   ```tiny
   let i be 0
   while i < 5
       show i
       set i to i + 1
   ```

2. **Repeat Loop**: Runs a block of code a fixed number of times. The loop variable counts from `0` to `count - 1`.
   ```tiny
   repeat 5 times as index
       show "Loop index: ${index}"
   ```

### Loop Control: Break and Continue
- `break` exits the nearest enclosing loop immediately.
- `continue` skips the rest of the current loop iteration and proceeds to the next cycle.

---

## 4. Functions and Lambdas

### Function Declarations
Functions are declared globally or inside other scopes using `define`. Parameters are defined after the `taking` keyword, separated by `and`. Parameters can also specify default values using the `be` keyword.

```tiny
define multiply taking a and b be 1
    return a * b

show multiply(5, 2)  # Output: 10
show multiply(5)     # Output: 5
```

### Return Statement
The `return` statement exits a function immediately and returns a value. If no value is specified (a bare `return`), or if the function completes without hitting a `return`, it returns `nil`.

### Lambdas (Anonymous Functions)
Lambdas are first-class anonymous functions created using the `function` keyword. They capture variables from their enclosing scope (lexical closures).

```tiny
let addOne be function taking x
    return x + 1

show addOne(10)  # Output: 11
```

---

## 5. Multi-line Literals

For lists and maps containing complex elements (like nested maps or lambdas), you can declare them across multiple lines. The parser supports comma separators. The formatter collapses them into a single line if they fit under 80 characters, or lists each entry on a separate line otherwise.

```tiny
let handlers be [
    function taking req
        show "Handler 1"
    ,
    function taking req
        show "Handler 2"
]
```

---

## 6. Modules and Imports

TLang is modular. You can import built-in standard library modules or local `.tiny` source files as modules using the `import` statement.

```tiny
import log
import math

log.info("Calculating division...")
show math.floor_div(7, 2)
```

For more details on what modules are available, see the **[Standard Library Reference](stdlib/index.md)**.
