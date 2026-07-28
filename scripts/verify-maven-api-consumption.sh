#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
version="${2:-$(sed -n 's/^risingStonesVersion=//p' "$root/gradle.properties" | tail -n 1)}"
consumer_root="$root/integration-tests/maven-consumer"
isolated_surfaces=(
  core
  network
  auth-webview
  account-data
  account-presentation
  forum-domain
  ui-compose
)

if [[ -z "$version" ]]; then
  echo "Unable to resolve risingStonesVersion" >&2
  exit 1
fi

for surface in "${isolated_surfaces[@]}"; do
  "$root/gradlew" \
    -p "$consumer_root" \
    :consumer:compileDebugKotlin \
    -PrisingStonesVersion="$version" \
    -PrisingStonesConsumerSurface="$surface"
done

"$root/gradlew" \
  -p "$consumer_root" \
  :consumer:compileDebugKotlin \
  -PrisingStonesVersion="$version" \
  -PrisingStonesConsumerSurface=aggregate

echo "Maven consumption verified for ${#isolated_surfaces[@]} isolated public API surfaces and the aggregate feature smoke test"
