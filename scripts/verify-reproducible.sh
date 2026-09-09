#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cd "$ROOT"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

./gradlew clean assemble
find . -path '*/build/libs/*.jar' -type f -print0 | sort -z | xargs -0 sha256sum > "$TMP_DIR/first.sha256"

./gradlew clean assemble
find . -path '*/build/libs/*.jar' -type f -print0 | sort -z | xargs -0 sha256sum > "$TMP_DIR/second.sha256"

diff -u "$TMP_DIR/first.sha256" "$TMP_DIR/second.sha256"
