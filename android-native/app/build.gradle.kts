plugins {
    id("com.android.application")
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
        minSdk = 23
        targetSdk = 35
        versionCode = 17
        versionName = "0.2.15"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            if (usePersistentLumioSigning) {
                signingConfig = signingConfigs.getByName("lumioPreview")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}
