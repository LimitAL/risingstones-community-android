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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("../../build/repository")
            content {
                includeGroup("top.cxmeow.risingstones")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "risingstones-maven-consumer-smoke"
include(":consumer")
