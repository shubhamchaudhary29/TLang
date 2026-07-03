#!/usr/bin/env bash
# Build and optionally run the TinyLang interpreter.
#
# Usage:
#   ./build.sh              # compile only
#   ./build.sh run FILE     # compile and run a .tiny script

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PROJECT_DIR/src"
OUT_DIR="$PROJECT_DIR/out"

echo "── Compiling TinyLang ──"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

find "$SRC_DIR" -name "*.java" | xargs javac -d "$OUT_DIR"

echo "── Build successful ──"

if [[ "${1:-}" == "run" && -n "${2:-}" ]]; then
    echo "── Running: $2 ──"
    java -cp "$OUT_DIR" TLang.Main "$2"
fi
