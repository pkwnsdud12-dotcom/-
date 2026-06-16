plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.notexs.iphonexscam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notexs.iphonexscam"
        // Galaxy Note 20 Ultra (One UI / Android 11+) 대응. 최소 API 30 요구사항 준수.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // OpenGL ES 3.0 사용을 위한 NDK ABI 필터(셰이더 자체는 Java/Kotlin GLES API 사용이므로 NDK 불필요)
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    // 셰이더(.glsl) 및 LUT(.cube) 파일은 assets 폴더에 위치하므로 압축 제외 설정 불필요(assets는 기본적으로 raw 접근 가능)
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // CameraX - 실시간 프리뷰 및 캡처 (ISP 억제는 Camera2Interop으로 처리)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // AndroidX 기본
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ListenableFuture 콜백을 위한 guava (CameraX ProcessCameraProvider)
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
}
