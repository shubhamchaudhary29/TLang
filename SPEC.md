# TLang Language Specification (v1.0 Language Freeze)

This document contains the official specification of the TLang programming language. TLang is an indentation-based, tree-walking interpreted programming language.

---

## 1. Lexical Structure

### Token Categories
All tokens in TLang belong to one of the following categories defined in `TokenType.java`:

- **Structure Tokens**: 
  - `NEWLINE`: Terminates statements.
  - `INDENT`: Indicates the start of an indented block.
  - `DEDENT`: Indicates the end of an indented block.
- **Punctuation**:
  - `LEFT_PAREN` `(`, `RIGHT_PAREN` `)`
  - `LEFT_BRACKET` `[`, `RIGHT_BRACKET` `]`
  - `LEFT_BRACE` `{`, `RIGHT_BRACE` `}`
  - `COMMA` `,`, `COLON` `:`, `DOT` `.`
- **Operators**:
  - Arithmetic: `PLUS` `+`, `MINUS` `-`, `STAR` `*`, `SLASH` `/`, `PERCENT` `%`
  - Comparison: `EQUAL_EQUAL` `==`, `BANG_EQUAL` `!=`, `GREATER` `>`, `GREATER_EQUAL` `>=`, `LESS` `<`, `LESS_EQUAL` `<=`
  - Boolean: `AND` (`and`), `OR` (`or`), `NOT` (`not`)
- **Literals**:
  - `NUMBER`: Integer literals (e.g. `42`).
  - `STRING`: String literals with support for escapes (`\"`, `\\`, `\n`, `\t`) and string interpolation (`${expression}`).
  - `IDENTIFIER`: Variables, function names, and property access names.
- **Keywords**:
  - Variable declaration/assignment: `LET` (`let`), `BE` (`be`), `SET` (`set`), `TO` (`to`)
  - Imports: `IMPORT` (`import`)
  - Output: `SHOW` (`show`)
  - Control Flow: `IF` (`if`), `OTHERWISE` (`otherwise`), `WHILE` (`while`), `BREAK` (`break`), `CONTINUE` (`continue`), `REPEAT` (`repeat`), `TIMES` (`times`), `AS` (`as`)
  - Functions & Lambdas: `DEFINE` (`define`), `TAKING` (`taking`), `RETURN` (`return`), `FUNCTION` (`function`)
  - Boolean values: `TRUE` (`true`), `FALSE` (`false`)
  - Null/nil values: `NIL` (`nil`)

### Block Structure
TLang is an indentation-based language (Python-style) and does not use curly braces or semicolons for block boundaries.
- **Indentation Tracking**: The Lexer keeps track of indentation levels using a stack (with an initial level of `0`).
  - An increase in leading spaces/tabs on a logical line emits an `INDENT` token.
  - A decrease in leading spaces/tabs pops the indentation stack and emits `DEDENT` tokens until the indentation matches an enclosing level.
  - Tabs are treated as equivalent to `4` spaces.
- **Line Handling**: Single-line comments starting with `//` and blank lines are ignored for indentation tracking.
- **Brace/Bracket Insignificant Newlines**: Newlines (`\n`) and their associated indentation (leading whitespace) are treated as insignificant whitespace and ignored by the lexer when they occur inside unclosed map/object braces `{...}` or list brackets `[...]`. This allows maps and lists to span multiple lines. Crucially, this newline suppression does **not** occur inside unclosed function call parentheses `(...)` to prevent interfering with nested inline lambda statements.
- **Lambda inside Map/List Literals**: Inline lambdas (anonymous functions) can be declared directly inside map or list literals. The lexer temporarily restores newline/indentation tracking for the lambda block. However, for readability and styling, it is often cleaner to declare the lambda first, store it in a variable, and then reference that variable inside the map/list literal.

### Position and Column Tracking
- **Convention**: Line and column tracking are **1-indexed**.
- **Synthetic Lexer Tokens**: Synthetic structure tokens (`NEWLINE`, `INDENT`, `DEDENT`, `EOF`) generated during lexing receive a column calculated as `current - lineStart + 1` which points to their location at the time of emission.
- **Synthetic AST Tokens**: Tokens generated dynamically by the parser during desugaring (such as the `<` and `+` tokens for `repeat` loops) receive a default column value of `1` on the line of the currently parsed token.
- **String positions**: A string token is located at its opening quote, even when its contents span physical lines. Newlines inside strings advance the location used for subsequent tokens. Line and column values are always positive.

---

## 2. Grammar

### Formal EBNF Grammar

```ebnf
program        ::= ( NEWLINE | statement )* EOF ;

statement      ::= varDecl
                 | assignment
                 | showStmt
                 | ifStmt
                 | whileStmt
                 | breakStmt
                 | continueStmt
                 | repeatStmt
                 | functionDecl
                 | returnStmt
                 | importStmt
                 | exprStmt ;

importStmt     ::= "import" IDENTIFIER NEWLINE ;

varDecl        ::= "let" IDENTIFIER "be" expression NEWLINE ;

assignment     ::= "set" target "to" expression NEWLINE ;

target         ::= IDENTIFIER
                 | call "[" expression "]"   (* Index assignment *)
                 | call "." IDENTIFIER ;     (* Field assignment *)

showStmt       ::= "show" expression NEWLINE ;

ifStmt         ::= "if" expression NEWLINE block ( "otherwise" NEWLINE block )? ;

whileStmt      ::= "while" expression NEWLINE block ;

breakStmt      ::= "break" NEWLINE ;

continueStmt   ::= "continue" NEWLINE ;

repeatStmt     ::= "repeat" expression "times" "as" IDENTIFIER NEWLINE block ;

functionDecl   ::= "define" IDENTIFIER [ "taking" params ] NEWLINE block ;

params         ::= param ( "and" param )* ;

param          ::= IDENTIFIER [ "be" expression ] ;

returnStmt     ::= "return" [ expression ] NEWLINE ;

exprStmt       ::= expression NEWLINE ;

block          ::= INDENT statement+ DEDENT ;

expression     ::= logicOr ;

logicOr        ::= logicAnd ( "or" logicAnd )* ;

logicAnd       ::= equality ( "and" equality )* ;

equality       ::= comparison ( ( "==" | "!=" ) comparison )* ;

comparison     ::= term ( ( "<" | "<=" | ">" | ">=" ) term )* ;

term           ::= factor ( ( "+" | "-" ) factor )* ;

factor         ::= unary ( ( "*" | "/" | "%" ) unary )* ;

unary          ::= ( "not" | "-" ) unary
                 | call ;

call           ::= primary ( "(" arguments? ")" | "[" expression "]" | "." IDENTIFIER )* ;

arguments      ::= expression ( "," expression )* ;

primary        ::= NUMBER
                 | STRING
                 | "true"
                 | "false"
                 | "nil"
                 | IDENTIFIER
                 | "(" expression ")"
                 | "function" [ "taking" params ] NEWLINE block
                 | "[" [ expression ( "," expression )* ] "]"
                 | "{" [ mapEntry ( "," mapEntry )* ] "}" ;

mapEntry       ::= ( IDENTIFIER | STRING ) ":" expression ;
```

### Precedence Table
The following table lists operators from lowest precedence (parsed first) to highest precedence (parsed last), corresponding to the recursive descent methods in `Parser.java`:

| Precedence | Operator / Syntax | Description | Parser Rule |
| :--- | :--- | :--- | :--- |
| 1 | `or` | Logical OR | `logicOr` |
| 2 | `and` | Logical AND | `logicAnd` |
| 3 | `==`, `!=` | Equality and Inequality | `equality` |
| 4 | `<`, `<=`, `>`, `>=` | Relational Comparisons | `comparison` |
| 5 | `+`, `-` | Addition, Subtraction (String Concatenation) | `term` |
| 6 | `*`, `/`, `%` | Multiplication, Division, Modulo | `factor` |
| 7 | `not`, `-` (unary) | Boolean Negation, Unary Minus | `unary` |
| 8 | `( )`, `[ ]`, `.` | Calls, Indexing, Field Access | `call` |
| 9 | Literals, Grouping | Numbers, Strings, Lambdas, Lists, Maps | `primary` |

---

## 3. Semantics

### Scoping Rules
- **Lexical Block Scope**: Scoping is block-level. Declaring a variable via `let <name> be <value>` binds the variable to the current lexical environment (`Environment` instance).
- **Environment Chain**: If a variable is read or assigned via `set ... to`, the interpreter traverses the chain of enclosing environments from the innermost scope outwards.
- **Shadowing**: Variables declared in inner scopes shadow variables with the same identifier in outer scopes. Redefining a variable in the *same* scope overwrites its value.

### Truthiness and Type Checking
- **Strict Conditional Types**: Conditional statements (`if`, `while`, loop guards) require expressions that evaluate strictly to a `boolean` type (`true` or `false`).
- **No Implicit Coercion**: Passing a non-boolean (such as an integer, string, list, or map) to a conditional guard throws a `TypeError`.
- **Truthiness Evaluation**: An expression is truthy if it evaluates to `true` and falsy if it evaluates to `false`.

### Operator Semantics
- **Arithmetic**: Arithmetic operators (`+`, `-`, `*`, `/`, `%`) and comparison operators (`<`, `<=`, `>`, `>=`) require numeric integer operands. Division (`/`) or Modulo (`%`) by zero throws a `RuntimeError`.
- **String Concatenation**: The `+` operator is overloaded. If either the left or right operand evaluates to a `string`, the other operand is coerced to its string representation (e.g. lists format as `[...]`, maps as `{...}`, null as `nil`) and concatenated.

### Object Representation
- **No Formal Class Syntax**: TLang has no classes. Objects are modeled dynamically using Map literals (`{}`).
- **Field Access**: Accessing a property using dot notation (`obj.prop`) is semantically equivalent to a map key lookup (`obj["prop"]`). Attempting to access a non-existent key throws an `IndexError`.
- **Field Assignment**: Setting a property (`set obj.prop to value`) updates or inserts the key `"prop"` in the underlying map.

### Imports and Module Loading
- **Import Statement**: `import <name>` resolves external dependencies in two sequential steps:
  1. **Native Modules**: Check `ModuleRegistry` first. Native modules include: `math`, `filesystem`, `time`, `random`, `strings`, `json`, `http`, and `db`.
  2. **User Modules**: If not in the registry, look for a `<name>.tiny` file relative to the importing script's directory.
- **Isolation**: Each module is executed once inside its own clean global environment. The top-level bindings are captured and returned as a map.
- **Concurrent loading**: Module initialization and cache publication are atomic per loader. Concurrent first imports observe the same completed export map. A load failure raises a diagnostic without terminating the process.
- **Circular Imports**: A loader-local stack tracks currently loading module files. Importing a module that is currently in the loading chain throws an `ImportError`.
- **Module diagnostics**: User-module tokens retain the imported file's source identity. Runtime failures keep their original module location while callers in other files add their own frames. Lexer, parser, and semantic failures inside imports report the failing module rather than the main script.

---

## 4. Named Functions vs. Lambdas

TLang supports two forms of function definitions, which serve distinct purposes:

### Named Functions (Statement)
Defined using the `define` statement. Named functions are bound at the start of
their current lexical scope, before ordinary statements in that scope execute.
This permits forward calls and mutual recursion without making block-local
functions visible outside their block.
```tiny
show greet("TLang")

define greet taking name
    return "Hello, " + name
```

### Anonymous Lambdas (Expression)
Defined using the `function` keyword as a primary expression. This returns an anonymous function value that can be stored, passed, or returned.
```tiny
let multiply be function taking a and b
    return a * b
```

### Shared Features
- **Parameters**: Both forms support parameters separated by `and`.
- **Default Values**: Both support optional parameters with default values defined via the `be` keyword (e.g. `param be <default>`). Required parameters must always precede default parameters.
- **Bound Methods**: If a function stored in a map is invoked using dot-style field access (e.g., `myMap.myFunc(...)`), and the function has a parameter signature that can accommodate an extra argument, the map itself is automatically prepended as the first parameter (representing the `self` receiver).

---

## 5. Runtime Diagnostic Semantics

- Runtime failures carry a structured category, message, primary source
  location, and an immutable list of TLang stack frames.
- Defined categories are `RuntimeError`, `TypeError`, `NameError`,
  `ImportError`, `DatabaseError`, `HttpError`, `ValidationError`, `IndexError`,
  and `ArityError`.
- The primary location identifies the expression that failed. Tokens retain
  their immutable source-unit identity, including across user modules,
  closures, and string interpolation.
- Function frames use the call site that invoked the function. Frames are
  ordered innermost-first and repeated recursive frames are retained.
- Named functions use their declared name; anonymous functions use
  `<anonymous>`. Module initialization, native calls, and HTTP handlers may add
  explicit boundary frames.
- Stack traces describe TLang execution only. Normal diagnostics never include
  interpreter Java methods, Java class names, Java stack traces, or retained
  native causes.
- Runtime failures preserve exit code `70`. Lexer, parser, resolver, and
  imported compile-time diagnostics preserve exit code `65`.
- TLang does not define language-level exception handling; runtime diagnostics
  propagate until the CLI or an embedding boundary such as the HTTP server
  handles them.

---

## 6. Concurrent HTTP Execution Semantics

- Each HTTP exchange executes with its own interpreter current-environment
  cursor and recursion counter. Function-call environments, parameters, locals,
  request values, and response state are request-local.
- Parsed statements, function declarations, native functions, global lexical
  environments, and module exports are shared. Closures retain normal lexical
  lookup and therefore continue to see program globals.
- Reads and writes of a global binding are synchronized. Each primitive mutable
  list/map operation is synchronized. A multi-step expression such as
  `set counter to counter + 1` is not an atomic transaction and may interleave
  with another handler.
- Route and middleware registration closes when the server starts. A server
  cannot be restarted after it stops.
- Calls on one SQLite connection are serialized; different connections follow
  SQLite's normal concurrency and locking rules.
- A handler runtime failure aborts only that request and contributes an HTTP
  method/path frame to the server-side diagnostic. The server remains alive.
- Remote error responses are always generic `500 Internal Server Error`
  responses. Detailed TLang frames, source paths, request data, native details,
  and Java causes are not exposed to the client.

The HTTP host runtime provides concurrency; the language itself does not add
async syntax or a general thread-spawning primitive.

---

## 7. Explicitly Out of Scope for v1

The following features are **explicitly out of scope** for TLang v1.0. Future tooling should be designed under the assumption that these are not supported, and any additions will require a revised specification:
- **No try/catch exception handling**: Errors propagate to the containing host boundary. A CLI run terminates; an HTTP runtime error terminates only the affected request.
- **No floating-point numbers**: All numeric operations are integer-only.
- **No formal class syntax**: Objects are dynamic maps; there is no inheritance or prototype chain.
- **No language-level async syntax**: TLang has no futures, promises, or async/await syntax. The HTTP host runtime may execute independent handlers concurrently using isolated interpreter execution cursors.
- **No bytecode VM**: The interpreter is a tree-walking interpreter running directly on the Java AST, and the host JVM handles memory management.

---

## 8. Conformance Baseline

- **Executable Suite**: The `.tiny` files located under `src/test/resources` (across the `lexer`, `parser`, `semantic`, `runtime`, and `integration` directories) define the formal conformance suite for TLang.
- **Validation**: All changes to the language runtime must verify against this baseline using the `scripts/run_all_tests.sh` runner, which must remain 100% green.
- **Spec Updates**: Any intentional change to the grammar or runtime semantics must be accompanied by an update to `SPEC.md` within the same revision.

---

## 9. See Also

For practical guides and reference material, see:
- **[Getting Started Guide](docs/getting-started.md)**: A step-by-step introduction to installing and running TLang.
- **[Language Reference Guide](docs/language-reference.md)**: A developer-friendly walkthrough of the language constructs.
- **[Standard Library Reference](docs/stdlib/index.md)**: Comprehensive documentation on all built-in native modules.
- **[Runtime Diagnostics](docs/errors.md)**: Runtime categories, source identity, TLang stack frames, and HTTP error security.
- **[Language Philosophy (LANGUAGE_PHILOSOPHY.md)](LANGUAGE_PHILOSOPHY.md)**: The developer-experience-first principles guiding TLang's design.
