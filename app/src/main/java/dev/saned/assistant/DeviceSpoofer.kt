package dev.saned.assistant

import android.content.ContentResolver
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object DeviceSpoofer {

    @Volatile var isSpoofAndroidId: Boolean = true
    @Volatile var spoofedAndroidId: String = "600d9587a82b2451" // fallback to current or custom
    @Volatile var isSpoofImei: Boolean = true
    @Volatile var spoofedImei: String = "869402041234567"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 1. Hook Settings.Secure.getString(ContentResolver, String)
        try {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java,
                "getString",
                ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val resolver = param.args[0] as? ContentResolver
                        SettingsSync.syncFromProvider(resolver)

                        val name = param.args[1] as? String
                        if (name == Settings.Secure.ANDROID_ID && isSpoofAndroidId && spoofedAndroidId.isNotEmpty()) {
                            param.result = spoofedAndroidId
                            XposedBridge.log("SanedAssistant: Spoofed ANDROID_ID -> $spoofedAndroidId")
                        }
                    }
                }
            )

            // Hook getStringForUser
            for (m in Settings.Secure::class.java.declaredMethods) {
                if (m.name == "getStringForUser") {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val resolver = param.args[0] as? ContentResolver
                            SettingsSync.syncFromProvider(resolver)

                            val name = param.args[1] as? String
                            if (name == Settings.Secure.ANDROID_ID && isSpoofAndroidId && spoofedAndroidId.isNotEmpty()) {
                                param.result = spoofedAndroidId
                            }
                        }
                    })
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking ANDROID_ID: ${t.message}")
        }

        // 2. Hook SharedPreferences.getString inside net.jahez.fleets
        // Jahez caches the device ID in its SharedPreferences after first launch.
        // Hooking SharedPreferences.getString ensures that even if Jahez reads its cached value,
        // it always receives the spoofed Android ID!
        try {
            val spClass = XposedHelpers.findClass("android.app.SharedPreferencesImpl", classLoader)
            XposedHelpers.findAndHookMethod(
                spClass,
                "getString",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = (param.args[0] as? String)?.lowercase() ?: return
                        if (isSpoofAndroidId && spoofedAndroidId.isNotEmpty()) {
                            if (key.contains("device_id") || key.contains("android_id") || key.contains("imei") || key.contains("unique_id")) {
                                param.result = spoofedAndroidId
                                XposedBridge.log("SanedAssistant: Spoofed cached SharedPreferences [$key] -> $spoofedAndroidId")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: SharedPreferences hook note: ${t.message}")
        }

        // 3. Hook TelephonyManager IMEI/DeviceID
        try {
            val tmClass = TelephonyManager::class.java
            val imeiHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isSpoofImei && spoofedImei.isNotEmpty()) {
                        param.result = spoofedImei
                    }
                }
            }

            for (m in tmClass.declaredMethods) {
                if (m.name in listOf("getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber")) {
                    try {
                        XposedBridge.hookMethod(m, imeiHook)
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking TelephonyManager: ${t.message}")
        }
    }
}
