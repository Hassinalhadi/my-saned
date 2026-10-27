# Aggressive R8 Obfuscation for J-Driver
-repackageclasses 'o'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn kotlinx.coroutines.**
-dontwarn com.google.crypto.tink.**
-dontwarn androidx.security.crypto.**

# Keep Tink & AndroidX Security Crypto protobuf/keyset classes intact for EncryptedSharedPreferences reflection
-keep class com.google.crypto.tink.** { *; }
-keep interface com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-keep interface androidx.security.crypto.** { *; }

-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep public class dev.saned.assistant.HookEntry {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
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
    public boolean onCreate();
    public android.os.Bundle call(java.lang.String, java.lang.String, android.os.Bundle);
    public android.database.Cursor query(...);
    public java.lang.String getType(android.net.Uri);
    public android.net.Uri insert(android.net.Uri, android.content.ContentValues);
    public int delete(android.net.Uri, java.lang.String, java.lang.String[]);
    public int update(android.net.Uri, android.content.ContentValues, java.lang.String, java.lang.String[]);
}
-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
