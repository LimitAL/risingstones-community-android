#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/risingstones-source-safety-test.XXXXXX")"

cleanup() {
  if [[ -d "$fixture_root" && "$fixture_root" == *'/risingstones-source-safety-test.'* ]]; then
    rm -rf -- "$fixture_root"
  fi
}
trap cleanup EXIT

mkdir -p "$fixture_root/app/src/main/java/example"

write_safe_source() {
  printf '%s\n' \
    'package example' \
    'internal const val OfficialUrl = "https://ff14risingstones.web.sdo.com/"' \
    > "$fixture_root/app/src/main/java/example/Fixture.kt"
}

expect_failure() {
  label="$1"
  expected_message="$2"
  output=""

  if output="$(bash "$script_dir/verify-public-source-safety.sh" "$fixture_root" 2>&1)"; then
    echo "Source-safety verifier unexpectedly accepted fixture: $label" >&2
    exit 1
  fi

  if ! grep -q "$expected_message" <<< "$output"; then
    echo "Source-safety verifier rejected $label for the wrong reason:" >&2
    echo "$output" >&2
    exit 1
  fi
}

write_safe_source
bash "$script_dir/verify-public-source-safety.sh" "$fixture_root" > /dev/null

printf '%s\n' 'local.properties' > "$fixture_root/.gitignore"
printf '%s\n' 'sdk.dir=/tmp/android-sdk' > "$fixture_root/local.properties"
bash "$script_dir/verify-public-source-safety.sh" "$fixture_root" > /dev/null

printf '%s\n' '# local.properties is intentionally not ignored' > "$fixture_root/.gitignore"
expect_failure "unignored local Android SDK configuration" "must be covered by .gitignore"

printf '%s\n' 'local.properties' > "$fixture_root/.gitignore"
git -C "$fixture_root" init -q
git -C "$fixture_root" add -f local.properties
expect_failure "tracked local Android SDK configuration" "Tracked local.properties must not be published"
git -C "$fixture_root" rm --cached -q local.properties
rm -f "$fixture_root/local.properties"

printf '%s\n' \
  'package example' \
  'internal const val LeakedCredential = "AKIA'"ABCDEFGHIJKLMNOP"'"' \
  > "$fixture_root/app/src/main/java/example/Fixture.kt"
expect_failure "recognized credential" "credential or private-key pattern"

printf '%s\n' \
  'package example' \
  'internal const val PrivateHost = "https://private.example/api"' \
  > "$fixture_root/app/src/main/java/example/Fixture.kt"
expect_failure "unreviewed hard-coded host" "outside the reviewed public allowlist"

for lookalike_host in 'https://ff14-eo.web.sdo.com.evil.example/image.png' 'https://ff14-eo.web.sdo.com@evil.example/image.png' 'http://ff14-eo.web.sdo.com/image.png'; do
  printf '%s\n' "internal const val UnreviewedIcon = \"$lookalike_host\"" > "$fixture_root/app/src/main/java/example/Fixture.kt"
  expect_failure "unreviewed icon host variant" "outside the reviewed public allowlist"
done

write_safe_source
touch "$fixture_root/release.jks"
expect_failure "signing artifact" "local, signing, credential, or built artifact"

echo "Public source safety verifier self-test passed"
