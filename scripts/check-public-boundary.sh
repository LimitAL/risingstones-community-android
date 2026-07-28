#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
production_roots=()

for source_root in "$root"/*/src/main; do
  if [[ -d "$source_root" ]]; then
    production_roots+=("$source_root")
  fi
done

if [[ ${#production_roots[@]} -eq 0 ]]; then
  echo "No production source roots found under $root" >&2
  exit 1
fi

foreign_package_references="$(
  grep -R -n -E \
    --include='*.kt' \
    --include='*.xml' \
    'top\.cxmeow\.' \
    "${production_roots[@]}" |
    grep -v -E 'top\.cxmeow\.risingstones([./"]|$)' ||
    true
)"
if [[ -n "$foreign_package_references" ]]; then
  echo "$foreign_package_references"
  echo "Public production source contains a package outside top.cxmeow.risingstones" >&2
  exit 1
fi

forbidden_build_pattern='includeBuild\(|/Volumes/Store|/Users/|file://'
if grep -R -n -E \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  --include='*.gradle.kts' \
  --include='settings.gradle.kts' \
  "$forbidden_build_pattern" \
  "$root"; then
  echo "Public build configuration contains an external checkout or local absolute path" >&2
  exit 1
fi

echo "Public repository boundary check passed"
