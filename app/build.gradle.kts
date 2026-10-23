plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.plaintext.editor"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.plaintext.editor"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    testOptions {
        // The 10 MiB-document tests allocate a few hundred MB (several 20 MB buffers and snapshots).
        unitTests.all { it.maxHeapSize = "1536m" }
    }
}

dependencies {
    // No runtime dependencies besides the Kotlin standard library: no AndroidX, no Compose, no network libraries.
    testImplementation("junit:junit:4.13.2")
}
