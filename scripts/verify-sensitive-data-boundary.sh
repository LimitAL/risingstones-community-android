#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

manifest="app/src/main/AndroidManifest.xml"
extraction_rules="app/src/main/res/xml/data_extraction_rules.xml"
legacy_rules="app/src/main/res/xml/backup_rules.xml"
share_paths="app/src/main/res/xml/share_paths.xml"
share_controller="app/src/main/java/top/cxmeow/risingstones/app/PngShareController.kt"

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

if [[ "$(grep -Fc '<provider' "$manifest")" -ne 1 ]] ||
  ! grep -Fq 'android:name=".PngShareFileProvider"' "$manifest" ||
  ! grep -Fq 'android:authorities="${applicationId}.share"' "$manifest" ||
  ! grep -Fq 'android:exported="false"' "$manifest" ||
  ! grep -Fq 'android:grantUriPermissions="true"' "$manifest" ||
  ! grep -Fq 'android:name="android.support.FILE_PROVIDER_PATHS"' "$manifest" ||
  ! grep -Fq 'android:resource="@xml/share_paths"' "$manifest"; then
  echo "The PNG share provider must be the single non-exported, URI-granting app provider" >&2
  exit 1
fi

if [[ "$(grep -Fc '<cache-path' "$share_paths")" -ne 1 ]] ||
  ! grep -Fq 'name="shared_png"' "$share_paths" ||
  ! grep -Fq 'path="share/"' "$share_paths" ||
  grep -n -E '<(root-path|files-path|external-path|external-files-path|external-cache-path|media-path)' "$share_paths"; then
  echo "PNG sharing must expose only the private cacheDir/share directory" >&2
  exit 1
fi

if grep -R -n -E \
  --include='AndroidManifest.xml' \
  --exclude-dir=build \
  'android\.permission\.(READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|READ_MEDIA_[A-Z_]+|MANAGE_EXTERNAL_STORAGE)' \
  .; then
  echo "PNG sharing must not request storage or media permissions" >&2
  exit 1
fi

required_share_controller_fragments=(
  'AtomicFile('
  'FileProvider.getUriForFile'
  'Intent.ACTION_SEND'
  'Intent.EXTRA_STREAM'
  'ClipData.newRawUri'
  'Intent.FLAG_GRANT_READ_URI_PERMISSION'
  'revokeUriPermission'
  'ShareDirectoryName = "share"'
  'mode != "r"'
  'clearRegisteredUrisForTesting'
)
for fragment in "${required_share_controller_fragments[@]}"; do
  if ! grep -Fq "$fragment" "$share_controller"; then
    echo "PNG share controller is missing required private, read-only behavior: $fragment" >&2
    exit 1
  fi
done
if grep -n -E 'ACTION_SEND_MULTIPLE|FLAG_GRANT_WRITE_URI_PERMISSION|externalCacheDir|externalFilesDir|filesDir|Uri\.fromFile' "$share_controller"; then
  echo "PNG sharing must not expose multiple, writable, external, or file:// URIs" >&2
  exit 1
fi

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
if grep -n -E 'putString\([[:space:]]*(CookieKey|UserAgentKey)' "$cookie_store_source"; then
    echo "WebView credentials must not be persisted in ordinary SharedPreferences" >&2
    exit 1
fi

login_ui_source="ui-compose/src/main/java/top/cxmeow/risingstones/ui/compose/RisingStonesWebLoginScreen.kt"
if ! grep -Fq "WindowManager.LayoutParams.FLAG_SECURE" "$login_ui_source"; then
  echo "The default WebView login UI must protect screenshots and task snapshots" >&2
  exit 1
fi

if grep -R -n -E \
  --include='*.kt' \
  '(^|[^A-Za-z])(Log\.[A-Za-z]+|println|printStackTrace)[[:space:]]*\(' \
  auth-webview/src/main \
  network/src/main; then
  echo "Sensitive authentication or network code must not write process logs" >&2
  exit 1
fi

exported_components="$(
  grep -R -n -F \
    --include='AndroidManifest.xml' \
    --exclude-dir=build \
    'android:exported="true"' \
    . ||
    true
)"
exported_component_count="$(
  printf '%s\n' "$exported_components" |
    sed '/^$/d' |
    wc -l |
    tr -d ' '
)"
if [[ "$exported_component_count" != "1" ]]; then
  if [[ -n "$exported_components" ]]; then
    echo "$exported_components"
  fi
  echo "Expected only the standalone launcher activity to be exported" >&2
  exit 1
fi

echo "Sensitive credential storage, backup, cleartext, logging, and export policies verified"
