#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
cd "$root"

require_api_dependency() {
  local module="$1"
  local dependency="$2"
  local build_file="$module/build.gradle.kts"

  if [[ ! -f "$build_file" ]]; then
    echo "Missing public module build file: $build_file" >&2
    exit 1
  fi
  if ! grep -Fq "api($dependency)" "$build_file"; then
    echo "$module publicly exposes $dependency but does not declare it with api(...)" >&2
    exit 1
  fi
}

# Public session state includes StateFlow.
require_api_dependency core "libs.kotlinx.coroutines.core"

# Public network constructors accept Json and OkHttpClient.
require_api_dependency network 'project(":core")'
require_api_dependency network "libs.kotlinx.serialization.json"
require_api_dependency network "platform(libs.okhttp.bom)"
require_api_dependency network "libs.okhttp"

# The WebView provider constructor accepts the public network validator contract.
require_api_dependency auth-webview 'project(":core")'
require_api_dependency auth-webview 'project(":network")'

# Forum domain contracts expose StateFlow in their identity-conflict state.
require_api_dependency forum-domain 'project(":core")'
require_api_dependency forum-domain "libs.kotlinx.coroutines.core"

features=(account forum glamour personal-data recruitment)
for feature in "${features[@]}"; do
  data_module="$feature-data"
  presentation_module="$feature-presentation"
  ui_module="$feature-ui-compose"

  # Public service constructors accept core session, network client, and Json types.
  require_api_dependency "$data_module" "project(\":$feature-domain\")"
  require_api_dependency "$data_module" 'project(":core")'
  require_api_dependency "$data_module" 'project(":network")'
  require_api_dependency "$data_module" "libs.kotlinx.serialization.json"

  # Public ViewModels and state properties expose Lifecycle and StateFlow types.
  require_api_dependency "$presentation_module" "project(\":$feature-domain\")"
  require_api_dependency "$presentation_module" "libs.androidx.lifecycle.viewmodel.ktx"
  require_api_dependency "$presentation_module" "libs.kotlinx.coroutines.core"

  # Public reference-screen functions expose the feature contracts and Compose Modifier.
  require_api_dependency "$ui_module" "project(\":$feature-domain\")"
  require_api_dependency "$ui_module" "project(\":$feature-presentation\")"
  require_api_dependency "$ui_module" "platform(libs.androidx.compose.bom)"
  require_api_dependency "$ui_module" "libs.androidx.compose.ui"
done

# The optional login screen exposes the WebView session provider and Compose Modifier.
require_api_dependency ui-compose 'project(":core")'
require_api_dependency ui-compose 'project(":auth-webview")'
require_api_dependency ui-compose "platform(libs.androidx.compose.bom)"
require_api_dependency ui-compose "libs.androidx.compose.ui"

echo "Public API dependency declarations verified for 24 Maven library modules"
