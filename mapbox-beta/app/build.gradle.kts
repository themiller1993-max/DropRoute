plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paul.droproute.mapboxbeta"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.paul.droproute.mapboxbeta"
        minSdk = 23
        targetSdk = 35
        versionCode = 131
        versionName = "1.3.1-beta"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.mapbox.maps:android:11.27.0")
}
