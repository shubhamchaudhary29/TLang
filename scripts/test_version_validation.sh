#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
project_version="$(sed -n 's/^version=//p' gradle.properties)"
snapshot_tag="v${project_version%-SNAPSHOT}"

expect_failure() {
    local expected="$1"
    shift
    local output
    if output=$("$@" 2>&1); then
        echo "expected version validation to fail: $*" >&2
        exit 1
    fi
    grep -Fq "$expected" <<<"$output"
}

./gradlew -q verifyVersion
RELEASE_TAG=v0.1.1 ./gradlew -q -Pversion=0.1.1 verifyVersion
expect_failure 'Version must be a semantic version' ./gradlew -q -Pversion= verifyVersion
expect_failure 'Version must be a semantic version' ./gradlew -q -Pversion=v0.1.1 verifyVersion
expect_failure 'Release tag must be' env RELEASE_TAG=release-0.1.1 ./gradlew -q -Pversion=0.1.1 verifyVersion
expect_failure 'does not match project version' env RELEASE_TAG=v0.1.2 ./gradlew -q -Pversion=0.1.1 verifyVersion
expect_failure 'Snapshot versions cannot be released' env RELEASE_TAG="$snapshot_tag" ./gradlew -q verifyVersion
./gradlew -q installDist
test "$(build/install/tlang/bin/tlang version)" = "TLang version $project_version"
