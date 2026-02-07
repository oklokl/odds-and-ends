plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.krdondon.txt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.krdondon.txt"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "13.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
    }

    // 패키징 방식 관련 옵션(요청하신 설정 포함)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // PDF 처리
    implementation(libs.pdfbox.android)

    // JPEG2000(JPXDecode) 지원용.
    // - app/libs/JP2ForAndroid.aar 가 있으면 그 파일을 사용
    // - 없으면 원격(JitPack) 의존성을 사용 (임시)
    //
    // 중요: release(minifyEnabled=true)에서 R8 missing class 방지를 위해 implementation 권장
    val localJp2Aar = file("libs/JP2ForAndroid.aar")
    if (localJp2Aar.exists()) {
        implementation(files(localJp2Aar))
    } else {
        implementation(libs.jp2forandroid)
    }

    // 권한 처리
    implementation(libs.accompanist.permissions)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
