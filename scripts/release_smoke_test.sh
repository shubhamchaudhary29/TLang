#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cli="$root/build/install/tlang/bin/tlang"
expected_version="$(sed -n 's/^version=//p' "$root/gradle.properties")"
work="$(mktemp -d "${TMPDIR:-/tmp}/tlang smoke.XXXXXX")"
trap 'rm -rf "$work"' EXIT

test -x "$cli"
test "$("$cli" version)" = "TLang version $expected_version"
"$cli" help | grep -Fqx '  tlang run <file>'
printf 'show "Hello, World!"\n' > "$work/hello world.tiny"
test "$("$cli" run "$work/hello world.tiny")" = 'Hello, World!'
test "$("$cli" run "$root/examples/hello.tiny")" = 'Hello from TLang!'
echo 'Distribution smoke test passed.'
