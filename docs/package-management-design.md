# TLang M4 package-management design

This document records the implementation contract for TLang v0.3 M4. The
user-facing guide is `docs/packages.md`; this document focuses on invariants
that must remain true as the implementation evolves.

## Files and identities

A project is the nearest ancestor of the executed script containing
`tlang.toml`. The manifest uses a deliberately small, strict TOML subset:

```toml
[package]
name = "my-api"
version = "0.1.0"

[dependencies]
utils = { path = "../utils" }
helpers = { git = "https://example.test/helpers.git", rev = "main" }
```

Package names are lowercase ASCII names matching
`[a-z][a-z0-9_-]{0,63}`. Dependency names match
`[a-z][a-z0-9_]{0,63}` so they are valid TLang import bindings. Versions are
semantic versions. Dependency aliases must equal the depended-on manifest's
package name. A dependency has exactly one source: `path`, or `git` plus a
non-empty `rev`. Unknown tables, fields, duplicate declarations, control
characters, ambiguous sources, and malformed URLs are errors.

A dependency identity is `(name, source)`. A path source is the canonical real
directory selected by a manifest-relative path. A Git source is the normalized
repository URI plus its immutable 40-character commit SHA. Resolving the same
name to different sources is a conflict. Graph nodes and edges are always
ordered by package name.

## Lockfile and resolution

`tlang.lock` is generated, never hand-maintained. Format version 1 stores a
SHA-256 digest of the canonical manifest model and one sorted package record
per resolved dependency. A path record stores the project-relative path and a
content digest, explicitly identifying it as mutable. A Git record stores the
repository, requested revision, and resolved commit SHA. Every record stores
its sorted direct dependency names.

The resolver performs a deterministic depth-first traversal with explicit
`VISITING`/`VISITED` states. It deduplicates identical identities, reports
source conflicts, and emits the complete cycle path. Package manifests are
validated before their edges are traversed. Existing Git pins are reused when
the manifest source and requested revision still match; `install --update` is
the explicit operation that resolves moving refs again.

A normal install with a lockfile never resolves a moving Git ref. It verifies
the manifest digest and installs the recorded graph. A missing lockfile is
resolved online and written. Offline mode never starts Git with a network
source: it can use installed packages, the exact cached Git commit, and local
path sources, and otherwise fails.

## Storage, transactions, and concurrency

All project state is local:

```text
.tlang/
  install.lock
  cache/git/<repository-hash>/<commit>/
  packages/<package-name>/
```

Cache and installed destinations are derived only from validated names and
hexadecimal hashes. Package trees reject symbolic links and never copy `.git`
or `.tlang`. Each installed tree has an internal metadata marker containing its
identity and content digest; exact-commit cache trees are checked against the
lock's content digest. Missing, partial, or corrupt trees are rebuilt in a
fresh sibling temporary directory and atomically moved into place.

Mutating package operations take an exclusive OS file lock on
`.tlang/install.lock`. Manifest and lockfile text is fully prepared before
either is replaced. Each individual file replacement uses a same-directory
temporary file and atomic move where supported. Failed resolution therefore
leaves the user's manifest and lockfile unchanged; interrupted package copies
cannot masquerade as complete installations. Stale temporary directories are
removed while holding the lock.

`add` and `remove` resolve and install the proposed graph before replacing the
manifest and lockfile. `install` never edits a valid manifest. No package hook,
shell fragment, or repository-provided program is executed.

## Import resolution

Standalone scripts continue to use the existing behavior. In a project the
loader receives an immutable view of the locked graph and package roots. Import
resolution is:

1. built-in/native module (reserved for backward compatibility),
2. `<name>.tiny` beside the importing file,
3. `<name>.tiny` at the project root (root/project code only),
4. the entry module `<package>/<package>.tiny` of an allowed dependency.

Root/project code may import direct manifest dependencies. A dependency module
may import only dependencies listed on its own lockfile edge. This prevents a
transitive dependency from accidentally observing unrelated project files.
Nested sibling modules keep their own directory as the import base. All loaded
files are lexed with their canonical source path, preserving M2 diagnostics in
closures, HTTP handlers, and spawned tasks. Resolver state belongs to the
`ModuleLoader`; no global mutable package context is introduced.

## Errors and security

Package operations raise `PackageException` with a stable category and safe
message. CLI commands print `Package error: ...` and return a non-zero status
without JVM stack traces. Git is invoked as an argument vector with options
terminated before untrusted revisions where applicable; revisions beginning
with `-`, malformed repository URIs, unsafe names, lockfile destination data,
duplicate records, graph references to absent records, source mismatches,
symlinked content, and cache/install escapes are rejected.

Git hooks are disabled for clone and checkout. Repositories using submodules or
`.gitattributes` external checkout filters are rejected, because either could
turn acquisition into dependency-selected code execution. URLs may use ordinary
Git authentication configuration but may not embed passwords. Checkout forces
LF content (`core.autocrlf=false`, `core.eol=lf`) so cache and lock content
digests do not vary with the installer's operating-system Git defaults.

Path dependencies intentionally permit documented `../sibling` paths. They are
canonicalized with `toRealPath`, may not point into the project's `.tlang`
state, must be directories with valid manifests, and are never used to derive
an installation destination. Local sources remain mutable; their lock content
digest detects changes instead of claiming immutability.
