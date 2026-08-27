# Local package example

From this directory, use a built TLang executable:

```bash
tlang install
tlang run main.tiny
```

The output is `Hello, packages!`. The checked-in lockfile records the local
dependency graph; installation is network-free. `.tlang/` is generated and is
ignored by Git.
