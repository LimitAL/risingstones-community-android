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
include(":dynamic-domain")
include(":dynamic-data")
include(":dynamic-presentation")
include(":dynamic-ui-compose")
include(":message-domain")
include(":message-data")
include(":message-presentation")
include(":message-ui-compose")
include(":profile-domain")
include(":profile-data")
include(":profile-presentation")
include(":profile-ui-compose")
include(":guild-domain")
include(":guild-data")
include(":guild-presentation")
include(":guild-ui-compose")
