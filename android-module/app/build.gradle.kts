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
        versionCode = 6
        versionName = "1.5.0"
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
    // 核心：Xposed 必须使用 compileOnly
    compileOnly("de.robv.android.xposed:api:82")
}
