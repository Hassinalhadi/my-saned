# Keep Xposed & Android Manifest entry points while obfuscating internal methods & strings
-repackageclasses 'o'
-allowaccessmodification
-overloadaggressively
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn kotlinx.coroutines.**

-keep public class dev.saned.assistant.HookEntry {
    public <init>(...);
    public void onPackageLoaded(...);
    public void onSystemServerLoaded(...);
}
-keep public class dev.saned.assistant.ui.MainActivity {
    public <init>();
}
-keep public class dev.saned.assistant.SanedReceiver {
    public <init>();
    public void onReceive(android.content.Context, android.content.Intent);
}
-keep public class io.github.libxposed.service.XposedProvider {
    public <init>();
    public static *;
}
-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class dev.jing.sanedhook.util.Native {
    public static *;
}
-keep class dev.saned.assistant.OrderModel { *; }
-keep class dev.saned.assistant.SettingsStore { *; }
-keep class dev.saned.assistant.OrderInterceptor {
    public static final dev.saned.assistant.OrderInterceptor INSTANCE;
    public void initAppContext(android.content.Context);
    public void hookPackage(java.lang.ClassLoader, java.lang.String);
    public void hookLocation(java.lang.ClassLoader);
}
-keep class dev.saned.assistant.LocationEngine { *; }
-keep class dev.saned.assistant.DeviceSpoofer { *; }
-keep class dev.saned.assistant.PackageCloaker { *; }
-keep class dev.saned.assistant.DiagnosticLogger { *; }
-keep class dev.saned.assistant.NotificationHelper { *; }
