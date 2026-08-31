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
        versionCode = 14
        versionName = "14.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        jvmToolchain(11)
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
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.media)
    implementation("com.google.android.play:age-signals:0.0.4")
}
