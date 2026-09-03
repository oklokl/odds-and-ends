import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// GitHub에는 local.properties를 올리지 않고, 로컬 빌드 시에만 기상청 API 키를 읽습니다.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val kmaAuthKey = localProperties.getProperty("KMA_AUTH_KEY", "")

android {
    namespace = "com.krdonon.wweather"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.krdonon.wweather"
        minSdk = 24
        targetSdk = 37
        versionCode = 37
        versionName = "37.0"

        // 실제 키는 Git에 포함되지 않는 root/local.properties에만 둡니다.
        buildConfigField("String", "KMA_AUTH_KEY", "\"$kmaAuthKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true // 코드를 난독화하고 줄임
            isShrinkResources = true // 사용하지 않는 리소스를 제거 (추가)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 필요하면 네트워크 디버깅/로그용 설정을 여기서
        }
    }

    buildFeatures {
        // local.properties의 KMA 키를 BuildConfig에 주입하기 위해 필요합니다.
        buildConfig = true
        // 현재 코드는 findViewById 기반이며 ViewBinding을 사용하지 않으므로 불필요한 생성 코드를 만들지 않습니다.
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 네트워크
    implementation(libs.squareup.okhttp)

    // 위치 서비스 (FusedLocationProvider)
    implementation(libs.google.play.services.location)

    // Google Play Age Signals API 0.0.4 (공식 최신 2-function architecture)
    implementation("com.google.android.play:age-signals:0.0.4")

    // ✅ 코루틴 (코드에서 사용 중이므로 필수)
    implementation(libs.kotlinx.coroutines.android)

    // 테스트
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
