# Verification Documentation - LSP Rename Capability

This document details how to verify the TLang Language Server (LSP) Rename Capability, which supports single-file renames with strict identifier syntax, reserved keyword, and same-scope collision validations.

---

## 1. How to Run the Verification Suite

### A. Run JUnit Unit Tests
The unit tests cover:
- Variable renaming and programmatic edit applications.
- Nested scope shadowing protection.
- Rejection of built-in/library functions.
- Rejection of invalid identifier names.
- Rejection of reserved keywords.
- Rejection of scope collisions.
- End-to-end integration and run validation.

Run the unit tests with:
```bash
./gradlew test
```

### B. Run Node.js Integration Script
The Node.js script launches the language server in `lsp` mode and performs a sequence of JSON-RPC requests (`initialize`, `textDocument/didOpen`, `textDocument/didChange`, `textDocument/hover`, `textDocument/definition`, `textDocument/references`, `textDocument/prepareRename`, and `textDocument/rename`).

First, build and package the server distribution:
```bash
./gradlew installDist
```

Then, run the integration script:
```bash
node scripts/verify_lsp.js
```

---

## 2. Integration Script Verification Output Transcript

Running `node scripts/verify_lsp.js` produces the following output:

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

Step 7: Prepare Rename of inner shadowed "foo" at Line 3, Char 7

--- Received Prepare Rename Response ---
{
  "start": {
    "line": 3,
    "character": 7
  },
  "end": {
    "line": 3,
    "character": 10
  }
}
----------------------------------------

Step 8: Rename inner shadowed "foo" at Line 3, Char 7 to "innerFoo"

--- Received Rename Response (inner foo -> innerFoo) ---
{
  "changes": {
    "file:///home/gigachad/Trash/TLang/scripts/test.tiny": [
      {
        "range": {
          "start": {
            "line": 2,
            "character": 6
          },
          "end": {
            "line": 2,
            "character": 9
          }
        },
        "newText": "innerFoo"
      },
      {
        "range": {
          "start": {
            "line": 3,
            "character": 7
          },
          "end": {
            "line": 3,
            "character": 10
          }
        },
        "newText": "innerFoo"
      }
    ]
  }
}
-------------------------------------------------------

Step 9: Change the document to set up validation checks (version 3)

--- Received Diagnostics ---
URI: file:///home/gigachad/Trash/TLang/scripts/test.tiny
Diagnostics count: 0
----------------------------

Step 10: Prepare Rename of built-in "now" at Line 2, Char 5

--- Received Prepare Rename Error (Built-in) ---
{
  "code": -32803,
  "message": "Cannot rename built-in symbol: 'now'"
}
-------------------------------------------------

Step 11: Rename "foo" at Line 0, Char 4 to reserved keyword "let" (Reserved Keyword Error)

--- Received Rename Error (Reserved Keyword) ---
{
  "code": -32602,
  "message": "Cannot rename to reserved keyword 'let'"
}
-------------------------------------------------

Step 12: Rename "foo" at Line 0, Char 4 to invalid identifier "1foo" (Invalid Identifier Error)

--- Received Rename Error (Invalid Identifier) ---
{
  "code": -32602,
  "message": "Invalid identifier name: '1foo'"
}
---------------------------------------------------

Step 13: Rename "foo" at Line 0, Char 4 to existing variable "bar" (Scope Collision Error)

--- Received Rename Error (Scope Collision) ---
{
  "code": -32803,
  "message": "Name collision: 'bar' is already declared in the same scope"
}
------------------------------------------------

Step 14: Creating two simultaneous undefined variable errors ("foo" and "bar")

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
