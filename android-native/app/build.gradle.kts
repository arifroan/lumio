plugins {
    id("com.android.application")
}

android {
    namespace = "com.arifroan.lumio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arifroan.lumio"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
}
