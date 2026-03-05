plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

import java.util.Properties

val localProperties = Properties().also { props: Properties ->
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) props.load(localFile.inputStream())
}

android {
    namespace = "ai.fd.thinklet.app.outing.advisor"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.fd.thinklet.app.outing.advisor"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // API Base URL configuration (now using UserPreferences for all URLs)
        buildConfigField("String", "API_BASE_URL", "\"http://192.168.3.8:8000\"")
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"${localProperties["openweather.api.key"] ?: ""}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties["gemini.api.key"] ?: ""}\"")
        buildConfigField("String", "CLOUD_TTS_API_KEY", "\"${localProperties["cloud.tts.api.key"] ?: ""}\"")

    }

    buildTypes {
        debug {
            // Development/Debug用のURL（Androidエミュレータからlocalhostにアクセス）
            buildConfigField("String", "API_BASE_URL", "\"http://192.168.3.8:8000\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Production用のURL（本番環境のサーバーURL）
            buildConfigField("String", "API_BASE_URL", "\"http://192.168.3.27:8000\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // Retrofit for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.runtime:runtime-livedata")

    // ML Kit
    implementation("com.google.mlkit:image-labeling:17.0.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Vosk offline speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Xfe
    implementation(project(":thinklet-xfe"))

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
}
