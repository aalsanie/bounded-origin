#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash -n scripts/central-portal.sh
bash -n scripts/verify-release-negative.sh
bash -n scripts/verify-release-reproducible.sh

grep -F "tags: ['v*']" .github/workflows/release.yml >/dev/null
grep -F "if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/')"   .github/workflows/release.yml >/dev/null
grep -F "environment: release" .github/workflows/release.yml >/dev/null
grep -F "environment: release" .github/workflows/central-preflight.yml >/dev/null

if grep -F "central-portal.sh publish" .github/workflows/central-preflight.yml >/dev/null; then
  echo "Central preflight must never publish a deployment" >&2
  exit 1
fi

if grep -R -F "publishingType=AUTOMATIC"   .github/workflows scripts/central-portal.sh >/dev/null; then
  echo "AUTOMATIC Central publishing is forbidden" >&2
  exit 1
fi

if grep -E "CENTRAL_TOKEN_|MAVEN_GPG_PRIVATE_KEY|MAVEN_GPG_PASSPHRASE"   .github/workflows/release-verification.yml >/dev/null; then
  echo "Release Verification must not consume production release secrets" >&2
  exit 1
fi

publish_calls="$(
  grep -R -F "central-portal.sh publish" .github/workflows | cut -d: -f1 | sort -u
)"
if [[ "$publish_calls" != ".github/workflows/release.yml" ]]; then
  echo "Only the tag-governed release workflow may invoke Central publish" >&2
  printf '%s\n' "$publish_calls" >&2
  exit 1
fi

echo "release workflow policy verified"
