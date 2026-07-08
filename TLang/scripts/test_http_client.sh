#!/usr/bin/env bash
# Run the HTTP client tests against a local test server.
#
# Usage: ./scripts/test_http_client.sh
#
# Starts LocalHttpTestServer in the background, runs the TLang test,
# captures the exit code, kills the server, and exits with that code.

set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SUPPORT_DIR="$PROJECT_DIR/src/test/java"
OUT_DIR="$PROJECT_DIR/out"
SERVER_PID=""

cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
}
trap cleanup EXIT

# 1. Build the project
echo "── Building TLang ──"
"$PROJECT_DIR/scripts/build.sh" 2>&1
if [ $? -ne 0 ]; then
    echo "BUILD FAILED"
    exit 1
fi

# 2. Compile the test server
echo "── Compiling test server ──"
javac "$SUPPORT_DIR/LocalHttpTestServer.java" -d "$SUPPORT_DIR" 2>&1
if [ $? -ne 0 ]; then
    echo "TEST SERVER COMPILATION FAILED"
    exit 1
fi

# 3. Start the test server in the background
echo "── Starting local test server ──"
java -cp "$SUPPORT_DIR" LocalHttpTestServer &
SERVER_PID=$!

# 4. Wait for the server to be ready (look for READY message, with timeout)
READY=false
for i in $(seq 1 30); do
    if curl -s "http://localhost:8973/echo" >/dev/null 2>&1; then
        READY=true
        break
    fi
    sleep 0.2
done

if [ "$READY" != "true" ]; then
    echo "TEST SERVER FAILED TO START"
    exit 1
fi
echo "── Test server ready ──"

# 5. Run the TLang HTTP client test
echo "── Running test_http_client.tiny ──"
java -cp "$OUT_DIR:$PROJECT_DIR/lib/sqlite-jdbc-3.34.0.jar" dev.tlang.Main "$PROJECT_DIR/src/test/resources/runtime/test_http_client.tiny"
TEST_EXIT=$?

# 6. Cleanup happens via trap
echo "── Test exit code: $TEST_EXIT ──"
exit $TEST_EXIT
