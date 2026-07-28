#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

project_dependencies() {
  module="$1"
  sed -nE 's/.*project\(":(.+)"\).*/\1/p' "$module/build.gradle.kts"
}

assert_project_dependencies() {
  module="$1"
  shift

  unparsed_project_lines="$(
    grep -n -E 'project\(' "$module/build.gradle.kts" |
      grep -v -E 'project\(":[^"]+"\)' ||
      true
  )"
  if [[ -n "$unparsed_project_lines" ]]; then
    echo "Unrecognized project dependency syntax in $module/build.gradle.kts:" >&2
    echo "$unparsed_project_lines" >&2
    exit 1
  fi
  if grep -n -E 'projects\.[A-Za-z0-9_.]+' "$module/build.gradle.kts"; then
    echo "Type-safe project dependency syntax in $module is not covered by the layer verifier" >&2
    exit 1
  fi

  while IFS= read -r dependency; do
    if [[ -z "$dependency" ]]; then
      continue
    fi
    allowed=false
    for candidate in "$@"; do
      if [[ "$dependency" == "$candidate" ]]; then
        allowed=true
        break
      fi
    done
    if [[ "$allowed" != true ]]; then
      echo "Disallowed project dependency: $module -> $dependency" >&2
      exit 1
    fi
  done < <(project_dependencies "$module")
}

assert_no_source_pattern() {
  description="$1"
  pattern="$2"
  shift 2

  if grep -R -n -E \
    --include='*.kt' \
    "$pattern" \
    "$@"; then
    echo "$description" >&2
    exit 1
  fi
}

assert_project_dependencies core
assert_project_dependencies network core
assert_project_dependencies auth-webview core network
assert_project_dependencies ui-compose core auth-webview

for module in *-domain; do
  assert_project_dependencies "$module" core
done

for module in *-data; do
  feature="${module%-data}"
  assert_project_dependencies "$module" "$feature-domain" core network
done

for module in *-presentation; do
  feature="${module%-presentation}"
  assert_project_dependencies "$module" "$feature-domain" core
done

for module in *-ui-compose; do
  feature="${module%-ui-compose}"
  assert_project_dependencies \
    "$module" \
    "$feature-domain" \
    "$feature-presentation" \
    core \
    auth-webview
done

non_ui_build_files=(
  core/build.gradle.kts
  network/build.gradle.kts
  auth-webview/build.gradle.kts
  *-domain/build.gradle.kts
  *-data/build.gradle.kts
  *-presentation/build.gradle.kts
)
if grep -n -E \
  'libs\.(androidx\.activity\.compose|androidx\.compose|coil\.)' \
  "${non_ui_build_files[@]}"; then
  echo "A reusable non-UI module depends on a Compose or UI-image library" >&2
  exit 1
fi

domain_roots=( *-domain/src/main )
data_roots=( *-data/src/main )
presentation_roots=( *-presentation/src/main )
feature_ui_roots=( *-ui-compose/src/main )

assert_no_source_pattern \
  "Domain source depends on Android, transport, authentication, or another implementation layer" \
  '^import[[:space:]]+(android\.|androidx\.|okhttp3\.|kotlinx\.serialization\.|top\.cxmeow\.risingstones\.(network|auth\.webview)\.|top\.cxmeow\.risingstones\.feature\.[^.]+\.(data|presentation|ui)\.)' \
  "${domain_roots[@]}"

assert_no_source_pattern \
  "Data source depends on Compose, presentation, or UI implementation" \
  '^import[[:space:]]+(androidx\.compose\.|top\.cxmeow\.risingstones\.feature\.[^.]+\.(presentation|ui)\.)' \
  "${data_roots[@]}"

assert_no_source_pattern \
  "Presentation source depends on Android UI, transport, WebView authentication, data, or UI implementation" \
  '^import[[:space:]]+(android\.|androidx\.compose\.|okhttp3\.|kotlinx\.serialization\.|top\.cxmeow\.risingstones\.(network|auth\.webview)\.|top\.cxmeow\.risingstones\.feature\.[^.]+\.(data|ui)\.)' \
  "${presentation_roots[@]}"

assert_no_source_pattern \
  "Optional Compose UI imports a feature data implementation instead of domain/presentation contracts" \
  '^import[[:space:]]+top\.cxmeow\.risingstones\.feature\.[^.]+\.data\.' \
  "${feature_ui_roots[@]}"

assert_no_source_pattern \
  "Core or network source depends on a feature or Compose UI" \
  '^import[[:space:]]+(androidx\.compose\.|top\.cxmeow\.risingstones\.feature\.)' \
  core/src/main \
  network/src/main

assert_no_source_pattern \
  "WebView authentication source depends on a feature or Compose UI" \
  '^import[[:space:]]+(androidx\.compose\.|top\.cxmeow\.risingstones\.feature\.)' \
  auth-webview/src/main

echo "Domain, data, presentation, authentication, network, and optional UI layer boundaries verified"
