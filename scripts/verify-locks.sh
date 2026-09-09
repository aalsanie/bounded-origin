#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cd "$ROOT"

./gradlew dependencies --write-locks >/dev/null
if ! git diff --exit-code -- '*/gradle.lockfile'; then
    echo "Dependency lock drift detected." >&2
    exit 1
fi
