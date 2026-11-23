# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# ==================== 타이머 앱 전용 규칙 ====================

# 🔹 알람 및 서비스 클래스 보존
-keep class com.krdonon.timer.alarm.** { *; }
-keep class com.krdonon.timer.ClockService { *; }
-keep class com.krdonon.timer.BootReceiver { *; }

# 🔹 BroadcastReceiver 보존 (리플렉션 사용)
-keep public class * extends android.content.BroadcastReceiver

# 🔹 Service 보존
-keep public class * extends android.app.Service

# 🔹 Activity 보존
-keep public class * extends androidx.appcompat.app.AppCompatActivity

# 🔹 Fragment 보존
-keep public class * extends androidx.fragment.app.Fragment

# 🔹 ViewModel 보존
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# 🔹 Serializable/Parcelable 보존
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 🔹 Enum 보존
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 🔹 Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# ==================== AndroidX 라이브러리 규칙 ====================

# AndroidX Core
-keep class androidx.core.** { *; }
-dontwarn androidx.core.**

# AndroidX AppCompat
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**

# AndroidX Fragment
-keep class androidx.fragment.** { *; }
-dontwarn androidx.fragment.**

# AndroidX Lifecycle
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# AndroidX Media
-keep class androidx.media.** { *; }
-dontwarn androidx.media.**

# ==================== Kotlin 규칙 ====================

# Kotlin 리플렉션
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}

# ==================== 일반 규칙 ====================

# 경고 무시
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Native methods 보존
-keepclasseswithmembernames class * {
    native <methods>;
}

# View 생성자 보존 (XML 레이아웃에서 사용)
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# onClick 메서드 보존 (XML에서 android:onClick 사용 시)
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# R 클래스 보존
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ==================== 최적화 설정 ====================

# 최적화 횟수 (기본값: 5)
-optimizationpasses 5

# 대소문자 혼용 클래스명 허용
-dontusemixedcaseclassnames

# 라이브러리 jar 사전 검증 건너뛰기
-dontpreverify

# 최적화 활성화
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# 로그 제거 (출시 버전)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}