plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bardquotahook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bardquotahook"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.6.0"
    }

    signingConfigs {
        create("fixedRelease") {
            val ksFile = file("signing.p12")
            if (ksFile.exists()) {
                storeFile = ksFile
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    packaging {
        dex {
            useLegacyPackaging = false
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
            val ksFile = file("signing.p12")
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("fixedRelease")
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
    // Legacy Xposed Bridge API (82 is the latest version in https://api.xposed.info/)
    compileOnly("de.robv.android.xposed:api:82")
}
