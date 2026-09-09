plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "top.cxmeow.risingstones.feature.forum.domain"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
