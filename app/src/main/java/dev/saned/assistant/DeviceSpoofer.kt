package dev.saned.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File

object DeviceSpoofer {

    @Volatile var isMasterRunning: Boolean = true
    @Volatile var isSpoofAndroidId: Boolean = false
    @Volatile var spoofedAndroidId: String = ""

    private var remotePrefs: SharedPreferences? = null
    private val hex16Regex = Regex("^[0-9a-fA-F]{16}$")

    fun initRemotePrefs(prefs: SharedPreferences) {
        remotePrefs = prefs
        syncSettings()
        try {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                syncSettings()
            }
        } catch (_: Throwable) {}
    }

    fun syncSettings(resolver: ContentResolver? = null) {
        val activeResolver = resolver ?: OrderInterceptor.appContext?.contentResolver ?: OrderInterceptor.currentActivity?.contentResolver
        if (activeResolver != null) {
            try {
                val uri = Uri.parse("content://dev.jing.sanedhook.XposedService")
                val bundle = activeResolver.call(uri, "getSettings", null, null)
                if (bundle != null && !bundle.isEmpty) {
                    isMasterRunning = bundle.getBoolean("master_running", bundle.getBoolean("enabled", isMasterRunning))
                    isSpoofAndroidId = bundle.getBoolean("spoof_android_id", isSpoofAndroidId)
                    val sId = bundle.getString("spoofed_android_id")
                    if (!sId.isNullOrEmpty()) {
                        spoofedAndroidId = sId.trim()
                    }
                    return
                }
            } catch (_: Throwable) {}
        }

        try {
            val f = File("/data/local/tmp/saned_config.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                isMasterRunning = json.optBoolean("master_running", json.optBoolean("enabled", isMasterRunning))
                isSpoofAndroidId = json.optBoolean("spoof_android_id", isSpoofAndroidId)
                val sId = json.optString("spoofed_android_id", "")
                if (sId.isNotEmpty()) spoofedAndroidId = sId.trim()
            }
        } catch (_: Throwable) {}

        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
            isSpoofAndroidId = p.getBoolean("spoof_android_id", isSpoofAndroidId)
            val sId = p.getString("spoofed_android_id", "")
            if (!sId.isNullOrEmpty()) {
                spoofedAndroidId = sId.trim()
            }
        }
    }

    fun getEffectiveAndroidId(resolver: ContentResolver? = null): String {
        syncSettings(resolver)
        val clean = spoofedAndroidId.trim()
        if (clean.length == 16) {
            return clean
        }
        return "7a8b9c0d1e2f3456"
    }

    private fun isHex16Id(str: String): Boolean {
        val trimmed = str.trim()
        return trimmed.length == 16 && hex16Regex.matches(trimmed)
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
                    val resolver = chain.args.getOrNull(0) as? ContentResolver
                    val name = chain.args.getOrNull(1) as? String
                    if (name == Settings.Secure.ANDROID_ID) {
                        syncSettings(resolver)
                        if (isMasterRunning && isSpoofAndroidId) {
                            return getEffectiveAndroidId(resolver)
                        }
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
                            val resolver = chain.args.getOrNull(0) as? ContentResolver
                            val name = chain.args.getOrNull(1) as? String
                            if (name == Settings.Secure.ANDROID_ID) {
                                syncSettings(resolver)
                                if (isMasterRunning && isSpoofAndroidId) {
                                    return getEffectiveAndroidId(resolver)
                                }
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
                            val msg = chain.args.getOrNull(0)?.toString() ?: ""
                            if (msg.length >= 16) {
                                syncSettings()
                                if (isMasterRunning && isSpoofAndroidId && isHex16Id(msg)) {
                                    val newId = getEffectiveAndroidId()
                                    return chain.proceed(arrayOf(newId))
                                }
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
                            val msg = chain.args.getOrNull(0)?.toString() ?: ""
                            if (msg.length >= 16) {
                                syncSettings()
                                if (isMasterRunning && isSpoofAndroidId && isHex16Id(msg)) {
                                    val newId = getEffectiveAndroidId()
                                    return chain.proceed(arrayOf(newId))
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 5. Hook TextView.setText (guarded by fast length == 16 check to avoid Regex overhead on normal UI text)
        try {
            for (m in TextView::class.java.declaredMethods) {
                if (m.name == "setText" && m.parameterTypes.size == 1 && CharSequence::class.java.isAssignableFrom(m.parameterTypes[0])) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val seq = chain.args.getOrNull(0) as? CharSequence
                            if (seq != null && seq.length == 16) {
                                val text = seq.toString()
                                if (isHex16Id(text)) {
                                    syncSettings()
                                    if (isMasterRunning && isSpoofAndroidId) {
                                        val newId = getEffectiveAndroidId()
                                        if (text != newId) {
                                            return chain.proceed(arrayOf(newId))
                                        }
                                    }
                                }
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
                    val clip = chain.args.getOrNull(0) as? ClipData
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        if (isHex16Id(text)) {
                            syncSettings()
                            if (isMasterRunning && isSpoofAndroidId) {
                                val newId = getEffectiveAndroidId()
                                val newClip = ClipData.newPlainText("IMEI", newId)
                                return chain.proceed(arrayOf(newClip))
                            }
                        }
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }
}
