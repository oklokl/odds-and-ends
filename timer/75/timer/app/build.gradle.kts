plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(11)
}

android {
    namespace = "com.krdonon.timer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.krdonon.timer"
        minSdk = 26
        targetSdk = 37
        versionCode = 75
        versionName = "75.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // AGP 9.3+ 권장 방식: 코드(R8) + 리소스 최적화를 함께 활성화
            optimization {
                enable = true
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    lint {
        abortOnError = false
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }
}

dependencies {
    // Google Play Age Signals API (0.0.4: requestAgeSignalsAccess -> checkAgeSignals)
    implementation("com.google.android.play:age-signals:0.0.4")

    // 핵심 Android & Kotlin 확장
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // AppCompatActivity 및 Fragment 지원
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)

    // Material Design 컴포넌트
    implementation(libs.material)

    // ViewModel & LiveData
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Media (알람/사운드)
    implementation(libs.androidx.media)

    // Android 14+ (API 34+) 대응
    implementation(libs.androidx.core)

    // 테스트 관련 종속성
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}