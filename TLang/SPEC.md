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

### Block Structure
TLang is an indentation-based language (Python-style) and does not use curly braces or semicolons for block boundaries.
- **Indentation Tracking**: The Lexer keeps track of indentation levels using a stack (with an initial level of `0`).
  - An increase in leading spaces/tabs on a logical line emits an `INDENT` token.
  - A decrease in leading spaces/tabs pops the indentation stack and emits `DEDENT` tokens until the indentation matches an enclosing level.
  - Tabs are treated as equivalent to `4` spaces.
- **Line Handling**: Single-line comments starting with `//` and blank lines are ignored for indentation tracking.

### Position and Column Tracking
- **Convention**: Line and column tracking are **1-indexed**.
- **Synthetic Lexer Tokens**: Synthetic structure tokens (`NEWLINE`, `INDENT`, `DEDENT`, `EOF`) generated during lexing receive a column calculated as `current - lineStart + 1` which points to their location at the time of emission.
- **Synthetic AST Tokens**: Tokens generated dynamically by the parser during desugaring (such as the `<` and `+` tokens for `repeat` loops) receive a default column value of `1` on the line of the currently parsed token.
- **Known Column Bug (Multi-line Strings)**: 
  > [!WARNING]
  > For multi-line string literals, the start of the token (`start`) is recorded on the string's starting line, but the line-tracking variable `lineStart` is updated to the start of the string's final line as newlines are scanned. Consequently, the column computation `start - lineStart + 1` produces a negative column value (e.g. `-7` for a two-line string starting at column 10). This behavior is frozen for this version and must be expected by tooling such as LSPs.

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

returnStmt     ::= "return" expression NEWLINE ;

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
- **No Implicit Coercion**: Passing a non-boolean (such as an integer, string, list, or map) to a conditional guard throws a `RuntimeError`.
- **Truthiness Evaluation**: An expression is truthy if it evaluates to `true` and falsy if it evaluates to `false`.

### Operator Semantics
- **Arithmetic**: Arithmetic operators (`+`, `-`, `*`, `/`, `%`) and comparison operators (`<`, `<=`, `>`, `>=`) require numeric integer operands. Division (`/`) or Modulo (`%`) by zero throws a `RuntimeError`.
- **String Concatenation**: The `+` operator is overloaded. If either the left or right operand evaluates to a `string`, the other operand is coerced to its string representation (e.g. lists format as `[...]`, maps as `{...}`, null as `nil`) and concatenated.

### Object Representation
- **No Formal Class Syntax**: TLang has no classes. Objects are modeled dynamically using Map literals (`{}`).
- **Field Access**: Accessing a property using dot notation (`obj.prop`) is semantically equivalent to a map key lookup (`obj["prop"]`). Attempting to access a non-existent key throws a `RuntimeError`.
- **Field Assignment**: Setting a property (`set obj.prop to value`) updates or inserts the key `"prop"` in the underlying map.

### Imports and Module Loading
- **Import Statement**: `import <name>` resolves external dependencies in two sequential steps:
  1. **Native Modules**: Check `ModuleRegistry` first. Native modules include: `math`, `filesystem`, `time`, `random`, `strings`, `json`, `http`, and `db`.
  2. **User Modules**: If not in the registry, look for a `<name>.tiny` file relative to the importing script's directory.
- **Isolation**: Each module is executed once inside its own clean global environment. The top-level bindings are captured and returned as a map.
- **Circular Imports**: A runtime stack tracks currently loading module files. Importing a module that is currently in the loading chain throws a `RuntimeError` due to a circular import.

---

## 4. Named Functions vs. Lambdas

TLang supports two forms of function definitions, which serve distinct purposes:

### Named Functions (Statement)
Defined using the `define` statement. It binds a callable function object to a name in the current environment scope.
```tiny
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

## 5. Explicitly Out of Scope for v1

The following features are **explicitly out of scope** for TLang v1.0. Future tooling should be designed under the assumption that these are not supported, and any additions will require a revised specification:
- **No try/catch exception handling**: Errors propagate up and terminate execution.
- **No floating-point numbers**: All numeric operations are integer-only.
- **No formal class syntax**: Objects are dynamic maps; there is no inheritance or prototype chain.
- **No concurrent/async request handling**: Execution is single-threaded and synchronous.
- **No bytecode VM**: The interpreter is a tree-walking interpreter running directly on the Java AST, and the host JVM handles memory management.

---

## 6. Conformance Baseline

- **Executable Suite**: The `.tiny` files located under `src/test/resources` (across the `lexer`, `parser`, `semantic`, `runtime`, and `integration` directories) define the formal conformance suite for TLang.
- **Validation**: All changes to the language runtime must verify against this baseline using the `scripts/run_all_tests.sh` runner, which must remain 100% green.
- **Spec Updates**: Any intentional change to the grammar or runtime semantics must be accompanied by an update to `SPEC.md` within the same revision.
