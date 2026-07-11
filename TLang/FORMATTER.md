# TLang Code Formatter (`tlang fmt`) Style Guide

The TLang formatter implements a single, strict, canonical coding style with no user-configurable options.

## Canonical Style Rules

### 1. Indentation
- Every indentation level consists of exactly **4 spaces**.
- Tabs are not used for indentation.

### 2. Operator Spacing
- Exactly one space on both sides of binary operators (`+`, `-`, `*`, `/`, `%`, `<`, `<=`, `>`, `>=`, `==`, `!=`) and logical operators (`and`, `or`).
  - Example: `let x be a + b * c`
- No space between the unary operator `-` and its operand.
  - Example: `-x`
- Exactly one space between the unary operator `not` and its operand.
  - Example: `not x`
- No space around parentheses `()` for groupings or argument lists, brackets `[]` for indexing, or dot `.` for field accesses.
  - Examples: `foo(a, b)`, `list[i]`, `obj.field`

### 3. String Literals
- All string literals are double-quoted.
- Special characters are canonically escaped (`\n`, `\t`, `\\`, `\"`).

### 4. List and Map Literals
- Empty list and map literals are formatted as `[]` and `{}`, respectively.
- Non-empty list and map literals are formatted on a single line if their total formatted length is **80 characters or fewer** and they contain no internal newlines (e.g., nested multi-line lambdas/lists/maps).
  - List example: `[1, 2, 3]`
  - Map example: `{a: 1, b: 2}` (keys that are valid identifiers are unquoted; otherwise, they are double-quoted).
- Otherwise, they are formatted as multi-line expressions:
  - One element or key-value pair per line.
  - Indented by an additional level.
  - No trailing commas are added (to align with parser expectations).

### 5. Blank Lines
- Exactly one blank line before every function declaration (`define ...`), unless it is the first statement in the file or in a block.
- Exactly one blank line after the last `import` statement at the top level (if any).
- Exactly one newline (no blank lines) between all other statements.
- Exactly one trailing newline at the end of the file.

---

## Known Limitations

### Comment Preservation
Single-line comments (`//`) are discarded entirely by the lexer during tokenization and are not present in the AST. Consequently, **the formatter cannot preserve comments**. Any comments in the source code will be stripped during formatting.
