plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val consumerSurface = providers.gradleProperty("risingStonesConsumerSurface")
    .getOrElse("aggregate")
val consumesComposeApi = consumerSurface.endsWith("ui-compose")
if (consumesComposeApi) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
}
val surfaceArtifacts = mapOf(
    "core" to listOf("core"),
    "network" to listOf("network"),
    "auth-webview" to listOf("auth-webview"),
    "account-data" to listOf("account-data"),
    "account-presentation" to listOf("account-presentation"),
    "account-ui-compose" to listOf("account-ui-compose"),
    "forum-domain" to listOf("forum-domain"),
    "dynamic-data" to listOf("dynamic-data"),
    "message-data" to listOf("message-data"),
    "profile-data" to listOf("profile-data"),
    "guild-data" to listOf("guild-data"),
    "glamour-data" to listOf("glamour-data"),
    "recruitment-data" to listOf("recruitment-data"),
    "personal-data-data" to listOf("personal-data-data"),
    "ui-compose" to listOf("ui-compose"),
    "forum-ui-compose" to listOf("forum-ui-compose"),
    "dynamic-ui-compose" to listOf("dynamic-ui-compose"),
    "message-ui-compose" to listOf("message-ui-compose"),
    "recruitment-ui-compose" to listOf("recruitment-ui-compose"),
    "glamour-ui-compose" to listOf("glamour-ui-compose"),
    "personal-data-ui-compose" to listOf("personal-data-ui-compose"),
    "guild-ui-compose" to listOf("guild-ui-compose"),
    "aggregate" to listOf(
        "auth-webview",
        "forum-data",
        "forum-presentation",
        "recruitment-ui-compose",
        "dynamic-ui-compose",
        "message-ui-compose",
        "profile-ui-compose",
        "glamour-ui-compose",
        "personal-data-ui-compose",
        "guild-ui-compose",
    ),
)
val selectedArtifacts = requireNotNull(surfaceArtifacts[consumerSurface]) {
    "Unknown risingStonesConsumerSurface: $consumerSurface"
}

android {
    namespace = "top.cxmeow.risingstones.integration.mavenconsumer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = consumesComposeApi
    }

    sourceSets {
        getByName("main") {
            val selectedSourceDirectories =
                if (consumerSurface == "aggregate") {
                    listOf("src/main/java")
                } else {
                    listOf("src/api-boundaries/$consumerSurface/java")
                }
            java.directories.clear()
            java.directories.addAll(selectedSourceDirectories)
            kotlin.directories.clear()
            kotlin.directories.addAll(selectedSourceDirectories)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val risingStonesVersion = providers.gradleProperty("risingStonesVersion")
    .getOrElse("0.1.0-SNAPSHOT")

dependencies {
    selectedArtifacts.forEach { artifact ->
        implementation("top.cxmeow.risingstones:$artifact:$risingStonesVersion")
    }
}
