plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blenderremote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blenderremote.app"
        minSdk = 24            // ARCore requires API 24+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.ar:core:1.42.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.zeromq:jeromq:0.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
