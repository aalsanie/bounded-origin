#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="bounded-origin-smoke:${GITHUB_SHA:-local}"
network="bounded-origin-smoke-$RANDOM-$$"
origin="bounded-origin-smoke-origin-$$"
gateway="bounded-origin-smoke-gateway-$$"

cleanup() {
  docker rm -f "$gateway" "$origin" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  docker image rm -f "$image" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$repo_root"
./gradlew :bounded-origin-proxy:prepareDockerSmoke

docker build \
  --pull=false \
  --file bounded-origin-proxy/src/test/docker/Dockerfile \
  --tag "$image" \
  bounded-origin-proxy/build/docker-smoke

docker network create "$network" >/dev/null

docker run --detach --rm \
  --name "$origin" \
  --network "$network" \
  --network-alias origin \
  "$image" \
  io.github.aalsanie.boundedorigin.proxy.SmokeOriginMain >/dev/null

docker run --detach --rm \
  --name "$gateway" \
  --network "$network" \
  --network-alias gateway \
  "$image" \
  io.github.aalsanie.boundedorigin.proxy.SmokeGatewayMain >/dev/null

docker run --rm \
  --network "$network" \
  "$image" \
  io.github.aalsanie.boundedorigin.proxy.SmokeClientMain
