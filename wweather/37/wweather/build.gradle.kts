plugins {
    // Android Gradle Plugin → 현재 환경에 맞춰 8.13.0 으로 설정
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false

    // Kotlin 2.0.21 사용
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false

    // Kotlin 2.0 이상에서는 Compose Compiler 플러그인이 필수
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
