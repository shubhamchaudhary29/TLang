# TLang VS Code & Language Server Verification

This document provides evidence and verification for the implementation of the TLang Language Server Protocol (LSP) diagnostics, symbol hover, go-to-definition, and find-references features.

---

## 1. Automated Integration Verification (Real stdio Connection)

A verification script (`scripts/verify_lsp.js`) connects to the compiled `tlang lsp` server directly over stdio (using JSON-RPC protocol), simulating editor document events:

### Steps Run by the Client
1. **Initialize**: Handshake is performed and `hoverProvider`, `definitionProvider`, and `referencesProvider` are registered as capabilities.
2. **Open document (`textDocument/didOpen`)**: Load a file containing `show foo\n` (where `foo` is undefined).
3. **Change document (`textDocument/didChange`)**: Correct the code to include variable shadowing:
   ```text
   let foo be 42
   if true
     let foo be 100
     show foo
   show foo
   ```
4. **Hover request (`textDocument/hover`)**: Query the symbol `foo` at Line 0, Character 4.
5. **Go-to-Definition request (`textDocument/definition`)**: Query the symbol `foo` at Line 4, Character 5.
6. **Find References (outer `foo`) (`textDocument/references`)**: Query references for outer `foo` at Line 4, Character 5 with `includeDeclaration: true`.
7. **Find References (inner `foo`) (`textDocument/references`)**: Query references for inner shadowed `foo` at Line 3, Character 7 with `includeDeclaration: false`.
8. **Change document (`textDocument/didChange`)**: Update the code to `show foo\nshow bar\n` to verify multiple simultaneous semantic errors.
9. **Shutdown & Exit**: Disconnect cleanly.

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

Step 2: Correcting the error by declaring "foo" with shadowing

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

Step 4: Go-to-Definition of variable "foo" at Line 4, Char 5

--- Received Definition Response ---
[
  {
    "uri": "file:///home/gigachad/Trash/TLang/scripts/test.tiny",
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
]
-----------------------------------

Step 5: Find References of outer "foo" at Line 4, Char 5 (includeDeclaration: true)

--- Received References Response (Outer foo, includeDeclaration: true) ---
[
  {
    "uri": "file:///home/gigachad/Trash/TLang/scripts/test.tiny",
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
  },
  {
    "uri": "file:///home/gigachad/Trash/TLang/scripts/test.tiny",
    "range": {
      "start": {
        "line": 4,
        "character": 5
      },
      "end": {
        "line": 4,
        "character": 8
      }
    }
  }
]
------------------------------------------------------------------------

Step 6: Find References of inner shadowed "foo" at Line 3, Char 7 (includeDeclaration: false)

--- Received References Response (Inner shadowed foo, includeDeclaration: false) ---
[
  {
    "uri": "file:///home/gigachad/Trash/TLang/scripts/test.tiny",
    "range": {
      "start": {
        "line": 3,
        "character": 7
      },
      "end": {
        "line": 3,
        "character": 10
      }
    }
  }
]
----------------------------------------------------------------------------------

Step 7: Creating two simultaneous undefined variable errors ("foo" and "bar")

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
- **Variable Shadowing Isolation**: In Step 5, querying references for the outer `foo` with `includeDeclaration: true` returned exactly 2 references (the declaration at Line 0 and the usage at Line 4). It correctly omitted the inner shadowed `foo` at Line 2 & 3.
- **`includeDeclaration` Respect**: In Step 6, querying references for the inner `foo` with `includeDeclaration: false` returned exactly 1 reference (the usage at Line 3), omitting the declaration at Line 2.
- **Go-to-Definition Resolution**: In Step 4, querying the character position 5 on line 4 (the usage `show foo`) successfully returned a `Location` referencing the declaration site at Line `0` Character `[4, 7)`.
- **Diagnostics and Hover**: Diagnostics correctly transition dynamically and Hover returns formatted markdown definitions.

---

## 2. Editor Manual Verification

### Setup
1. Local executable compiled at `build/install/tlang/bin/tlang`.
2. Extension packaged (`tlang-vscode-0.0.1.vsix`) and installed.
3. Workspace configuration (`.vscode/settings.json`) pointed to the local executable.

### Observations in VS Code
- **Find All References Navigation**:
  - Right-clicking a variable (or pressing `Shift+F12`) opens the VS Code References view.
  - Doing this on an outer variable displays its declaration and usages, and does not show references of same-named variables shadowed in nested blocks.
  - Doing this on a function identifier lists all call sites cleanly in the sidebar.
  - Doing this on whitespace/keywords does not show any references.
- **Go-to-Definition Navigation**:
  - Ctrl+Clicking or pressing `F12` on a variable/parameter/function jumps the cursor to the declaration site.
- **Diagnostics Squiggle & Problems Panel**:
  - Unresolved variables trigger red squiggles, which clear dynamically when declared.
- **Symbol Hover Tooltips**:
  - Hovering over symbols displays tooltips containing symbol kind and declaration details.
