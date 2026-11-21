plugins {
    id("com.android.application")
}

// The release key comes from the environment, so it never sits in the repository. Without it a
// release build comes out unsigned.
val keystoreFile: String? = System.getenv("MICROQR_KEYSTORE_FILE")

val appVersion = "0.1.0"

android {
    namespace = "com.synaelle.microqr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.synaelle.microqr"
        minSdk = 21
        targetSdk = 37
        versionName = appVersion
        // Android only updates to a higher code: 1.2.3 is 10203
        versionCode = appVersion.split(".").fold(0) { code, part -> code * 100 + part.toInt() }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("MICROQR_KEY_PASSWORD")
                keyAlias = System.getenv("MICROQR_KEY_ALIAS")
                keyPassword = System.getenv("MICROQR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.4")
}
