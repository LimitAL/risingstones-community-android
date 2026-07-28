#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

required_files=(
  .editorconfig
  .gitattributes
  .gitignore
  .github/dependabot.yml
  .github/pull_request_template.md
  .github/workflows/release-candidate.yml
  .github/workflows/verify.yml
  CONTRIBUTING.md
  LICENSE
  README.md
  SECURITY.md
  app/src/main/AndroidManifest.xml
  app/src/main/res/xml/backup_rules.xml
  app/src/main/res/xml/data_extraction_rules.xml
  docs/architecture.md
  docs/compatibility-matrix.md
  docs/dependency-licenses.md
  docs/host-integration.md
  docs/release-checklist.md
  gradle/gradle-daemon-jvm.properties
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.jar
  gradle/wrapper/gradle-wrapper.properties
  gradlew
  gradlew.bat
  scripts/verify-dependency-license-inventory.sh
  scripts/verify-layer-boundaries.sh
  scripts/verify-maven-api-consumption.sh
  scripts/verify-public-api-dependencies.sh
  scripts/verify-public-documentation.sh
  scripts/verify-public-source-safety.sh
  scripts/verify-sensitive-data-boundary.sh
  scripts/verify-string-localizations.sh
  settings.gradle.kts
  scripts/test-public-source-safety-gate.sh
)

for path in "${required_files[@]}"; do
  if [[ ! -f "$path" ]]; then
    echo "Missing required standalone repository file: $path" >&2
    exit 1
  fi
done

bash scripts/verify-dependency-license-inventory.sh
bash scripts/verify-layer-boundaries.sh
bash scripts/verify-public-api-dependencies.sh
bash scripts/verify-public-documentation.sh
bash scripts/verify-public-source-safety.sh
bash scripts/test-public-source-safety-gate.sh
bash scripts/verify-sensitive-data-boundary.sh
bash scripts/verify-string-localizations.sh

settings_modules="$(
  sed -nE 's/^include\(":(.+)"\)$/\1/p' settings.gradle.kts |
    LC_ALL=C sort
)"
directory_modules="$(
  for build_file in */build.gradle.kts; do
    dirname "$build_file"
  done |
    LC_ALL=C sort
)"

if [[ "$settings_modules" != "$directory_modules" ]]; then
  echo "settings.gradle.kts modules do not match top-level Android module directories" >&2
  echo "Settings modules:" >&2
  echo "$settings_modules" >&2
  echo "Directory modules:" >&2
  echo "$directory_modules" >&2
  exit 1
fi

if find . -maxdepth 3 -type f \
  \( -name google-services.json -o -name '.env' -o -name '.env.*' \
  -o -name '*.jks' -o -name '*.keystore' \) \
  -print -quit |
  grep -q .; then
  echo "Standalone repository contains local, service, or signing material" >&2
  exit 1
fi

if ! grep -q '^distributionSha256Sum=[0-9a-f][0-9a-f]*$' \
  gradle/wrapper/gradle-wrapper.properties; then
  echo "Gradle wrapper distribution checksum is missing" >&2
  exit 1
fi

while IFS= read -r action_reference; do
  if [[ "$action_reference" == ./* ]]; then
    continue
  fi
  if [[ ! "$action_reference" =~ @[0-9a-f]{40}$ ]]; then
    echo "External GitHub Action is not pinned to a full commit SHA: $action_reference" >&2
    exit 1
  fi
done < <(
  sed -nE 's/^[[:space:]]*uses:[[:space:]]*([^[:space:]#]+).*/\1/p' \
    .github/workflows/*.yml \
    .github/workflows/*.yaml 2> /dev/null
)

echo "Standalone repository layout verified for $(echo "$settings_modules" | wc -l | tr -d ' ') modules"
