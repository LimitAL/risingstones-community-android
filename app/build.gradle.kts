plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = providers.gradleProperty("risingStonesAppVersionName")
    .orElse(providers.gradleProperty("risingStonesVersion"))
    .orElse("0.1.0-dev")
val appVersionCode = providers.gradleProperty("risingStonesAppVersionCode")
    .orElse("1")
val releaseStoreFile = providers.environmentVariable("RISINGSTONES_RELEASE_STORE_FILE").orNull
val releaseStorePassword =
    providers.environmentVariable("RISINGSTONES_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RISINGSTONES_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword =
    providers.environmentVariable("RISINGSTONES_RELEASE_KEY_PASSWORD").orNull
val releaseStoreType = providers.environmentVariable("RISINGSTONES_RELEASE_STORE_TYPE")
    .orElse("PKCS12")
val releaseSigningInputs = mapOf(
    "RISINGSTONES_RELEASE_STORE_FILE" to releaseStoreFile,
    "RISINGSTONES_RELEASE_STORE_PASSWORD" to releaseStorePassword,
    "RISINGSTONES_RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "RISINGSTONES_RELEASE_KEY_PASSWORD" to releaseKeyPassword,
)
val configuredReleaseSigningInputs = releaseSigningInputs.filterValues { !it.isNullOrBlank() }
require(
    configuredReleaseSigningInputs.isEmpty() ||
        configuredReleaseSigningInputs.size == releaseSigningInputs.size,
) {
    val missingNames = releaseSigningInputs
        .filterValues { it.isNullOrBlank() }
        .keys
        .joinToString()
    "Release signing is only partially configured. Missing: $missingNames"
}
val hasReleaseSigning = configuredReleaseSigningInputs.size == releaseSigningInputs.size

android {
    namespace = "top.cxmeow.risingstones.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "top.cxmeow.risingstones"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.get().toIntOrNull()?.takeIf { it > 0 }
            ?: error("risingStonesAppVersionCode must be a positive integer")
        versionName = appVersionName.get().trim().takeIf(String::isNotEmpty)
            ?: error("risingStonesAppVersionName must not be blank")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                storeType = releaseStoreType.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":account-data"))
    implementation(project(":account-presentation"))
    implementation(project(":account-ui-compose"))
    implementation(project(":core"))
    implementation(project(":network"))
    implementation(project(":auth-webview"))
    implementation(project(":ui-compose"))
    implementation(project(":forum-domain"))
    implementation(project(":forum-data"))
    implementation(project(":forum-presentation"))
    implementation(project(":forum-ui-compose"))
    implementation(project(":glamour-data"))
    implementation(project(":glamour-ui-compose"))
    implementation(project(":personal-data-data"))
    implementation(project(":personal-data-ui-compose"))
    implementation(project(":recruitment-data"))
    implementation(project(":recruitment-ui-compose"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
