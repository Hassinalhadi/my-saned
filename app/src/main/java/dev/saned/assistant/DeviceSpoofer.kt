package dev.saned.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object DeviceSpoofer {

    @Volatile var isSpoofAndroidId: Boolean = true
    @Volatile var customAndroidId: String = ""

    fun getEffectiveAndroidId(): String {
        // Priority 1: Direct file sync from Download folder (bypasses all sandbox/IPC limits)
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "saned_device_id.txt")
            if (file.exists()) {
                val id = file.readText().trim()
                if (id.isNotEmpty() && id.length == 16) {
                    return id
                }
            }
        } catch (_: Throwable) {}

        // Priority 2: XSharedPreferences via LSPosed
        try {
            val pref = XSharedPreferences("dev.jing.sanedhook", "sanedhook_settings")
            if (pref.hasFileChanged()) pref.reload()
            val id = pref.getString("spoofed_android_id", "") ?: ""
            if (id.isNotEmpty()) return id
        } catch (_: Throwable) {}

        if (customAndroidId.isNotEmpty()) return customAndroidId
        return "7a8b9c0d1e2f3456" // Generic clean fallback (never original ID)
    }

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
                        val name = param.args[1] as? String
                        if (name == Settings.Secure.ANDROID_ID) {
                            param.result = getEffectiveAndroidId()
                            XposedBridge.log("SanedAssistant: Hooked Settings.Secure.getString -> " + param.result)
                        }
                    }
                }
            )

            // Hook getStringForUser
            for (m in Settings.Secure::class.java.declaredMethods) {
                if (m.name == "getStringForUser") {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val name = param.args[1] as? String
                            if (name == Settings.Secure.ANDROID_ID) {
                                param.result = getEffectiveAndroidId()
                                XposedBridge.log("SanedAssistant: Hooked getStringForUser -> " + param.result)
                            }
                        }
                    })
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking ANDROID_ID: ${t.message}")
        }

        // 2. Hook SharedPreferences.getString inside Jahez
        // In case Jahez cached the old ID on first install, intercept it directly!
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
                        val currentVal = param.result as? String ?: ""
                        if (key.contains("device_id") || key.contains("android_id") || key.contains("imei") || key.contains("unique_id") || currentVal.matches(Regex("^[0-9a-fA-F]{16}$"))) {
                            param.result = getEffectiveAndroidId()
                            XposedBridge.log("SanedAssistant: Intercepted SharedPreferences [$key] -> " + param.result)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. UI INJECTION HOOK: Hook TextView.setText
        // When Jahez pops up the "معرف الجهاز IMEI" dialog, it renders the 16-hex string on a TextView.
        // This hook intercepts the UI text rendering and ensures the custom ID is ALWAYS displayed!
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = param.args[0]?.toString() ?: return
                        if (text.matches(Regex("^[0-9a-fA-F]{16}$"))) {
                            val targetId = getEffectiveAndroidId()
                            param.args[0] = targetId
                            XposedBridge.log("SanedAssistant: Replaced UI TextView 16-hex -> $targetId")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 4. Hook Clipboard copy
        // When the user clicks the copy icon in the dialog, copy the spoofed ID
        try {
            XposedHelpers.findAndHookMethod(
                ClipboardManager::class.java,
                "setPrimaryClip",
                ClipData::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val clip = param.args[0] as? ClipData ?: return
                        if (clip.itemCount > 0) {
                            val text = clip.getItemAt(0)?.text?.toString() ?: return
                            if (text.matches(Regex("^[0-9a-fA-F]{16}$"))) {
                                val targetId = getEffectiveAndroidId()
                                param.args[0] = ClipData.newPlainText("IMEI", targetId)
                                XposedBridge.log("SanedAssistant: Replaced Clipboard text -> $targetId")
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 5. Hook TelephonyManager
        try {
            val tmClass = TelephonyManager::class.java
            val imeiHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = getEffectiveAndroidId()
                }
            }
            for (m in tmClass.declaredMethods) {
                if (m.name in listOf("getDeviceId", "getImei", "getMeid")) {
                    try { XposedBridge.hookMethod(m, imeiHook) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }
}
