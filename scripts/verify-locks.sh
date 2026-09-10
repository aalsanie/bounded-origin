#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"

trap 'rm -rf "$tmp_dir"' EXIT

lock_files=(
  "bounded-origin-api/gradle.lockfile"
  "bounded-origin-core/gradle.lockfile"
  "bounded-origin-store-fs/gradle.lockfile"
  "bounded-origin-proxy/gradle.lockfile"
  "bounded-origin-cli/gradle.lockfile"
  "bounded-origin-benchmarks/gradle.lockfile"
  "test-infra/gradle.lockfile"
)

for relative_path in "${lock_files[@]}"; do
  source_file="$repo_root/$relative_path"

  if [[ ! -f "$source_file" ]]; then
    echo "Missing dependency lock file: $relative_path" >&2
    exit 1
  fi

  snapshot_file="$tmp_dir/$relative_path"
  mkdir -p "$(dirname "$snapshot_file")"
  cp "$source_file" "$snapshot_file"
done

"$repo_root/gradlew" \
  :bounded-origin-api:dependencies \
  :bounded-origin-core:dependencies \
  :bounded-origin-store-fs:dependencies \
  :bounded-origin-proxy:dependencies \
  :bounded-origin-cli:dependencies \
  :bounded-origin-benchmarks:dependencies \
  :test-infra:dependencies \
  --write-locks

changed=()

for relative_path in "${lock_files[@]}"; do
  if ! cmp -s \
    "$tmp_dir/$relative_path" \
    "$repo_root/$relative_path"; then
    changed+=("$relative_path")
  fi
done

if (( ${#changed[@]} > 0 )); then
  echo "Dependency lock state is stale. Regeneration changed:" >&2

  for relative_path in "${changed[@]}"; do
    echo "  $relative_path" >&2
  done

  exit 1
fi
