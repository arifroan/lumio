plugins {
    id("com.android.application")
}

android {
    namespace = "com.lumio.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumio.player"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "0.1-draft"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
}
