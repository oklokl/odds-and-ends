// build.gradle.kts (Module: app)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.krdondon.thelordsprayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.krdondon.thelordsprayer"
        minSdk = 26
        targetSdk = 37
        versionCode = 17
        versionName = "17.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        release {
            // AGP 9.3+ 권장 DSL: R8 코드 최적화 + 최적화된 리소스 축소를 함께 사용합니다.
            optimization {
                enable = true
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.media)
    implementation(libs.play.age.signals)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
