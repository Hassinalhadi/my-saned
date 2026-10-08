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

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 1. Hook Settings.Secure.getString(resolver, "android_id")
        try {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java,
                "getString",
                ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String
                        if (name == Settings.Secure.ANDROID_ID) {
                            // Spoof Android ID dynamically
                            val spoofed = HookEntry.spoofedAndroidId
                            if (spoofed.isNotEmpty()) {
                                param.result = spoofed
                                XposedBridge.log("SanedAssistant: Spoofed ANDROID_ID -> $spoofed")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking ANDROID_ID: ${t.message}")
        }

        // 2. Hook TelephonyManager IMEI / DeviceID
        try {
            val tmClass = TelephonyManager::class.java
            val imeiHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val spoofed = HookEntry.spoofedImei
                    if (spoofed.isNotEmpty()) {
                        param.result = spoofed
                        XposedBridge.log("SanedAssistant: Spoofed IMEI/DeviceId -> $spoofed")
                    }
                }
            }

            try { XposedHelpers.findAndHookMethod(tmClass, "getDeviceId", imeiHook) } catch (_: Throwable) {}
            try { XposedHelpers.findAndHookMethod(tmClass, "getDeviceId", Int::class.javaPrimitiveType, imeiHook) } catch (_: Throwable) {}
            try { XposedHelpers.findAndHookMethod(tmClass, "getImei", imeiHook) } catch (_: Throwable) {}
            try { XposedHelpers.findAndHookMethod(tmClass, "getImei", Int::class.javaPrimitiveType, imeiHook) } catch (_: Throwable) {}
            try { XposedHelpers.findAndHookMethod(tmClass, "getMeid", imeiHook) } catch (_: Throwable) {}
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking TelephonyManager: ${t.message}")
        }

        // 3. Hook Build Serial & Hardware fingerprint
        try {
            try {
                XposedHelpers.findAndHookMethod(Build::class.java, "getSerial", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = "SANED" + HookEntry.spoofedAndroidId.take(8).uppercase()
                    }
                })
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking Build: ${t.message}")
        }
    }
}
