#!/usr/bin/env bash
# Build and optionally run the TinyLang interpreter.
#
# Usage:
#   ./scripts/build.sh              # compile only
#   ./scripts/build.sh run FILE     # compile and run a .tiny script

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$PROJECT_DIR/src/main/java"
OUT_DIR="$PROJECT_DIR/out"

echo "── Compiling TinyLang ──"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

find "$SRC_DIR" -name "*.java" | xargs javac -cp "$PROJECT_DIR/lib/javax.mail-1.6.2.jar:$PROJECT_DIR/lib/activation-1.1.1.jar" -d "$OUT_DIR"

echo "── Build successful ──"

if [[ "${1:-}" == "run" && -n "${2:-}" ]]; then
    echo "── Running: $2 ──"
    java -cp "$OUT_DIR:$PROJECT_DIR/lib/sqlite-jdbc-3.34.0.jar:$PROJECT_DIR/lib/javax.mail-1.6.2.jar:$PROJECT_DIR/lib/activation-1.1.1.jar" dev.tlang.Main "$2"
fi
