plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ninte86hl.hugminn"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ninte86hl.hugminn"
        minSdk = 30
        targetSdk = 34
        versionCode = 89
        versionName = "0.12.8"
    }

    // Fixed key for test builds so new versions install over old ones. Not a release key.
    signingConfigs {
        create("test") {
            storeFile = file("signing/hugminn-test.jks")
            storePassword = "hugminn-test"
            keyAlias = "hugminn"
            keyPassword = "hugminn-test"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
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
