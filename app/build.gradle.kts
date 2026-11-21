plugins {
    id("com.android.application")
}

android {
    namespace = "com.synaelle.microqr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.synaelle.microqr"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("release") {
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
