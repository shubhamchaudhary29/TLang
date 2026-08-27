# Projects and package management

TLang projects add reproducible multi-file dependency graphs without changing
the script-first workflow. A standalone `.tiny` file still runs without any
project files. A project is a directory containing `tlang.toml`; commands and
scripts may be launched from that directory or any child directory.

## Create a project

```bash
mkdir my-api
cd my-api
tlang init
```

`init` creates (and never overwrites) this deterministic manifest:

```toml
[package]
name = "my-api"
version = "0.1.0"

[dependencies]
```

Use `tlang init --name my_api` when the directory name is not the desired
package name. Package names are lowercase ASCII names; dependency names are
also valid TLang identifiers, because `import <name>` binds that identifier.

Every dependency package has its own `tlang.toml` and an entry module named
`<package-name>.tiny`. Its top-level bindings are the package exports.

## Manifest dependencies

Local paths are resolved relative to the manifest that declares them:

```toml
[dependencies]
utils = { path = "../utils" }
```

Paths must be relative. `..` is intentionally supported for sibling projects,
but the canonical source may not be inside the consuming project's `.tlang`
directory. Package trees containing symbolic links are rejected.

Git dependencies name both a repository and a required revision:

```toml
[dependencies]
http_helpers = {
    git = "https://github.com/example/http_helpers.git",
    rev = "main"
}
```

Branches, tags, and full commits are accepted. HTTPS, SSH, and `file:` Git URIs
are supported. TLang clones using an argument-safe process invocation and never
runs package scripts or hooks from the package manifest. Git hooks are disabled;
submodules and dependency-selected external checkout filters are rejected to
prevent checkout from becoming an install-time code-execution mechanism. Git
checkout also disables automatic CRLF conversion, keeping locked content
digests identical across operating systems.

The manifest parser is deliberately strict. Unknown tables or fields,
duplicates, source conflicts, absolute paths, malformed URLs, missing
revisions, invalid names/versions, and ambiguous dependency tables fail rather
than being ignored.

## Commands

Add, remove, install, and inspect dependencies with:

```bash
tlang add utils --path ../utils
tlang add helpers --git https://example.test/helpers.git --rev v1.2.0
tlang remove helpers
tlang install
tlang list
```

`add` and `remove` resolve and install the proposed graph before committing the
new manifest and lockfile. A resolution failure leaves both files unchanged.
Removing a direct dependency removes packages no longer reachable but retains
shared transitive packages.

`tlang install` creates `tlang.lock` when it is missing. With an existing valid
lockfile it installs exactly that graph and never advances a branch or tag.
Use `tlang install --update` to intentionally resolve moving Git revisions and
changed local package contents again.

```bash
tlang install --offline
```

Offline mode never starts a network Git operation. It succeeds with local path
sources and exact Git commits already present in the project cache, and fails
with the missing package and commit when the cache is incomplete. It will not
silently retry online.

## Lockfile and installed state

Commit `tlang.toml` and `tlang.lock`. Do not commit `.tlang/`.

`tlang.lock` format version 1 contains the canonical manifest digest, a graph
integrity digest, stable name-sorted records and edges, each source, and a
content SHA-256. Git records contain the requested revision and immutable
40-character commit. Local records contain a normalized project-relative path
and content digest; they remain explicitly mutable, so changing one requires
`install --update`. The same graph produces the same lockfile bytes on every
run.

Project-local state has this layout:

```text
.tlang/
  install.lock
  cache/git/<repository-hash>/<commit>/
  packages/<package-name>/
```

The exact-commit cache supports repair and offline installation. Installed
trees carry an integrity marker. Missing, corrupt, partial, and stale temporary
trees are rebuilt through a temporary directory and an atomic rename. An OS
file lock plus an in-process fair lock serializes concurrent installers.

## Imports

After installation, a direct dependency is imported with the existing syntax:

```tiny
import utils
show utils.format_name("TLang")
```

Resolution is deterministic:

1. built-in/native modules (reserved for backward compatibility),
2. a module beside the importing file,
3. a module at the project root for project source,
4. an allowed installed dependency entry module.

Project source can import direct dependencies. A dependency can import only its
own declared dependency edges, though it may import sibling `.tiny` modules in
its package. This prevents undeclared access to the application's files or
unrelated transitive packages. Each source file keeps its canonical installed
path, so structured errors and stack traces identify dependency code correctly
inside ordinary calls, closures, HTTP handlers, and spawned tasks.

## Troubleshooting and limitations

- **Manifest and lockfile do not match:** restore the checked-in pair or run
  `tlang install --update` after intentionally editing the manifest.
- **Local source changed:** review the local changes, then update the lock.
- **Offline cache miss:** run `tlang install` online once to acquire the exact
  locked commit.
- **Git revision not found:** check the repository URI, credentials, and branch,
  tag, or commit. Git's first safe diagnostic line is included without a Java
  stack trace.
- **Cycle:** the error prints the complete cycle, such as
  `a -> auth -> common -> a`.
- **Corrupt install/cache:** online install repairs it from the locked source;
  an offline Git repair requires an intact cache.

M4 has no hosted registry, publishing, login, package scripts, version-range
solver, submodule support, or global installation. Git authentication is
delegated to the user's normal Git configuration without storing embedded
credentials in project files.

See the runnable, network-free [local package example](../examples/packages/app/README.md).
