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
            // The SMB library (smbj) and its dependencies use reflection.
            // The build does not shrink the code, so the release build is safe.
            isMinifyEnabled = false
            isShrinkResources = false
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

    packaging {
        resources {
            // Libraries for the network shares have files with the same path.
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Network shares
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
