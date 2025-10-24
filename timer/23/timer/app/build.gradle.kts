plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // alias(libs.plugins.kotlin.compose) // View 기반이므로 제거
}

android {
    namespace = "com.krdonon.timer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.krdonon.timer"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "23.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 🔹 출시용 빌드 최적화
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 🔹 디버그 정보 제거 (선택사항)
            // isDebuggable = false
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        // Kotlin과 Java의 호환성 설정을 일치시킵니다.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        // compose = true // View 기반이므로 제거
        buildConfig = true  // 🔹 BuildConfig 생성 활성화 (필요 시)
    }

    // 🔹 패키징 옵션 (중복 파일 제거)
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    // 🔹 Lint 옵션 (경고 무시 설정)
    lint {
        // 치명적 오류를 경고로 변경
        abortOnError = false
        // 특정 경고 무시
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }
}

dependencies {

    // 1. 핵심 Android & Kotlin 확장
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 2. === 필수: AppCompatActivity 및 Fragment 지원 ===
    // AppCompatActivity (MainActivity가 상속받는 클래스)를 위해 필요합니다.
    implementation("androidx.appcompat:appcompat:1.7.1")

    // androidx.fragment.app.Fragment를 위해 필요합니다.
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // XML 레이아웃에서 ConstraintLayout을 사용하기 위해 필요합니다.
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // === Material Design 컴포넌트 ===
    implementation("com.google.android.material:material:1.13.0")

    // === ViewModel & LiveData ===
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // === Media (알람/사운드) ===
    implementation("androidx.media:media:1.7.1")

    // 🔹 Android 14+ (API 34+) 대응을 위한 추가 의존성
    // ServiceCompat 등을 위해 최신 core 라이브러리 사용
    implementation("androidx.core:core:1.15.0")

    // 3. 테스트 관련 종속성
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}