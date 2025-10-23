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
        versionCode = 19
        versionName = "19.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Kotlin과 Java의 호환성 설정을 일치시킵니다.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        // compose = true // View 기반이므로 제거
        // Data Binding, View Binding 등을 필요에 따라 추가할 수 있습니다.
    }
}

dependencies {

    // 1. 핵심 Android & Kotlin 확장
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 2. === 필수 추가: AppCompatActivity 및 Fragment 지원 ===

    // AppCompatActivity (MainActivity가 상속받는 클래스)를 위해 필요합니다.
    implementation("androidx.appcompat:appcompat:1.7.1")

    // androidx.fragment.app.Fragment를 위해 필요합니다. (이전 오류의 핵심 수정사항)
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // XML 레이아웃에서 ConstraintLayout을 사용하기 위해 필요합니다.
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // === 새로 추가된 부분: MaterialButton과 최신 디자인을 위해 필수 ===
    implementation("com.google.android.material:material:1.13.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.media:media:1.7.1")
// 또는 최신

    // 3. 기존 Compose 관련 의존성 모두 제거
    // libs.androidx.activity.compose, libs.androidx.compose.ui 등 모두 제거됨


    // 4. 테스트 관련 종속성 유지
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Compose 테스트 종속성 제거됨
}
