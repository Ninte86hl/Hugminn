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
        versionCode = 258
        versionName = "1.10.26-test"
    }

    // Release key: only on GitHub (secrets SIGNING_KEYSTORE_B64 / SIGNING_PASSWORD), never in the repository.
    // Local builds without it use the old test key.
    val releaseStore = System.getenv("HUGMINN_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        create("test") {
            storeFile = file("signing/hugminn-test.jks")
            storePassword = "hugminn-test"
            keyAlias = "hugminn"
            keyPassword = "hugminn-test"
        }
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = System.getenv("HUGMINN_KEY_PASSWORD")
            keyAlias = "hugminn"
            keyPassword = System.getenv("HUGMINN_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else signingConfigs.getByName("test")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
