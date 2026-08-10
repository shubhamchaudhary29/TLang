#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d "$root/.doc-validation.XXXXXX")"
trap 'rm -rf "$work"' EXIT
printf '[ok](README.md)\n' > "$work/ok.md"
cp "$root/README.md" "$work/README.md"
python3 "$root/scripts/verify_docs.py" "$work/ok.md"
printf '[bad](file:///home/example/private.md)\n' > "$work/bad.md"
if python3 "$root/scripts/verify_docs.py" "$work/bad.md" 2>"$work/error"; then
    echo "portable-path validator accepted a file URL" >&2
    exit 1
fi
grep -Fq 'machine-specific path or file URL' "$work/error"
