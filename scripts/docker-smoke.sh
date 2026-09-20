#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="bounded-origin-smoke:${GITHUB_SHA:-local}"
network="bounded-origin-smoke-$RANDOM-$$"
volume="bounded-origin-smoke-store-$RANDOM-$$"
origin="bounded-origin-smoke-origin-$$"
gateway="bounded-origin-smoke-gateway-$$"
context="$repo_root/bounded-origin-cli/build/docker-smoke"

cleanup() {
  docker rm -f "$gateway" "$origin" >/dev/null 2>&1 || true
  docker volume rm -f "$volume" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  docker image rm -f "$image" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$repo_root"
./gradlew :bounded-origin-cli:distTar :bounded-origin-proxy:testClasses

rm -rf "$context"
mkdir -p "$context"
archive="$(find bounded-origin-cli/build/distributions -maxdepth 1 -type f -name '*.tar' -print -quit)"
if [[ -z "$archive" ]]; then
  echo "bounded-origin distribution tar was not produced" >&2
  exit 1
fi

tar -xf "$archive" -C "$context"
distribution="$(find "$context" -mindepth 1 -maxdepth 1 -type d -name 'bounded-origin-*' -print -quit)"
if [[ -z "$distribution" ]]; then
  echo "bounded-origin distribution root was not found" >&2
  exit 1
fi
mv "$distribution" "$context/distribution"
cp -R bounded-origin-proxy/build/classes/java/test "$context/test-classes"
cp bounded-origin-cli/src/test/docker/Dockerfile "$context/Dockerfile"
cp bounded-origin-cli/src/test/docker/smoke.yaml "$context/smoke.yaml"

docker build   --pull=false   --file "$context/Dockerfile"   --tag "$image"   "$context"

docker network create "$network" >/dev/null
docker volume create "$volume" >/dev/null

docker run --detach   --name "$origin"   --network "$network"   --network-alias origin   "$image"   java -cp /opt/smoke/test-classes   io.github.aalsanie.boundedorigin.proxy.SmokeOriginMain >/dev/null

start_gateway() {
  docker run --detach     --name "$gateway"     --network "$network"     --network-alias gateway     --volume "$volume:/var/lib/bounded-origin"     "$image"     /opt/bounded-origin/bin/bounded-origin     run --config /etc/bounded-origin/smoke.yaml >/dev/null
}

run_client() {
  expected="$1"
  docker run --rm     --network "$network"     "$image"     java -cp /opt/smoke/test-classes     io.github.aalsanie.boundedorigin.proxy.SmokeClientMain "$expected"
}

start_gateway
run_client 1

docker stop --time 10 "$gateway" >/dev/null
docker rm "$gateway" >/dev/null

start_gateway
run_client 0
