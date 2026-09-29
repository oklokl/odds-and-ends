# 디버깅 및 Crashlytics / Google Play Console 스택트레이스 복원용 라인 넘버 보존
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Google Play Age Signals SDK 보호
-keep class com.google.android.play.agesignals.** { *; }
-dontwarn com.google.android.play.agesignals.**

# WorkManager 및 Worker 클래스 리플렉션 생성자 보존
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.krdondon.week.notification.StatusNotificationWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 알림 및 위젯 리시버 보존
-keep class com.krdondon.week.notification.StatusNotificationReceiver { *; }
-keep class com.krdondon.week.TimeWidgetProvider { *; }
