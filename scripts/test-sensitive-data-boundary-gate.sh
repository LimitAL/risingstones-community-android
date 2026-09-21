#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/risingstones-sensitive-boundary-test.XXXXXX")"

cleanup() {
  if [[ -d "$fixture_root" && "$fixture_root" == *'/risingstones-sensitive-boundary-test.'* ]]; then
    rm -rf -- "$fixture_root"
  fi
}
trap cleanup EXIT

write_fixture() {
  mkdir -p \
    "$fixture_root/app/src/main/java/top/cxmeow/risingstones/app" \
    "$fixture_root/app/src/main/res/xml" \
    "$fixture_root/auth-webview/src/main/java/top/cxmeow/risingstones/auth/webview" \
    "$fixture_root/ui-compose/src/main/java/top/cxmeow/risingstones/ui/compose"
  cp "$project_root/app/src/main/AndroidManifest.xml" "$fixture_root/app/src/main/AndroidManifest.xml"
  cp "$project_root/app/src/main/res/xml/backup_rules.xml" "$fixture_root/app/src/main/res/xml/backup_rules.xml"
  cp "$project_root/app/src/main/res/xml/data_extraction_rules.xml" "$fixture_root/app/src/main/res/xml/data_extraction_rules.xml"
  cp "$project_root/app/src/main/res/xml/share_paths.xml" "$fixture_root/app/src/main/res/xml/share_paths.xml"
  cp "$project_root/app/src/main/java/top/cxmeow/risingstones/app/PngShareController.kt" \
    "$fixture_root/app/src/main/java/top/cxmeow/risingstones/app/PngShareController.kt"
  cp "$project_root/auth-webview/src/main/java/top/cxmeow/risingstones/auth/webview/RisingStonesCookieStore.kt" \
    "$fixture_root/auth-webview/src/main/java/top/cxmeow/risingstones/auth/webview/RisingStonesCookieStore.kt"
  cp "$project_root/ui-compose/src/main/java/top/cxmeow/risingstones/ui/compose/RisingStonesWebLoginScreen.kt" \
    "$fixture_root/ui-compose/src/main/java/top/cxmeow/risingstones/ui/compose/RisingStonesWebLoginScreen.kt"
}

expect_failure() {
  local label="$1"
  local expected_message="$2"
  local output=""
  if output="$(bash "$script_dir/verify-sensitive-data-boundary.sh" "$fixture_root" 2>&1)"; then
    echo "Sensitive-data verifier unexpectedly accepted fixture: $label" >&2
    exit 1
  fi
  if ! grep -Fq "$expected_message" <<< "$output"; then
    echo "Sensitive-data verifier rejected $label for the wrong reason:" >&2
    echo "$output" >&2
    exit 1
  fi
}

write_fixture
bash "$script_dir/verify-sensitive-data-boundary.sh" "$fixture_root" > /dev/null

perl -0pi -e 's/android:exported="false"/android:exported="true"/' \
  "$fixture_root/app/src/main/AndroidManifest.xml"
expect_failure "exported share provider" "single non-exported, URI-granting app provider"

write_fixture
perl -0pi -e 's/<cache-path/<files-path/' "$fixture_root/app/src/main/res/xml/share_paths.xml"
expect_failure "broad private-files provider path" "expose only the private cacheDir/share directory"

write_fixture
perl -0pi -e 's#<application#<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />\n    <application#' \
  "$fixture_root/app/src/main/AndroidManifest.xml"
expect_failure "media permission" "must not request storage or media permissions"

write_fixture
perl -0pi -e 's/Intent\.ACTION_SEND/Intent.ACTION_SEND_MULTIPLE/' \
  "$fixture_root/app/src/main/java/top/cxmeow/risingstones/app/PngShareController.kt"
expect_failure "multiple image share" "multiple, writable, external, or file:// URIs"

write_fixture
perl -0pi -e 's/applicationContext\.revokeUriPermission/\/\/ revoked intentionally removed\n        applicationContext.revokeMissingUriPermission/' \
  "$fixture_root/app/src/main/java/top/cxmeow/risingstones/app/PngShareController.kt"
expect_failure "missing URI revocation" "missing required private, read-only behavior: revokeUriPermission"

echo "Sensitive-data boundary verifier self-test passed"
