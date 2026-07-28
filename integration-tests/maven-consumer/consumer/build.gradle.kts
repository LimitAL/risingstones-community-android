plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val consumerSurface = providers.gradleProperty("risingStonesConsumerSurface")
    .getOrElse("aggregate")
val consumesComposeApi = consumerSurface == "ui-compose"
if (consumesComposeApi) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
}
val surfaceArtifacts = mapOf(
    "core" to listOf("core"),
    "network" to listOf("network"),
    "auth-webview" to listOf("auth-webview"),
    "account-data" to listOf("account-data"),
    "account-presentation" to listOf("account-presentation"),
    "forum-domain" to listOf("forum-domain"),
    "ui-compose" to listOf("ui-compose"),
    "aggregate" to listOf(
        "auth-webview",
        "forum-data",
        "glamour-ui-compose",
        "personal-data-ui-compose",
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val risingStonesVersion = providers.gradleProperty("risingStonesVersion")
    .getOrElse("0.1.0-SNAPSHOT")

dependencies {
    selectedArtifacts.forEach { artifact ->
        implementation("top.cxmeow.risingstones:$artifact:$risingStonesVersion")
    }
}
