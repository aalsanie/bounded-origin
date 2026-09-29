#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${CENTRAL_PORTAL_URL:-https://central.sonatype.com/api/v1/publisher}"
PUBLIC_REPOSITORY="${CENTRAL_PUBLIC_REPOSITORY:-https://repo.maven.apache.org/maven2}"
MODULES=(
  bounded-origin-api
  bounded-origin-core
  bounded-origin-store-fs
  bounded-origin-proxy
)

require_authorization() {
  if [[ -z "${CENTRAL_AUTHORIZATION:-}" ]]; then
    echo "CENTRAL_AUTHORIZATION is required" >&2
    exit 2
  fi
}

request() {
  local method="$1"
  local url="$2"
  shift 2
  require_authorization
  curl --fail-with-body --silent --show-error \
    --retry 3 --retry-all-errors \
    --connect-timeout 15 --max-time 120 \
    --request "$method" \
    --header "Authorization: $CENTRAL_AUTHORIZATION" \
    "$@" "$url"
}

upload() {
  local bundle="$1"
  local name="$2"
  test -s "$bundle"
  if [[ ! "$name" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "Central deployment name contains unsupported characters: $name" >&2
    exit 64
  fi

  local deployment_id
  deployment_id="$(
    request POST "$BASE_URL/upload?publishingType=USER_MANAGED&name=$name" \
      --form "bundle=@$bundle;type=application/octet-stream"
  )"
  deployment_id="${deployment_id//$'\r'/}"
  deployment_id="${deployment_id//$'\n'/}"
  if [[ ! "$deployment_id" =~ ^[0-9a-fA-F-]{36}$ ]]; then
    echo "Central returned an invalid deployment ID: $deployment_id" >&2
    exit 1
  fi
  printf '%s\n' "$deployment_id"
}

status() {
  local deployment_id="$1"
  request POST "$BASE_URL/status?id=$deployment_id"
}

wait_state() {
  local deployment_id="$1"
  local target="$2"
  local attempts="${3:-120}"
  local interval="${4:-15}"
  local state=""
  local body=""

  for ((i = 1; i <= attempts; i++)); do
    body="$(status "$deployment_id")"
    state="$(jq -r '.deploymentState // empty' <<<"$body")"
    if [[ "$state" == "$target" ]]; then
      printf '%s\n' "$body"
      return 0
    fi
    if [[ "$state" == "FAILED" ]]; then
      echo "Central deployment failed:" >&2
      jq . <<<"$body" >&2
      return 1
    fi

    case "$target:$state" in
      VALIDATED:PENDING|VALIDATED:VALIDATING|PUBLISHED:PENDING|PUBLISHED:VALIDATING|PUBLISHED:VALIDATED|PUBLISHED:PUBLISHING)
        ;;
      *)
        echo "Unexpected Central deployment state '$state' while waiting for '$target'" >&2
        jq . <<<"$body" >&2
        return 1
        ;;
    esac
    sleep "$interval"
  done

  echo "Timed out waiting for Central deployment $deployment_id to reach $target" >&2
  return 1
}

publish() {
  local deployment_id="$1"
  request POST "$BASE_URL/deployment/$deployment_id" >/dev/null
}

drop() {
  local deployment_id="$1"
  request DELETE "$BASE_URL/deployment/$deployment_id" >/dev/null
}

public_count() {
  local version="$1"
  local count=0
  local module
  for module in "${MODULES[@]}"; do
    local url="$PUBLIC_REPOSITORY/io/github/aalsanie/$module/$version/$module-$version.pom"
    if curl --fail --silent --show-error --head \
      --connect-timeout 10 --max-time 30 "$url" >/dev/null 2>&1; then
      count=$((count + 1))
    fi
  done
  printf '%s\n' "$count"
}

wait_public() {
  local version="$1"
  local attempts="${2:-120}"
  local interval="${3:-15}"
  local count

  for ((i = 1; i <= attempts; i++)); do
    count="$(public_count "$version")"
    if [[ "$count" == "${#MODULES[@]}" ]]; then
      return 0
    fi
    sleep "$interval"
  done

  echo "Timed out waiting for all Maven artifacts to become public" >&2
  return 1
}

case "${1:-}" in
  upload)
    [[ $# == 3 ]] || { echo "usage: $0 upload BUNDLE NAME" >&2; exit 64; }
    upload "$2" "$3"
    ;;
  status)
    [[ $# == 2 ]] || { echo "usage: $0 status DEPLOYMENT_ID" >&2; exit 64; }
    status "$2"
    ;;
  wait-state)
    [[ $# -ge 3 && $# -le 5 ]] || { echo "usage: $0 wait-state DEPLOYMENT_ID STATE [ATTEMPTS] [INTERVAL]" >&2; exit 64; }
    wait_state "$2" "$3" "${4:-120}" "${5:-15}"
    ;;
  publish)
    [[ $# == 2 ]] || { echo "usage: $0 publish DEPLOYMENT_ID" >&2; exit 64; }
    publish "$2"
    ;;
  drop)
    [[ $# == 2 ]] || { echo "usage: $0 drop DEPLOYMENT_ID" >&2; exit 64; }
    drop "$2"
    ;;
  public-count)
    [[ $# == 2 ]] || { echo "usage: $0 public-count VERSION" >&2; exit 64; }
    public_count "$2"
    ;;
  wait-public)
    [[ $# -ge 2 && $# -le 4 ]] || { echo "usage: $0 wait-public VERSION [ATTEMPTS] [INTERVAL]" >&2; exit 64; }
    wait_public "$2" "${3:-120}" "${4:-15}"
    ;;
  *)
    echo "usage: $0 {upload|status|wait-state|publish|drop|public-count|wait-public} ..." >&2
    exit 64
    ;;
esac
