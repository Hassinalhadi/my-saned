package dev.saned.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object DeviceSpoofer {

    @Volatile var isMasterRunning: Boolean = false
    @Volatile var isSpoofAndroidId: Boolean = false
    @Volatile var spoofedAndroidId: String = ""

    private var remotePrefs: SharedPreferences? = null

    fun initRemotePrefs(prefs: SharedPreferences) {
        remotePrefs = prefs
        syncSettings()
        try {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                syncSettings()
            }
        } catch (_: Throwable) {}
    }

    fun syncSettings() {
        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", false)
            isSpoofAndroidId = p.getBoolean("spoof_android_id", false)
            spoofedAndroidId = p.getString("spoofed_android_id", "") ?: ""
        }
    }

    fun getEffectiveAndroidId(): String {
        syncSettings()
        if (spoofedAndroidId.isNotEmpty() && spoofedAndroidId.length == 16) {
            return spoofedAndroidId
        }
        return "7a8b9c0d1e2f3456"
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 1. Hook Settings.Secure.getString(ContentResolver, String)
        try {
            val mGetString = Settings.Secure::class.java.getDeclaredMethod(
                "getString",
                ContentResolver::class.java,
                String::class.java
            )
            module.hook(mGetString).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val name = chain.args[1] as? String
                    syncSettings()
                    if (name == Settings.Secure.ANDROID_ID && isMasterRunning && isSpoofAndroidId) {
                        return getEffectiveAndroidId()
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 2. Hook Settings.Secure.getStringForUser
        for (m in Settings.Secure::class.java.declaredMethods) {
            if (m.name == "getStringForUser") {
                try {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val name = chain.args[1] as? String
                            syncSettings()
                            if (name == Settings.Secure.ANDROID_ID && isMasterRunning && isSpoofAndroidId) {
                                return getEffectiveAndroidId()
                            }
                            return chain.proceed()
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        // 3. Hook android.app.AlertDialog.Builder.setMessage(CharSequence)
        try {
            val bClass = Class.forName("android.app.AlertDialog\$Builder", false, classLoader)
            for (m in bClass.declaredMethods) {
                if (m.name == "setMessage" && m.parameterTypes.size == 1 && CharSequence::class.java.isAssignableFrom(m.parameterTypes[0])) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val msg = chain.args[0]?.toString() ?: ""
                            syncSettings()
                            if (isMasterRunning && isSpoofAndroidId && (msg.matches(Regex("^[0-9a-fA-F]{16}$")) || msg.contains("600d9587a82b2451"))) {
                                val newId = getEffectiveAndroidId()
                                val targetMethod = chain.thisObject.javaClass.getMethod("setMessage", CharSequence::class.java)
                                return targetMethod.invoke(chain.thisObject, newId)
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 4. Hook androidx.appcompat.app.AlertDialog.Builder.setMessage(CharSequence)
        try {
            val bClass = Class.forName("androidx.appcompat.app.AlertDialog\$Builder", false, classLoader)
            for (m in bClass.declaredMethods) {
                if (m.name == "setMessage" && m.parameterTypes.size == 1 && CharSequence::class.java.isAssignableFrom(m.parameterTypes[0])) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val msg = chain.args[0]?.toString() ?: ""
                            syncSettings()
                            if (isMasterRunning && isSpoofAndroidId && (msg.matches(Regex("^[0-9a-fA-F]{16}$")) || msg.contains("600d9587a82b2451"))) {
                                val newId = getEffectiveAndroidId()
                                val targetMethod = chain.thisObject.javaClass.getMethod("setMessage", CharSequence::class.java)
                                return targetMethod.invoke(chain.thisObject, newId)
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 5. Hook TextView.setText
        try {
            for (m in TextView::class.java.declaredMethods) {
                if (m.name == "setText" && m.parameterTypes.size == 1 && CharSequence::class.java.isAssignableFrom(m.parameterTypes[0])) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val text = chain.args[0]?.toString() ?: ""
                            syncSettings()
                            if (isMasterRunning && isSpoofAndroidId && (text.matches(Regex("^[0-9a-fA-F]{16}$")) || text == "600d9587a82b2451")) {
                                val newId = getEffectiveAndroidId()
                                val targetMethod = chain.thisObject.javaClass.getMethod("setText", CharSequence::class.java)
                                return targetMethod.invoke(chain.thisObject, newId)
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 6. Hook ClipboardManager.setPrimaryClip
        try {
            val mClip = ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java)
            module.hook(mClip).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val clip = chain.args[0] as? ClipData
                    syncSettings()
                    if (isMasterRunning && isSpoofAndroidId && clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString() ?: ""
                        if (text.matches(Regex("^[0-9a-fA-F]{16}$")) || text == "600d9587a82b2451") {
                            val newId = getEffectiveAndroidId()
                            val newClip = ClipData.newPlainText("IMEI", newId)
                            val targetMethod = chain.thisObject.javaClass.getMethod("setPrimaryClip", ClipData::class.java)
                            return targetMethod.invoke(chain.thisObject, newClip)
                        }
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }
}
