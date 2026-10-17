plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maxsyncpro.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maxsync.pro"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            // ffmpeg-kit-maintained 라이브러리가 배포하는 ABI와 일치시킴 (arm64-v8a, x86_64)
            // 실제 스마트폰(arm64) + 에뮬레이터(x86_64) 지원
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // FFmpegKit 네이티브(.so) 라이브러리 로딩 안정성을 위해 APK 설치 시 추출하도록 설정
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // 가장 가볍고 안정적인 공식 경량 오디오 엔진
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
}
