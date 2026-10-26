plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val lumioKeystorePath = System.getenv("LUMIO_KEYSTORE_PATH")
val lumioStorePassword = System.getenv("LUMIO_KEYSTORE_PASSWORD")
val lumioKeyAlias = System.getenv("LUMIO_KEY_ALIAS")
val lumioKeyPassword = System.getenv("LUMIO_KEY_PASSWORD")
val usePersistentLumioSigning = listOf(
    lumioKeystorePath,
    lumioStorePassword,
    lumioKeyAlias,
    lumioKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.lumio.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumio.player"
        minSdk = 33
        targetSdk = 35
        versionCode = 22
        versionName = "0.2.20"
    }

    signingConfigs {
        if (usePersistentLumioSigning) {
            create("lumioPreview") {
                storeFile = file(lumioKeystorePath!!)
                storePassword = lumioStorePassword
                keyAlias = lumioKeyAlias
                keyPassword = lumioKeyPassword
                storeType = "JKS"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Keep the preview's application ID and signing identity, but shrink code/resources
            // so the downloadable preview reflects realistic APK-size optimization.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            if (usePersistentLumioSigning) {
                signingConfig = signingConfigs.getByName("lumioPreview")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
}
