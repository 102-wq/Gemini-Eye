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

    signingConfigs {
        create("release") {
            val jks = file("release.jks")
            val p12 = file("signing.p12")
            if (jks.exists()) {
                storeFile = jks
                storePassword = "android"
                keyAlias = "release"
                keyPassword = "android"
            } else if (p12.exists()) {
                storeFile = p12
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
            val releaseKeystore = file("release.jks")
            val p12Keystore = file("signing.p12")
            if (releaseKeystore.exists() || p12Keystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
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
