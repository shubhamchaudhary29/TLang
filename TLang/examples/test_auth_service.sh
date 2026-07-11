#!/usr/bin/env bash
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "── Building TLang ──"
"$PROJECT_DIR/scripts/build.sh" 2>&1

echo "── Setting up configuration (.env) ──"
cat <<EOF > .env
PORT=8087
JWT_SECRET=super-secret-tlang-key-123
DB_PATH=test_auth.db
EOF

# Clean any existing DB
rm -f test_auth.db
rm -f auth_server.log

echo "── Starting Auth Service on port 8087 ──"
java -cp "$PROJECT_DIR/build/classes/java/main:$PROJECT_DIR/build/dependencies/*" dev.tlang.Main "$PROJECT_DIR/examples/auth_service.tiny" > auth_server.log 2>&1 &
SERVER_PID=$!

cleanup() {
    echo "── Stopping Auth Service ──"
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    rm -f .env
    rm -f test_auth.db
    rm -f auth_server.log
}
trap cleanup EXIT

# Wait for server to start and bind
sleep 1.5

errors=0

check_status() {
    local label="$1"
    local actual="$2"
    local expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: [Status] $label"
    else
        echo "FAIL: [Status] $label"
        echo "  Expected: $expected"
        echo "  Got:      $actual"
        errors=$((errors+1))
    fi
}

check_body_contains() {
    local label="$1"
    local body="$2"
    local expected_substring="$3"
    if [[ "$body" == *"$expected_substring"* ]]; then
        echo "PASS: [Body] $label"
    else
        echo "FAIL: [Body] $label"
        echo "  Expected to contain: $expected_substring"
        echo "  Got:                 $body"
        errors=$((errors+1))
    fi
}

echo "── Running Integration Tests ──"

# 1. Signup Validation Failures (Invalid Email & Short Password)
res=$(curl -s -w "\n%{http_code}" -X POST -d '{"email":"invalid-email","password":"123"}' http://localhost:8087/signup)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "Signup invalid email & short password" "$status" "400"
check_body_contains "Signup validation errors in body" "$body" "email must match pattern"
check_body_contains "Signup validation errors in body" "$body" "password length must be at least 6"

# 2. Successful Signup
res=$(curl -s -w "\n%{http_code}" -X POST -d '{"email":"test@example.com","password":"secretpassword"}' http://localhost:8087/signup)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "Successful signup" "$status" "201"
check_body_contains "Signup returns user email" "$body" '"email":"test@example.com"'
check_body_contains "Signup returns user id" "$body" '"id":1'

# 3. Duplicate User Signup
res=$(curl -s -w "\n%{http_code}" -X POST -d '{"email":"test@example.com","password":"secretpassword"}' http://localhost:8087/signup)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "Duplicate signup" "$status" "400"
check_body_contains "Duplicate signup error message" "$body" "User already exists"

# 4. Login with Incorrect Password
res=$(curl -s -w "\n%{http_code}" -X POST -d '{"email":"test@example.com","password":"wrongpassword"}' http://localhost:8087/login)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "Login incorrect password" "$status" "401"
check_body_contains "Incorrect password error message" "$body" "Invalid email or password"

# 5. Successful Login (and extract JWT token)
res=$(curl -s -w "\n%{http_code}" -X POST -d '{"email":"test@example.com","password":"secretpassword"}' http://localhost:8087/login)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "Successful login" "$status" "200"
check_body_contains "Login returns token" "$body" '"token":'

# Extract token
token=$(echo "$body" | grep -o '"token":"[^"]*' | grep -o '[^"]*$')

# 6. GET /me without Authorization header
res=$(curl -s -w "\n%{http_code}" http://localhost:8087/me)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "GET /me missing header" "$status" "401"
check_body_contains "GET /me missing header error message" "$body" "Authorization header required"

# 7. GET /me with Invalid Token
res=$(curl -s -w "\n%{http_code}" -H "Authorization: Bearer invalid-token" http://localhost:8087/me)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "GET /me invalid token" "$status" "401"
check_body_contains "GET /me invalid token error message" "$body" "Invalid token"

# 8. GET /me with Valid Token
res=$(curl -s -w "\n%{http_code}" -H "Authorization: Bearer $token" http://localhost:8087/me)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "GET /me valid token" "$status" "200"
check_body_contains "GET /me valid token returns user id" "$body" '"id":1'
check_body_contains "GET /me valid token returns email" "$body" '"email":"test@example.com"'

# 9. GET /crash to verify request handler exception robustness
res=$(curl -s -w "\n%{http_code}" http://localhost:8087/crash)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "GET /crash" "$status" "500"
check_body_contains "GET /crash returns 500 containing RuntimeError" "$body" "Runtime Error: Division by zero."

# 10. Verify that the server did NOT crash and is still fully responsive
res=$(curl -s -w "\n%{http_code}" -H "Authorization: Bearer $token" http://localhost:8087/me)
body=$(echo "$res" | head -n 1)
status=$(echo "$res" | tail -n 1)
check_status "GET /me post-crash check" "$status" "200"
check_body_contains "GET /me post-crash works" "$body" '"id":1'

if [ $errors -eq 0 ]; then
    echo "── All Auth Service integration tests PASSED ──"
    exit 0
else
    echo "── Auth Service integration tests FAILED with $errors error(s) ──"
    exit 1
fi
