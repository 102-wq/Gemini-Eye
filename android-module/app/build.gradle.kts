plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bardquotahook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bardquotahook"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "2.1.0"
    }

    val keystoreCandidate = if (file("release.jks").exists()) {
        file("release.jks")
    } else if (file("release.p12").exists()) {
        file("release.p12")
    } else {
        file("release.jks")
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreCandidate
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    packaging {
        dex {
            useLegacyPackaging = false
        }
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = if (keystoreCandidate.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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

dependencies {
    // Modern Xposed / LibXposed API 102 (Maven Central)
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("androidx.annotation:annotation:1.8.0")
    // Legacy Xposed Bridge API (82 is the latest version in https://api.xposed.info/)
    compileOnly("de.robv.android.xposed:api:82")
}
