plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.meallogger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.meallogger"
        minSdk = 27
        targetSdk = 34
        versionCode = 86
        versionName = "4.4.2"

        // API Base URL configuration (now using UserPreferences for all URLs)
        buildConfigField("String", "API_BASE_URL", "\"http://192.168.3.8:8000\"")
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

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // THINKLET SDK for multi-mic recording
    implementation(thinkletLibs.sdk.audio)

    // Vosk offline speech recognition
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Xfe
    implementation(project(":thinklet-xfe"))
}
