#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

locales=(
  values-zh-rCN
  values-zh-rTW
)

extract_keys() {
  sed -nE 's/.*<string[[:space:]]+name="([^"]+)".*/\1/p' "$1" |
    LC_ALL=C sort
}

extract_placeholders() {
  local file="$1"
  local key="$2"
  sed -nE \
    "s#.*<string[[:space:]]+name=\"${key}\"[^>]*>(.*)</string>.*#\\1#p" \
    "$file" |
    { grep -oE '%[0-9]+\$([.][0-9]+)?[a-zA-Z]' || true; } |
    LC_ALL=C sort
}

module_count=0
string_count=0

while IFS= read -r default_file; do
  module_count=$((module_count + 1))
  module_dir="${default_file%/src/main/res/values/strings.xml}"
  default_keys="$(extract_keys "$default_file")"
  duplicate_keys="$(
    extract_keys "$default_file" |
      uniq -d
  )"
  if [[ -n "$duplicate_keys" ]]; then
    echo "Duplicate default string keys in $default_file:" >&2
    echo "$duplicate_keys" >&2
    exit 1
  fi
  string_count=$((string_count + $(printf '%s\n' "$default_keys" | sed '/^$/d' | wc -l)))

  for locale in "${locales[@]}"; do
    translated_file="$module_dir/src/main/res/$locale/strings.xml"
    if [[ ! -f "$translated_file" ]]; then
      echo "Missing $locale strings for module $module_dir" >&2
      exit 1
    fi

    translated_keys="$(extract_keys "$translated_file")"
    duplicate_keys="$(
      extract_keys "$translated_file" |
        uniq -d
    )"
    if [[ -n "$duplicate_keys" ]]; then
      echo "Duplicate translated string keys in $translated_file:" >&2
      echo "$duplicate_keys" >&2
      exit 1
    fi
    if [[ "$default_keys" != "$translated_keys" ]]; then
      echo "String keys differ between $default_file and $translated_file" >&2
      diff \
        <(printf '%s\n' "$default_keys") \
        <(printf '%s\n' "$translated_keys") >&2 || true
      exit 1
    fi

    while IFS= read -r key; do
      [[ -n "$key" ]] || continue
      default_placeholders="$(extract_placeholders "$default_file" "$key")"
      translated_placeholders="$(extract_placeholders "$translated_file" "$key")"
      if [[ "$default_placeholders" != "$translated_placeholders" ]]; then
        echo "Format placeholders differ for $key in $translated_file" >&2
        echo "Default: ${default_placeholders:-<none>}" >&2
        echo "Translated: ${translated_placeholders:-<none>}" >&2
        exit 1
      fi
    done <<< "$default_keys"
  done
done < <(
  find . \
    -path '*/src/main/res/values/strings.xml' \
    -not -path './build/*' \
    -print |
    LC_ALL=C sort
)

if [[ "$module_count" -eq 0 ]]; then
  echo "No default Android string resources found" >&2
  exit 1
fi

echo "String localization parity verified for $string_count strings in $module_count modules"
