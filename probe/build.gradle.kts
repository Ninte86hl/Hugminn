plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ninte86hl.hugminn.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ninte86hl.hugminn.probe"
        minSdk = 30
        targetSdk = 34
        versionCode = 10
        versionName = "0.4.4"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
