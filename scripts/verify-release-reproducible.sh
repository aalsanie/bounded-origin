#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

snapshot() {
  local output="$1"
  : > "$output"

  local consumer_count
  consumer_count="$(find build/consumer-repository -type f | wc -l | tr -d ' ')"
  if [[ "$consumer_count" != "16" ]]; then
    echo "expected 16 staged Maven primary files, found $consumer_count" >&2
    exit 1
  fi

  while IFS= read -r -d '' file; do
    printf '%s  %s\n' "$(sha256sum "$file" | awk '{print $1}')" "$file" >> "$output"
  done < <(find build/consumer-repository -type f -print0 | sort -z)

  for file in \
    bounded-origin-cli/build/distributions/bounded-origin-0.1.0.tar \
    bounded-origin-cli/build/distributions/bounded-origin-0.1.0.zip \
    bounded-origin-cli/build/distributions/SHA256SUMS
  do
    test -s "$file"
    printf '%s  %s\n' "$(sha256sum "$file" | awk '{print $1}')" "$file" >> "$output"
  done
}

build_release_inputs() {
  ./gradlew clean \
    stageConsumerPublications \
    :bounded-origin-cli:cliReleaseArtifacts \
    --no-daemon --stacktrace
}

build_release_inputs
snapshot "$TMP_DIR/first.sha256"

build_release_inputs
snapshot "$TMP_DIR/second.sha256"

if ! cmp -s "$TMP_DIR/first.sha256" "$TMP_DIR/second.sha256"; then
  echo "release artifacts are not reproducible" >&2
  diff -u "$TMP_DIR/first.sha256" "$TMP_DIR/second.sha256" || true
  exit 1
fi

echo "release artifacts are reproducible"
