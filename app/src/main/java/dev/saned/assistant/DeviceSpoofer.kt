package dev.saned.assistant

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object DeviceSpoofer {

    @Volatile var isSpoofAndroidId: Boolean = true
    @Volatile var spoofedAndroidId: String = "d41d8cd98f00b204"
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
                            XposedBridge.log("SanedAssistant: Spoofed ANDROID_ID via getString -> $spoofedAndroidId")
                        }
                    }
                }
            )

            // Also hook getStringForUser (which getString calls internally on Android 8+)
            try {
                for (m in Settings.Secure::class.java.declaredMethods) {
                    if (m.name == "getStringForUser") {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val resolver = param.args[0] as? ContentResolver
                                SettingsSync.syncFromProvider(resolver)

                                val name = param.args[1] as? String
                                if (name == Settings.Secure.ANDROID_ID && isSpoofAndroidId && spoofedAndroidId.isNotEmpty()) {
                                    param.result = spoofedAndroidId
                                    XposedBridge.log("SanedAssistant: Spoofed ANDROID_ID via getStringForUser -> $spoofedAndroidId")
                                }
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking ANDROID_ID: ${t.message}")
        }

        // 2. Hook TelephonyManager: getDeviceId, getImei, getMeid, getSubscriberId, getSimSerialNumber
        try {
            val tmClass = TelephonyManager::class.java
            val imeiHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isSpoofImei && spoofedImei.isNotEmpty()) {
                        param.result = spoofedImei
                        XposedBridge.log("SanedAssistant: Spoofed Telephony -> $spoofedImei")
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

        // 3. Hook Build.getSerial()
        try {
            for (m in Build::class.java.declaredMethods) {
                if (m.name == "getSerial") {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (isSpoofAndroidId && spoofedAndroidId.isNotEmpty()) {
                                param.result = "SANED" + spoofedAndroidId.take(8).uppercase()
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }
}
