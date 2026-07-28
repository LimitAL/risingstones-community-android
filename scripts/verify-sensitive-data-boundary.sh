#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

manifest="app/src/main/AndroidManifest.xml"
extraction_rules="app/src/main/res/xml/data_extraction_rules.xml"
legacy_rules="app/src/main/res/xml/backup_rules.xml"

required_manifest_attributes=(
  'android:allowBackup="false"'
  'android:dataExtractionRules="@xml/data_extraction_rules"'
  'android:fullBackupContent="@xml/backup_rules"'
  'android:usesCleartextTraffic="false"'
)
for attribute in "${required_manifest_attributes[@]}"; do
  if ! grep -Fq "$attribute" "$manifest"; then
    echo "Sensitive-data manifest policy is missing: $attribute" >&2
    exit 1
  fi
done

domains=(
  root
  file
  database
  sharedpref
  external
  device_root
  device_file
  device_database
  device_sharedpref
)
for domain in "${domains[@]}"; do
  expected='<exclude domain="'"$domain"'" path="." />'
  if [[ "$(grep -Fc "$expected" "$extraction_rules")" -ne 2 ]]; then
    echo "Cloud and device-transfer rules must both exclude domain: $domain" >&2
    exit 1
  fi
  if [[ "$(grep -Fc "$expected" "$legacy_rules")" -ne 1 ]]; then
    echo "Legacy backup rules must exclude domain: $domain" >&2
    exit 1
  fi
done

cookie_store_source="auth-webview/src/main/java/top/cxmeow/risingstones/auth/webview/RisingStonesCookieStore.kt"
if ! grep -Fq "noBackupFilesDir" "$cookie_store_source" ||
  ! grep -Fq "AtomicFile(" "$cookie_store_source"; then
  echo "The reusable WebView credential store must use atomic no-backup storage" >&2
  exit 1
fi
if rg -n 'putString\([[:space:]]*(CookieKey|UserAgentKey)' "$cookie_store_source"; then
    echo "WebView credentials must not be persisted in ordinary SharedPreferences" >&2
    exit 1
fi

login_ui_source="ui-compose/src/main/java/top/cxmeow/risingstones/ui/compose/RisingStonesWebLoginScreen.kt"
if ! grep -Fq "WindowManager.LayoutParams.FLAG_SECURE" "$login_ui_source"; then
  echo "The default WebView login UI must protect screenshots and task snapshots" >&2
  exit 1
fi

if rg -n \
  '(^|[^A-Za-z])(Log\.[A-Za-z]+|println|printStackTrace)[[:space:]]*\(' \
  auth-webview/src/main \
  network/src/main \
  -g '*.kt'; then
  echo "Sensitive authentication or network code must not write process logs" >&2
  exit 1
fi

exported_components="$(
  rg -n 'android:exported="true"' \
    --glob 'AndroidManifest.xml' \
    --glob '!**/build/**' |
    wc -l |
    tr -d ' '
)"
if [[ "$exported_components" != "1" ]]; then
  echo "Expected only the standalone launcher activity to be exported" >&2
  exit 1
fi

echo "Sensitive credential storage, backup, cleartext, logging, and export policies verified"
