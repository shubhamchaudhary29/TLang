#!/usr/bin/env bash
# Full test-suite runner for TLang.
# Handles:
#   - "// Expected exit code: N" metadata (defaults to 0)
#   - "// Expected output:" blocks (strips "// " prefix only, preserving indentation)
#   - Files with no expected output header (skip output comparison)
set -uo pipefail
cd "$(dirname "$0")"

echo "── Compiling ──"
./build.sh 2>&1
if [ $? -ne 0 ]; then
    echo "COMPILATION FAILED"
    exit 1
fi
echo ""

pass=0
fail=0
skip=0
errors=""

# Support files that are imported by other tests (not standalone)
SUPPORT_FILES="circ_a.tiny circ_b.tiny greeter_module.tiny invalid_module.tiny isolated_module.tiny left_module.tiny right_module.tiny shared_module.tiny test_http_client.tiny test_http_server.tiny test_middleware.tiny"

is_support_file() {
    local base=$(basename "$1")
    for s in $SUPPORT_FILES; do
        [ "$base" = "$s" ] && return 0
    done
    return 1
}

# Collect all .tiny test files
test_files=$(find ../src/test/resources -name "*.tiny" | sort)

for f in $test_files; do
    if is_support_file "$f"; then
        continue
    fi

    # Extract expected exit code (default 0)
    expected_exit=0
    first_line=$(head -1 "$f")
    if echo "$first_line" | grep -q "Expected exit code:"; then
        expected_exit=$(echo "$first_line" | sed 's/.*Expected exit code: *//')
    fi

    # Extract expected output using a python one-liner for accuracy
    expected_output=$(python3 -c "
import sys
lines = open(sys.argv[1]).readlines()
out = []
in_block = False
for line in lines:
    stripped = line.rstrip('\n')
    if stripped == '// Expected output:':
        in_block = True
        continue
    if in_block:
        if stripped.startswith('// '):
            out.append(stripped[3:])
        elif stripped == '//':
            out.append('')
        else:
            break
print('\n'.join(out))
" "$f")

    # Run the test
    actual_output=$(java -cp ../out:../lib/sqlite-jdbc-3.34.0.jar dev.tlang.Main "$f" 2>&1)
    actual_exit=$?

    # Compare exit code
    if [ "$actual_exit" -ne "$expected_exit" ]; then
        fail=$((fail+1))
        errors+="FAIL: $f (exit code: expected $expected_exit, got $actual_exit)"$'\n'
        errors+="  output: $(echo "$actual_output" | head -3)"$'\n'
        continue
    fi

    # Compare output (only if expected output is non-empty)
    if [ -n "$expected_output" ]; then
        if [ "$actual_output" != "$expected_output" ]; then
            fail=$((fail+1))
            errors+="FAIL: $f (output mismatch)"$'\n'
            diff_out=$(diff <(echo "$expected_output") <(echo "$actual_output") | head -20)
            errors+="$diff_out"$'\n'
        else
            pass=$((pass+1))
            echo "PASS: $f"
        fi
    else
        # No expected output defined — just check exit code was correct
        pass=$((pass+1))
        echo "PASS: $f (exit code only)"
    fi
done

echo ""
echo "══════════════════════════════════════"
echo "Results: $pass passed, $fail failed"
echo "══════════════════════════════════════"
if [ $fail -gt 0 ]; then
    echo ""
    echo "$errors"
    exit 1
fi
