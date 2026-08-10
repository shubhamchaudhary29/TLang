#!/usr/bin/env bash
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "── Building TLang ──"
"$PROJECT_DIR/scripts/build.sh" 2>&1

echo "── Starting HTTP server ──"
java -cp "$PROJECT_DIR/out:$PROJECT_DIR/lib/sqlite-jdbc-3.34.0.jar:$PROJECT_DIR/lib/javax.mail-1.6.2.jar:$PROJECT_DIR/lib/activation-1.1.1.jar" dev.tlang.Main "$PROJECT_DIR/src/test/resources/runtime/test_http_server.tiny" &
SERVER_PID=$!

cleanup() {
    echo "── Stopping HTTP server ──"
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
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

echo "── Running curl checks ──"

# 1. GET /
res=$(curl -s http://localhost:8085/)
check_equals "GET /" "$res" "Hello World"

# 2. POST /echo
res=$(curl -s -X POST -d "hello payload" http://localhost:8085/echo)
check_equals "POST /echo" "$res" '{"method":"POST","body":"hello payload"}'

# 2b. POST /json-body valid JSON
res=$(curl -s -X POST -H "Content-Type: application/json" -d '{"foo": "bar"}' http://localhost:8085/json-body)
check_equals "POST /json-body valid JSON" "$res" '{"json":{"foo":"bar"}}'

# 2c. POST /json-body text/plain
res=$(curl -s -X POST -H "Content-Type: text/plain" -d '{"foo": "bar"}' http://localhost:8085/json-body)
check_equals "POST /json-body text/plain" "$res" '{"json":null}'

# 2d. POST /json-body malformed JSON
res=$(curl -s -X POST -H "Content-Type: application/json" -d '{"foo":' http://localhost:8085/json-body)
check_equals "POST /json-body malformed JSON" "$res" '{"json":null}'

# 2e. POST /json-body empty body
res=$(curl -s -X POST -H "Content-Type: application/json" -d '' http://localhost:8085/json-body)
check_equals "POST /json-body empty body" "$res" '{"json":null}'

# 3. GET /headers
res=$(curl -s -H "X-Custom: header-value" http://localhost:8085/headers)
check_equals "GET /headers" "$res" "header-value"

# 4. GET /query
res=$(curl -s "http://localhost:8085/query?foo=bar&baz=qux")
check_equals "GET /query" "$res" '{"foo":"bar","baz":"qux"}'

# 5. GET /status (Check response code and body)
status_code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/status)
body=$(curl -s http://localhost:8085/status)
check_equals "GET /status code" "$status_code" "201"
check_equals "GET /status body" "$body" "Created"

# 6. GET /json
res=$(curl -s http://localhost:8085/json)
check_equals "GET /json" "$res" '{"message":"success","code":100}'

# 7. GET /double-send (Check that it throws and returns 500)
status_code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/double-send)
body=$(curl -s http://localhost:8085/double-send)
check_equals "GET /double-send code" "$status_code" "500"
check_contains "GET /double-send body" "$body" "Response already sent"

# 8. GET /non-existent (Check 404)
status_code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/non-existent)
body=$(curl -s http://localhost:8085/non-existent)
check_equals "GET /non-existent code" "$status_code" "404"
check_equals "GET /non-existent body" "$body" "Not Found"

# 9. GET /users/:id (Single parameter)
res=$(curl -s http://localhost:8085/users/123)
check_equals "GET /users/:id (123)" "$res" '{"id":"123"}'

# 10. GET /users/:id/posts/:postId (Multi parameter)
res=$(curl -s http://localhost:8085/users/123/posts/456)
check_equals "GET /users/:id/posts/:postId (123/456)" "$res" '{"id":"123","postId":"456"}'

# 11. Overlapping specificity (literal /users/me vs parameter /users/:id)
res_literal=$(curl -s http://localhost:8085/users/me)
check_equals "Overlapping literal /users/me" "$res_literal" "literal_me"
res_param=$(curl -s http://localhost:8085/users/other)
check_equals "Overlapping parameter /users/other" "$res_param" '{"id":"other"}'

# 12. Trailing-slash normalization (register /users/:id, request /users/5/)
res=$(curl -s http://localhost:8085/users/5/)
check_equals "Trailing slash normalization" "$res" '{"id":"5"}'

# 13. URL-decoded path parameters (register /search/:term, request /search/hello%20world)
res=$(curl -s http://localhost:8085/search/hello%20world)
check_equals "URL-decoded path parameter" "$res" "hello world"

# 14. 405 Method Not Allowed check (Allow headers contains GET, POST)
status_405=$(curl -s -o /dev/null -w "%{http_code}" -X PUT http://localhost:8085/search/foo)
check_equals "405 Status Code" "$status_405" "405"
allow_headers=$(curl -s -i -X PUT http://localhost:8085/search/foo | grep -i "^Allow:")
check_contains "405 Allow Header contains GET" "$allow_headers" "GET"
check_contains "405 Allow Header contains POST" "$allow_headers" "POST"

if [ $errors -eq 0 ]; then
    echo "── All HTTP Server / Router integration tests PASSED ──"
    exit 0
else
    echo "── HTTP Server / Router integration tests FAILED with $errors error(s) ──"
    exit 1
fi
