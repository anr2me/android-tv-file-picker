plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The CI workflow sets these values. Local builds use the defaults.
val ciVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val ciVersionName = (findProperty("versionName") as String?) ?: "1.0.0"

// The CI workflow sets KEYSTORE_PATH when a signing key is available.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.example.androidtvfilepicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.androidtvfilepicker"
        minSdk = 30 // Android 11
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without a release key, use the debug key. The APK can then be installed.
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
