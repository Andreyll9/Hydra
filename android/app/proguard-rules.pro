# Hydra R8 / ProGuard Rules
-keep class mobile.** { *; }
-keep class go.** { *; }
-keep class com.teleghost.app.MainActivity$HydraNativeBridge { *; }
-keep class com.teleghost.app.TeleGhostService { *; }
-keep class com.teleghost.app.DirectReplyReceiver { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*,JavascriptInterface
-dontwarn mobile.**
-dontwarn go.**
