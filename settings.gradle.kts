pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "risingstones-community-android"
include(":app")
include(":account-domain")
include(":account-data")
include(":account-presentation")
include(":account-ui-compose")
include(":core")
include(":forum-domain")
include(":forum-data")
include(":forum-presentation")
include(":forum-ui-compose")
include(":glamour-domain")
include(":glamour-data")
include(":glamour-presentation")
include(":glamour-ui-compose")
include(":network")
include(":personal-data-domain")
include(":personal-data-data")
include(":personal-data-presentation")
include(":personal-data-ui-compose")
include(":recruitment-domain")
include(":recruitment-data")
include(":recruitment-presentation")
include(":recruitment-ui-compose")
include(":auth-webview")
include(":ui-compose")
