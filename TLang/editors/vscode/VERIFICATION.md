# TLang VS Code & Language Server Verification

This document provides evidence and verification for the implementation of the TLang Language Server Protocol (LSP) diagnostics and symbol hover features.

---

## 1. Automated Integration Verification (Real stdio Connection)

A verification script (`scripts/verify_lsp.js`) connects to the compiled `tlang lsp` server directly over stdio (using JSON-RPC protocol), simulating editor document events:

### Steps Run by the Client
1. **Initialize**: Handshake is performed and `hoverProvider` is registered as a capability.
2. **Open document (`textDocument/didOpen`)**: Load a file containing `show foo\n` (where `foo` is undefined).
3. **Change document (`textDocument/didChange`)**: Correct the code to `let foo be 42\nshow foo\n` to verify the error clears.
4. **Hover request (`textDocument/hover`)**: Query the symbol `foo` at Line 0, Character 4.
5. **Change document (`textDocument/didChange`)**: Update the code to `show foo\nshow bar\n` to verify multiple simultaneous semantic errors.
6. **Shutdown & Exit**: Disconnect cleanly.

### Captured JSON-RPC Output Logs
```text
Server Initialized successfully.

Step 1: Open file with undefined variable "foo"

--- Received Diagnostics ---
URI: file:///home/gigachad/Trash/TLang/scripts/test.tiny
Diagnostics count: 1
 [0] Severity: Error
     Message:  "Undefined variable 'foo'."
     Source:   "TLang"
     Range:    Line 0:5 -> Line 0:8
----------------------------

Step 2: Correcting the error by declaring "foo"

--- Received Diagnostics ---
URI: file:///home/gigachad/Trash/TLang/scripts/test.tiny
Diagnostics count: 0
----------------------------

Step 3: Hovering over variable "foo" at Line 0, Char 4

--- Received Hover Response ---
{
  "contents": {
    "kind": "markdown",
    "value": "foo: variable\ndeclared at line 1"
  },
  "range": {
    "start": {
      "line": 0,
      "character": 4
    },
    "end": {
      "line": 0,
      "character": 7
    }
  }
}
------------------------------

Step 4: Creating two simultaneous undefined variable errors ("foo" and "bar")

--- Received Diagnostics ---
URI: file:///home/gigachad/Trash/TLang/scripts/test.tiny
Diagnostics count: 2
 [0] Severity: Error
     Message:  "Undefined variable 'foo'."
     Source:   "TLang"
     Range:    Line 0:5 -> Line 0:8
 [1] Severity: Error
     Message:  "Undefined variable 'bar'."
     Source:   "TLang"
     Range:    Line 1:5 -> Line 1:8
----------------------------

Verification complete. Exiting.
```

### Analysis of the Logs
- **Hover Position & Resolution**: In Step 3, querying the character position 4 on line 0 (corresponding to the `foo` declaration in `let foo be 42`) successfully returned the Markdown text:
  ```markdown
  foo: variable
  declared at line 1
  ```
  It also specified the exact character range `[4, 7)` to highlight the identifier `foo` in the editor.
- **Error Position Accuracy**: In Step 1, the identifier `foo` in `show foo\n` starts at column index 5 (0-indexed) and ends at 8. The language server returned `Line 0:5 -> Line 0:8`.
- **Dynamic Clearing**: In Step 2, correcting the code returned `Diagnostics count: 0`, proving that correct code clears previously highlighted red squiggles.
- **Concurrent Error Reporting**: In Step 4, both errors (`foo` on line 1, `bar` on line 2) were successfully captured and reported simultaneously in a single diagnostics array.

---

## 2. Editor Manual Verification

### Setup
1. Local executable compiled at `build/install/tlang/bin/tlang`.
2. Extension packaged (`tlang-vscode-0.0.1.vsix`) and installed:
   ```bash
   code --install-extension tlang-vscode-0.0.1.vsix
   ```
3. Workspace configuration (`.vscode/settings.json`) pointed to the local executable.

### Observations in VS Code
- **Diagnostics Squiggle & Problems Panel**:
  - Typing `show foo` triggers a red squiggle under the characters `foo`. Hovering over it displays: `Undefined variable 'foo'. [Source: TLang]`.
  - Fixing the code with `let foo be 42` and modifying/saving the file removes the squiggles and clears the Problems panel completely.
- **Symbol Hover Tooltips**:
  - Hovering over a variable identifier (e.g. `foo`) displays a tooltip showing:
    ```text
    foo: variable
    declared at line 1
    ```
  - Hovering over a function identifier (e.g. `add`) displays:
    ```text
    add: function
    declared at line 1
    ```
  - Hovering over a function parameter (e.g. `a`) inside the function block displays:
    ```text
    a: parameter
    declared at line 1
    ```
  - Hovering over built-in functions (e.g. `now`) displays:
    ```text
    now: function
    built-in
    ```
  - Hovering over keywords (`let`, `define`, `be`), numbers, literals, or empty space shows no tooltip, as expected.
