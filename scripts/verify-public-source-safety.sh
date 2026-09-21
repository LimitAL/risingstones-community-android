#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

unsafe_file="$(
  find . -type f \
    -not -path './.git/*' \
    -not -path '*/.gradle/*' \
    -not -path '*/build/*' \
    \( \
      -name 'google-services.json' \
      -o -name '.env' \
      -o -name '.env.*' \
      -o -name '*.jks' \
      -o -name '*.keystore' \
      -o -name '*.p12' \
      -o -name '*.pfx' \
      -o -name '*.pem' \
      -o -name '*.key' \
      -o -name '*.apk' \
      -o -name '*.aab' \
      -o -name '*.aar' \
    \) \
    -print -quit
)"

if [[ -n "$unsafe_file" ]]; then
  echo "Public source tree contains local, signing, credential, or built artifact: $unsafe_file" >&2
  exit 1
fi

repository_has_git=false
if git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
  repository_has_git=true
  tracked_local_properties="$(
    git ls-files |
      grep -E '(^|/)local\.properties$' ||
      true
  )"
  if [[ -n "$tracked_local_properties" ]]; then
    echo "$tracked_local_properties"
    echo "Tracked local.properties must not be published" >&2
    exit 1
  fi
fi

local_properties_files="$(
  find . -type f \
    -not -path './.git/*' \
    -not -path '*/.gradle/*' \
    -not -path '*/build/*' \
    -name 'local.properties' \
    -print
)"

if [[ -n "$local_properties_files" ]]; then
  while IFS= read -r local_file; do
    relative_path="${local_file#./}"

    if [[ "$repository_has_git" == true ]]; then
      if ! git check-ignore -q -- "$relative_path"; then
        echo "Local local.properties must be ignored by Git: $local_file" >&2
        exit 1
      fi
    elif [[ ! -f .gitignore ]] || ! grep -F -x -q 'local.properties' .gitignore; then
      echo "Local local.properties must be covered by .gitignore: $local_file" >&2
      exit 1
    fi
  done <<< "$local_properties_files"
fi

secret_pattern='-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[0-9A-Za-z]{30,}|github_pat_[0-9A-Za-z_]{20,}|xox[baprs]-[0-9A-Za-z-]{20,}|sk_live_[0-9A-Za-z]{16,}|Bearer[[:space:]]+[0-9A-Za-z._~-]{20,}|ff14risingstones=[0-9A-Za-z._%+-]{16,}|https?://[^/:[:space:]]+:[^/@[:space:]]+@'

secret_matches=""
if secret_matches="$(
  grep -R -n -E \
    --binary-files=without-match \
    --exclude-dir=.git \
    --exclude-dir=.gradle \
    --exclude-dir=build \
    --exclude=local.properties \
    --exclude=verify-public-source-safety.sh \
    -e "$secret_pattern" \
    .
)"; then
  echo "$secret_matches"
  echo "Public source tree contains a value matching a credential or private-key pattern" >&2
  exit 1
else
  grep_status=$?
  if [[ $grep_status -ne 1 ]]; then
    echo "Credential-pattern scan failed with grep status $grep_status" >&2
    exit "$grep_status"
  fi
fi

production_roots=()
for source_root in ./*/src/main; do
  if [[ -d "$source_root" ]]; then
    production_roots+=("$source_root")
  fi
done

if [[ ${#production_roots[@]} -eq 0 ]]; then
  echo "No production source roots found" >&2
  exit 1
fi

allowed_url_pattern='^https://(ff14risingstones\.web\.sdo\.com|apiff14risingstones\.web\.sdo\.com|ff14risingstones\.gcloud\.com\.cn|static\.web\.sdo\.com|ff14-eo\.web\.sdo\.com|www\.bilibili\.com)(/|$)|^http://schemas\.android\.com(/|$)|^https://%1\$s$'
all_urls=""
if all_urls="$(
  grep -R -h -o -E \
    --include='*.kt' \
    --include='*.xml' \
    'https?://[^"[:space:]<>)]+' \
    "${production_roots[@]}"
)"; then
  :
else
  grep_status=$?
  if [[ $grep_status -ne 1 ]]; then
    echo "Production URL scan failed with grep status $grep_status" >&2
    exit "$grep_status"
  fi
fi

unexpected_urls="$(
  echo "$all_urls" |
    LC_ALL=C sort -u |
    grep -v -E "$allowed_url_pattern" ||
    true
)"

if [[ -n "$unexpected_urls" ]]; then
  echo "Production source contains hard-coded URL hosts outside the reviewed public allowlist:" >&2
  echo "$unexpected_urls" >&2
  exit 1
fi

echo "Public source safety checks passed for credential artifacts, secret patterns, and hard-coded hosts"
