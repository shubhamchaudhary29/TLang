#!/usr/bin/env bash
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "── Building TLang ──"
"$PROJECT_DIR/scripts/build.sh" 2>&1

echo "── Starting HTTP server with middleware ──"
rm -f server.log
java -cp "$PROJECT_DIR/out:$PROJECT_DIR/lib/sqlite-jdbc-3.34.0.jar" dev.tlang.Main "$PROJECT_DIR/src/test/resources/runtime/test_middleware.tiny" > server.log 2>&1 &
SERVER_PID=$!

cleanup() {
    echo "── Stopping HTTP server ──"
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    rm -f server.log
}
trap cleanup EXIT

# Wait for server to bind
sleep 1.5

errors=0

# Helper function to check output
check_equals() {
    local label="$1"
    local actual="$2"
    local expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $label"
    else
        echo "FAIL: $label"
        echo "  Expected: $expected"
        echo "  Got:      $actual"
        errors=$((errors+1))
    fi
}

# Helper function to check substring
check_contains() {
    local label="$1"
    local actual="$2"
    local substring="$3"
    if [[ "$actual" == *"$substring"* ]]; then
        echo "PASS: $label"
    else
        echo "FAIL: $label"
        echo "  Expected to contain: $substring"
        echo "  Got:                 $actual"
        errors=$((errors+1))
    fi
}

echo "── Running middleware checks ──"

# 1. GET / -> Hello World & CORS header
res=$(curl -s http://localhost:8086/)
check_equals "GET / body" "$res" "Hello World"

headers=$(curl -s -i http://localhost:8086/ | tr 'A-Z' 'a-z')
check_contains "GET / CORS header" "$headers" "access-control-allow-origin: *"

# 2. GET /authed without bearer token -> 401 Unauthorized
status_401=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8086/authed)
check_equals "GET /authed unauthorized status" "$status_401" "401"

body_401=$(curl -s http://localhost:8086/authed)
check_equals "GET /authed unauthorized body" "$body_401" '{"error":"Unauthorized"}'

headers_41=$(curl -s -i http://localhost:8086/authed | tr 'A-Z' 'a-z')
check_contains "GET /authed unauthorized CORS header" "$headers_41" "access-control-allow-origin: *"

# 3. Verify counter is still 0 (short-circuit worked, handler did not run)
counter_0=$(curl -s http://localhost:8086/counter)
check_equals "Counter before authorized request" "$counter_0" "0"

# 4. GET /authed with token -> 200 OK and counter updates (only one request to avoid double-increment)
body_200=$(curl -s -H "Authorization: Bearer secret-token" http://localhost:8086/authed)
check_equals "GET /authed authorized body" "$body_200" "Authorized: count is 1"

# 5. Verify counter is 1
counter_1=$(curl -s http://localhost:8086/counter)
check_equals "Counter after authorized request" "$counter_1" "1"

# 6. GET /no-response -> 500 fallback fires
status_500=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8086/no-response)
check_equals "GET /no-response status" "$status_500" "500"

body_500=$(curl -s http://localhost:8086/no-response)
check_contains "GET /no-response body fallback message" "$body_500" "No response was sent by the handler or middleware"

# 7. Check Logger output on stdout
sleep 0.5
logs=$(cat server.log)
check_contains "Logger output GET /" "$logs" "GET /"
check_contains "Logger output GET /authed" "$logs" "GET /authed"
check_contains "Logger output GET /counter" "$logs" "GET /counter"
check_contains "Logger output GET /no-response" "$logs" "GET /no-response"

if [ $errors -eq 0 ]; then
    echo "── All Middleware integration tests PASSED ──"
    exit 0
else
    echo "── Middleware integration tests FAILED with $errors error(s) ──"
    exit 1
fi
