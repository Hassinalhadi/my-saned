package dev.saned.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.os.Environment
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

object DeviceSpoofer {

    @Volatile var isSpoofAndroidId: Boolean = true
    @Volatile var spoofedAndroidId: String = ""
    @Volatile var isSpoofImei: Boolean = true
    @Volatile var spoofedImei: String = ""

    fun getEffectiveAndroidId(): String {
        // Priority 1: Direct file sync from Download folder
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

        // Priority 3: Synced from SettingsSync
        if (spoofedAndroidId.isNotEmpty()) return spoofedAndroidId

        return "7a8b9c0d1e2f3456" // Default custom ID (never original)
    }

    fun hook(classLoader: ClassLoader) {

        // 1. Hook android.app.AlertDialog.Builder.setMessage(CharSequence)
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.AlertDialog\$Builder",
                classLoader,
                "setMessage",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val msg = param.args[0]?.toString() ?: return
                        if (msg.matches(Regex("^[0-9a-fA-F]{16}$")) || msg.contains("600d9587a82b2451")) {
                            val newId = getEffectiveAndroidId()
                            param.args[0] = newId
                            XposedBridge.log("SanedAssistant: Replaced AlertDialog message -> $newId")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 2. Hook androidx.appcompat.app.AlertDialog.Builder.setMessage(CharSequence)
        try {
            XposedHelpers.findAndHookMethod(
                "androidx.appcompat.app.AlertDialog\$Builder",
                classLoader,
                "setMessage",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val msg = param.args[0]?.toString() ?: return
                        if (msg.matches(Regex("^[0-9a-fA-F]{16}$")) || msg.contains("600d9587a82b2451")) {
                            val newId = getEffectiveAndroidId()
                            param.args[0] = newId
                            XposedBridge.log("SanedAssistant: Replaced AppCompat AlertDialog message -> $newId")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. Hook TextView.setText
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
                        if (text.matches(Regex("^[0-9a-fA-F]{16}$")) || text == "600d9587a82b2451") {
                            param.args[0] = getEffectiveAndroidId()
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 4. Hook Clipboard copy icon
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
                            if (text.matches(Regex("^[0-9a-fA-F]{16}$")) || text == "600d9587a82b2451") {
                                param.args[0] = ClipData.newPlainText("IMEI", getEffectiveAndroidId())
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 5. Hook Settings.Secure.getString(ContentResolver, String)
        try {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java,
                "getString",
                ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String
                        if (name == Settings.Secure.ANDROID_ID && isSpoofAndroidId) {
                            param.result = getEffectiveAndroidId()
                        }
                    }
                }
            )

            for (m in Settings.Secure::class.java.declaredMethods) {
                if (m.name == "getStringForUser") {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val name = param.args[1] as? String
                            if (name == Settings.Secure.ANDROID_ID && isSpoofAndroidId) {
                                param.result = getEffectiveAndroidId()
                            }
                        }
                    })
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking ANDROID_ID: ${t.message}")
        }

        // 6. Hook SharedPreferences.getString inside Jahez
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
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }
}
