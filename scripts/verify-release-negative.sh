#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

STAGING="$ROOT/build/central-staging"
BASELINE="$(mktemp -d)"
BUILD_BACKUP="$(mktemp)"
LOG="$(mktemp)"
trap 'cp "$BUILD_BACKUP" "$ROOT/build.gradle.kts"; rm -rf "$BASELINE"; rm -f "$BUILD_BACKUP" "$LOG"' EXIT

test -d "$STAGING"
cp "$ROOT/build.gradle.kts" "$BUILD_BACKUP"
cp -a "$STAGING/." "$BASELINE/"

excluded_stage_tasks=(
  -x stageCentralPublications
  -x cleanCentralStaging
  -x :bounded-origin-api:stageCentralPublication
  -x :bounded-origin-core:stageCentralPublication
  -x :bounded-origin-store-fs:stageCentralPublication
  -x :bounded-origin-proxy:stageCentralPublication
)

restore_staging() {
  rm -rf "$STAGING"
  mkdir -p "$STAGING"
  cp -a "$BASELINE/." "$STAGING/"
}

expect_failure() {
  local label="$1"
  shift
  if "$@" >"$LOG" 2>&1; then
    echo "$label unexpectedly succeeded" >&2
    cat "$LOG" >&2
    exit 1
  fi
}

verify_mutated_staging_fails() {
  local label="$1"
  expect_failure "$label" ./gradlew verifyCentralStaging \
    "${excluded_stage_tasks[@]}" --rerun-tasks --no-daemon --stacktrace
}

expect_failure "missing signing key" env \
  -u ORG_GRADLE_PROJECT_signingKey \
  -u ORG_GRADLE_PROJECT_signingPassword \
  -u ORG_GRADLE_PROJECT_signingKeyId \
  ./gradlew :bounded-origin-api:verifyCentralSigningCredentials --no-daemon --stacktrace

expect_failure "malformed signing key" env \
  ORG_GRADLE_PROJECT_signingKey=not-a-pgp-key \
  ORG_GRADLE_PROJECT_signingPassword= \
  ./gradlew :bounded-origin-api:signMavenJavaPublication \
    --rerun-tasks --no-daemon --stacktrace

restore_staging
rm "$STAGING/io/github/aalsanie/bounded-origin-api/0.1.0/bounded-origin-api-0.1.0.jar"
verify_mutated_staging_fails "missing publication artifact"

restore_staging
printf '%064d\n' 0 > \
  "$STAGING/io/github/aalsanie/bounded-origin-api/0.1.0/bounded-origin-api-0.1.0.jar.sha256"
verify_mutated_staging_fails "corrupt checksum"

restore_staging
touch "$STAGING/unexpected-release-file"
verify_mutated_staging_fails "unexpected Central file"

restore_staging
mkdir -p "$STAGING/io/github/aalsanie/test-infra/0.1.0"
touch "$STAGING/io/github/aalsanie/test-infra/0.1.0/test-infra-0.1.0.jar"
verify_mutated_staging_fails "internal module exposure"

restore_staging
pom="$STAGING/io/github/aalsanie/bounded-origin-api/0.1.0/bounded-origin-api-0.1.0.pom"
sed -i 's#Apache License, Version 2.0#Wrong License#' "$pom"
verify_mutated_staging_fails "wrong POM license"

restore_staging
jar="$STAGING/io/github/aalsanie/bounded-origin-api/0.1.0/bounded-origin-api-0.1.0.jar"
printf 'tamper' >> "$jar"
expect_failure "tampered signed artifact" gpg --batch --verify "$jar.asc" "$jar"

cp "$BUILD_BACKUP" "$ROOT/build.gradle.kts"
sed -i 's/val releaseVersion = "0.1.0"/val releaseVersion = "0.1.0-SNAPSHOT"/'   "$ROOT/build.gradle.kts"
expect_failure "snapshot release revision" ./gradlew verifyReleaseRevision --no-daemon --stacktrace
cp "$BUILD_BACKUP" "$ROOT/build.gradle.kts"

restore_staging

echo "negative release verification passed"
