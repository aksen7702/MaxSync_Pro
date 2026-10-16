plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maxsyncpro.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maxsyncpro.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        // 스토어 심사 없이 스마트폰에 즉시 설치(사이드로딩)할 수 있도록 디버그 서명키를 Release APK에 자동 연결
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    // FFmpegKit Android (EBU R128 측정 및 libmp3lame 인코딩 내장)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")

    // Jetpack Compose & Material 3
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
