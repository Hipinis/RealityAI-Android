# Add project specific ProGuard rules here.
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.reality.ai.MainActivity$NativeBridge { *; }

# WorkManager ProGuard rules
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.reality.ai.NotificationWorker { *; }
-keep class com.reality.ai.CacheManager { *; }
-keep class com.reality.ai.MainActivity { *; }
-keep class com.reality.ai.FCMService { *; }
-keep class com.reality.ai.AlarmReceiver { *; }

