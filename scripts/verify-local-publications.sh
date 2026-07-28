#!/usr/bin/env bash
set -euo pipefail

repository="${1:-build/repository}"
version="${2:-$(sed -n 's/^risingStonesVersion=//p' gradle.properties | tail -n 1)}"
require_release_metadata="${3:-false}"
group_path="top/cxmeow/risingstones"
modules=()
while IFS= read -r module; do
  if [[ "$module" != "app" ]]; then
    modules+=("$module")
  fi
done < <(
  sed -nE 's/^include\(":(.+)"\)$/\1/p' settings.gradle.kts |
    LC_ALL=C sort
)

if [[ -z "$version" ]]; then
  echo "Unable to resolve risingStonesVersion from gradle.properties" >&2
  exit 1
fi

if [[ ${#modules[@]} -eq 0 ]]; then
  echo "Unable to resolve public library modules from settings.gradle.kts" >&2
  exit 1
fi

for module in "${modules[@]}"; do
  artifact_directory="$repository/$group_path/$module/$version"
  if [[ ! -d "$artifact_directory" ]]; then
    echo "Missing publication directory: $artifact_directory" >&2
    exit 1
  fi

  for pattern in \
    "$module-*.aar" \
    "$module-*.pom" \
    "$module-*.module" \
    "$module-*-sources.jar" \
    "$module-*-javadoc.jar"; do
    if ! find "$artifact_directory" -maxdepth 1 -type f -name "$pattern" -print -quit |
      grep -q .; then
      echo "Missing published artifact matching $artifact_directory/$pattern" >&2
      exit 1
    fi
  done

  pom="$(find "$artifact_directory" -maxdepth 1 -type f -name "$module-*.pom" -print |
    sort |
    tail -n 1)"
  grep -q "<groupId>top.cxmeow.risingstones</groupId>" "$pom"
  grep -q "<artifactId>$module</artifactId>" "$pom"
  grep -q "<packaging>aar</packaging>" "$pom"
  if grep -q "<version>unspecified</version>" "$pom"; then
    echo "Invalid unspecified dependency version in $pom" >&2
    exit 1
  fi
  if [[ "$require_release_metadata" == "true" ]]; then
    for element in url licenses developers email scm connection developerConnection; do
      if ! grep -q "<$element>" "$pom"; then
        echo "Missing release POM element <$element> in $pom" >&2
        exit 1
      fi
    done
  fi
done

echo "Verified ${#modules[@]} public Maven publications for version $version"
if [[ "$require_release_metadata" == "true" ]]; then
  echo "Verified required public release POM metadata"
fi
