#!/usr/bin/env bash
# Build and optionally run the TinyLang interpreter.
#
# Usage:
#   ./scripts/build.sh              # compile only
#   ./scripts/build.sh run FILE     # compile and run a .tiny script

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Delegate to Gradle compile task
cd "$PROJECT_DIR"
./gradlew compileJava

if [[ "${1:-}" == "run" && -n "${2:-}" ]]; then
    # Delegate to Gradle run task with arguments
    ./gradlew run --args="$2"
fi
