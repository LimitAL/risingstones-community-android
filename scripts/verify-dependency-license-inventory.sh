#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

catalog_aliases="$(
  awk '
    /^\[(libraries|plugins)\]$/ {
      in_catalog = 1
      next
    }
    /^\[/ {
      in_catalog = 0
    }
    in_catalog && /^[A-Za-z0-9_-]+[[:space:]]*=/ {
      alias = $0
      sub(/[[:space:]]*=.*/, "", alias)
      print alias
    }
  ' gradle/libs.versions.toml |
    LC_ALL=C sort -u
)"

documented_aliases="$(
  sed -nE 's/^<!--[[:space:]]*catalog-aliases:[[:space:]]*(.*)[[:space:]]*-->$/\1/p' \
    docs/dependency-licenses.md |
    tr ' ' '\n' |
    sed '/^$/d' |
    LC_ALL=C sort -u
)"

if [[ "$catalog_aliases" != "$documented_aliases" ]]; then
  echo "Version-catalog aliases and dependency license inventory do not match" >&2
  echo "Catalog aliases:" >&2
  echo "$catalog_aliases" >&2
  echo "Documented aliases:" >&2
  echo "$documented_aliases" >&2
  exit 1
fi

echo "Dependency license inventory covers $(echo "$catalog_aliases" | wc -l | tr -d ' ') catalog aliases"
